package com.studytimer.audio;

import com.studytimer.service.AppLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 生命保障的阶段转换音乐：电脑文件/文件夹 + 手机音频（可整个文件夹随机或顺序）。
 *
 * 两个来源都可以为空。电脑端是整个文件夹时按文件名顺序连续播放，播完从头循环，
 * 直到 {@link #stop()} 或设定的秒数到点。手机端文件夹由 AudioPlaybackCoordinator 负责接下一首。
 */
public final class RestMusicPlayer {

    private static final List<String> AUDIO_EXTENSIONS = List.of(
            "mp3", "flac", "m4a", "aac", "wav", "ogg", "oga", "opus", "wma");

    private final AudioPlaybackCoordinator coordinator;
    private final AtomicLong generation = new AtomicLong();
    /** 自己是否真的在播东西；为空时只停自己的音乐，不动计时的音乐。 */
    private final java.util.concurrent.atomic.AtomicBoolean playing =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "life-guard-music");
        thread.setDaemon(true);
        return thread;
    });

    public RestMusicPlayer(AudioPlaybackCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /**
     * 播放阶段转换音乐；两个来源都为空时只停止当前音乐。
     *
     * @param pcPath    电脑音乐：单个文件或整个文件夹（空 = 不播放）
     * @param phoneId   手机音乐：audioId 或 folder:RANDOM/ORDER:文件夹（空 = 不播放）
     * @param seconds   > 0 时到点自动停止
     */
    public void play(String pcPath, String phoneId, int volume, int seconds) {
        stop();
        long token = generation.get();
        List<Path> queue = pcQueue(pcPath);
        boolean hasPhone = phoneId != null && !phoneId.isBlank();
        if (queue.isEmpty() && !hasPhone) {
            return;
        }
        playing.set(true);
        if (!queue.isEmpty()) {
            playPcFile(token, queue, 0, volume);
        }
        if (hasPhone) {
            coordinator.playLifeGuardPhone(phoneId, volume);
        }
        if (seconds > 0) {
            scheduler.schedule(() -> {
                if (token == generation.get()) {
                    coordinator.stopLifeGuardAudio();
                    playing.set(false);
                }
            }, seconds, TimeUnit.SECONDS);
        }
    }

    public void stop() {
        generation.incrementAndGet();
        // 没有自己播过东西就不要停：stopAll 会连计时器的计划音乐和手机声音一起停。
        if (playing.getAndSet(false)) {
            coordinator.stopLifeGuardAudio();
        }
    }

    private void playPcFile(long token, List<Path> queue, int index, int volume) {
        if (token != generation.get()) {
            return;
        }
        Path file = queue.get(index % queue.size());
        coordinator.playLifeGuardFile(file, volume,
                () -> playPcFile(token, queue, (index + 1) % queue.size(), volume));
    }

    /** 电脑音乐路径 → 要依次播放的文件列表；空路径或空文件夹返回空列表。 */
    static List<Path> pcQueue(String pcPath) {
        List<Path> result = new ArrayList<>();
        if (pcPath == null || pcPath.isBlank()) {
            return result;
        }
        Path path;
        try {
            path = Path.of(pcPath.trim());
        } catch (RuntimeException e) {
            AppLog.warn("休息音乐路径无效：" + pcPath);
            return result;
        }
        if (Files.isDirectory(path)) {
            try (Stream<Path> files = Files.list(path)) {
                files.filter(Files::isRegularFile)
                        .filter(RestMusicPlayer::isAudioFile)
                        .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .forEach(result::add);
            } catch (IOException | RuntimeException e) {
                AppLog.warn("读取休息音乐文件夹失败：" + path + "（" + e.getMessage() + "）");
            }
            if (result.isEmpty()) {
                AppLog.warn("休息音乐文件夹里没有音频：" + path);
            }
            return result;
        }
        if (Files.isRegularFile(path)) {
            result.add(path);
        } else {
            AppLog.warn("休息音乐文件不存在：" + path);
        }
        return result;
    }

    private static boolean isAudioFile(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && AUDIO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
