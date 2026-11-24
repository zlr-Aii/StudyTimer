package com.studytimer.audio;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;

import java.nio.file.Files;
import java.nio.file.Path;

public class VlcAudioService implements AutoCloseable {

    private final MediaPlayerFactory factory;
    private final MediaPlayer player;

    /**
     * 回调线程。
     *
     * libvlc 发事件时持有内部锁，在事件回调里再调用播放器 API（例如播完接下一首）
     * 会和 libvlc 自己互等而永久卡住；一旦卡住，之后任何线程调用 stop()/play()
     * 都会一起阻塞（表现为界面冻结、托盘唤不回窗口）。所以 finished 回调先切到
     * 这个线程再执行。
     */
    private final java.util.concurrent.ExecutorService callbacks =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "vlc-audio-callback");
                thread.setDaemon(true);
                return thread;
            });

    public VlcAudioService() {
        // 自动查找本机 VLC 的 libVLC
        this.factory = new MediaPlayerFactory();

        // 创建纯音频播放器
        this.player = factory.mediaPlayers().newMediaPlayer();

        player.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mediaPlayer) {
                mediaPlayer.audio().setVolume(volume);
            }

            @Override
            public void finished(MediaPlayer mediaPlayer) {
                Runnable callback = onFinished;
                onFinished = null;
                if (callback == null) {
                    return;
                }
                // 不在 libvlc 事件线程里直接回调：回调常会立刻播放下一首或停止
                try {
                    callbacks.execute(callback);
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    // 播放器已关闭：丢弃这次回调。
                }
            }
        });
    }

    /** 本次播放自然结束时回调（VLC 事件线程）；调用 stop() 不会触发。 */
    private volatile Runnable onFinished;

    public void play(Path audioFile, Runnable whenFinished) {
        play(audioFile);
        onFinished = whenFinished;
    }

    /** 本次播放音量 0-100；VLC 开始播放后才接受音量，在 playing 事件里应用。 */
    private volatile int volume = 100;

    public void play(Path audioFile, int volume, Runnable whenFinished) {
        play(audioFile, whenFinished);
        this.volume = Math.max(0, Math.min(100, volume));
        player.audio().setVolume(this.volume);
    }

    public void play(Path audioFile) {
        String extension = audioFile == null ? "" : getExtension(audioFile);

        if (audioFile != null && !extension.equals("mp3") && !extension.equals("flac")) {
            throw new IllegalArgumentException(
                    "目前仅支持 MP3 和 FLAC：" + extension
            );
        }

        start(audioFile);
    }

    /** 不限制格式（手机音频库试听用：m4a/aac/wav/ogg 等 VLC 都能播）。 */
    public void playAnyFormat(Path audioFile, Runnable whenFinished) {
        start(audioFile);
        onFinished = whenFinished;
    }

    /** 不限制格式，并指定音量 0-100。 */
    public void playAnyFormat(Path audioFile, int volume, Runnable whenFinished) {
        playAnyFormat(audioFile, whenFinished);
        this.volume = Math.max(0, Math.min(100, volume));
        player.audio().setVolume(this.volume);
    }

    private void start(Path audioFile) {
        if (audioFile == null || !Files.isRegularFile(audioFile)) {
            throw new IllegalArgumentException(
                    "音频文件不存在：" + audioFile
            );
        }

        stop();
        onFinished = null;
        volume = 100;

        boolean started = player.media().play(
                audioFile.toAbsolutePath().normalize().toUri().toASCIIString()
        );

        if (!started) {
            throw new IllegalStateException("VLC 无法启动音频播放");
        }
    }

    /** 暂停或继续当前播放。 */
    public void setPaused(boolean paused) {
        player.controls().setPause(paused);
    }

    public void stop() {
        onFinished = null;
        player.controls().stop();
    }

    private String getExtension(Path path) {
        String name = path.getFileName().toString();
        int index = name.lastIndexOf('.');

        if (index < 0) {
            return "";
        }

        return name.substring(index + 1).toLowerCase();
    }

    @Override
    public void close() {
        callbacks.shutdownNow();
        try {
            player.release();
        } finally {
            factory.release();
        }
    }
}
