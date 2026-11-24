package com.studytimer.audio;

import com.studytimer.PolarBellConnectionState;
import com.studytimer.PolarBellService;
import com.studytimer.model.TimerStage;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Coordinates audio playback for the desktop player and PolarBell.
 *
 * This class deliberately does not own the lifecycle of either service:
 * the application creates and closes VlcAudioService and PolarBellService.
 */
public class AudioPlaybackCoordinator {

    private final VlcAudioService localAudio;
    private final VlcAudioService reminderAudio;
    private final PolarBellService polarBell;
    private final BiConsumer<String, Exception> errorHandler;
    private final AudioExtractor audioExtractor = new AudioExtractor();

    public AudioPlaybackCoordinator(
            VlcAudioService localAudio,
            PolarBellService polarBell,
            BiConsumer<String, Exception> errorHandler) {
        this(localAudio, localAudio, polarBell, errorHandler);
    }

    /** Creates independent VLC players for timer music and reminder media. */
    public AudioPlaybackCoordinator(
            VlcAudioService localAudio,
            VlcAudioService reminderAudio,
            PolarBellService polarBell,
            BiConsumer<String, Exception> errorHandler) {

        this.localAudio = Objects.requireNonNull(localAudio, "localAudio");
        this.reminderAudio = Objects.requireNonNull(reminderAudio, "reminderAudio");
        this.polarBell = Objects.requireNonNull(polarBell, "polarBell");
        this.errorHandler = errorHandler != null
                ? errorHandler
                : (message, error) -> {
                    System.err.println(message + ": " + error.getMessage());
                };
    }

    /** Plays all audio outputs configured for a timer stage. */
    public void playStage(TimerStage stage) {
        if (stage == null) {
            return;
        }

        // Keep the existing behavior: desktop and phone audio may play together.
        String localFile = stage.getAudioFile();
        if (localFile != null && !localFile.isBlank()) {
            try {
                Path path = Path.of(localFile);
                System.out.println("VLC 播放：" + path);
                localAudio.play(path);
            } catch (RuntimeException e) {
                errorHandler.accept("电脑音乐播放失败", e);
            }
        }

        String phoneAudioId = stage.getPhoneAudioId();
        if (PhoneFolderPlaylist.isFolderId(phoneAudioId)) {
            playPhoneFolder(phoneAudioId, stage.getPhoneVolume());
        } else if (phoneAudioId != null && !phoneAudioId.isBlank()) {
            playPhoneAudio(phoneAudioId, stage.getPhoneVolume());
        }
    }

    private final PhoneFolderPlaylist folderPlaylist = new PhoneFolderPlaylist();
    private final java.util.concurrent.atomic.AtomicLong folderGeneration =
            new java.util.concurrent.atomic.AtomicLong();

    /** 按文件夹播放：先读手机音频列表，再随机或按顺序选一首。 */
    private void playPhoneFolder(String phoneAudioId, int volume) {
        PhoneFolderPlaylist.Selection selection =
                PhoneFolderPlaylist.parse(phoneAudioId).orElse(null);
        if (selection == null) {
            errorHandler.accept("手机音频播放失败",
                    new IllegalArgumentException("无法识别的文件夹设置：" + phoneAudioId));
            return;
        }
        long generation = folderGeneration.incrementAndGet();
        polarBell.getAudioListAsync(
                audios -> {
                    // 读列表期间计时已停止或换了阶段：不再播放
                    if (generation != folderGeneration.get()) {
                        return;
                    }
                    folderPlaylist.next(selection, audios).ifPresentOrElse(
                        audio -> {
                            System.out.println("手机" + selection.label() + "：" + audio.getName());
                            playPhoneAudio(audio.getAudioId(), volume);
                            scheduleFolderCheck(selection, volume, generation);
                        },
                        () -> errorHandler.accept("手机音频播放失败",
                                new IllegalStateException(selection.label() + " 里没有音频")));
                },
                error -> errorHandler.accept("手机音频播放失败", error));
    }

    /** 每隔几秒问一次手机是否还在播放，播完就接下一首，直到阶段结束（stopAll）。 */
    private static final long FOLDER_CHECK_SECONDS = 3;

    private final java.util.concurrent.ScheduledExecutorService folderScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "phone-folder-next");
                thread.setDaemon(true);
                return thread;
            });

    private void scheduleFolderCheck(PhoneFolderPlaylist.Selection selection, int volume, long generation) {
        if (!polarBell.isPlayStateSupported()) {
            // 旧版手机 App 不能查询播放状态：只播一首
            return;
        }
        folderScheduler.schedule(() -> {
            if (generation != folderGeneration.get()) {
                return;
            }
            synchronized (phoneLock) {
                if (phoneOwner == PhoneOwner.REMINDER) {
                    // 提醒占用手机：等它结束（被延后的这首会补播）
                    scheduleFolderCheck(selection, volume, generation);
                    return;
                }
            }
            polarBell.getPlayStateAsync(playing -> {
                if (generation != folderGeneration.get()) {
                    return;
                }
                if (playing) {
                    scheduleFolderCheck(selection, volume, generation);
                } else {
                    playNextInFolder(selection, volume, generation);
                }
            }, error -> scheduleFolderCheck(selection, volume, generation));
        }, FOLDER_CHECK_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void playNextInFolder(PhoneFolderPlaylist.Selection selection, int volume, long generation) {
        polarBell.getAudioListAsync(audios -> {
            if (generation != folderGeneration.get()) {
                return;
            }
            folderPlaylist.next(selection, audios).ifPresent(audio -> {
                System.out.println("手机" + selection.label() + " 下一首：" + audio.getName());
                playPhoneAudio(audio.getAudioId(), volume);
                scheduleFolderCheck(selection, volume, generation);
            });
        }, error -> scheduleFolderCheck(selection, volume, generation));
    }

    /** Plays reminder media through its own VLC player, isolated from timer music. */
    public void playReminderAudio(Path audioFile) {
        if (audioFile == null) {
            throw new IllegalArgumentException("提醒音频文件路径为空");
        }
        reminderAudio.play(audioFile);
    }

    /** Stops only reminder media; timer music and PolarBell are unaffected. */
    public void stopReminderAudio() {
        reminderAudio.stop();
    }

    /** Stops playback on both desktop and phone. */
    public void stopAll() {
        folderGeneration.incrementAndGet();
        try {
            localAudio.stop();
        } catch (RuntimeException e) {
            errorHandler.accept("停止电脑音乐失败", e);
        }

        synchronized (phoneLock) {
            deferredTimerPhone = null;
            if (phoneOwner == PhoneOwner.REMINDER) {
                // 提醒正在用手机，计时停止不影响它。
                return;
            }
            phoneOwner = PhoneOwner.NONE;
        }
        polarBell.stopAsync(error ->
                errorHandler.accept("停止手机播放失败", error)
        );
    }

    /**
     * 手机只有一个播放器。提醒占用手机时，计时的手机声音先记下来，
     * 提醒结束后再播；停止命令只由当前占用者发出，避免互相误停。
     */
    private enum PhoneOwner { NONE, TIMER, REMINDER }

    private final Object phoneLock = new Object();
    private PhoneOwner phoneOwner = PhoneOwner.NONE;
    private Runnable deferredTimerPhone;

    /** 计时器要使用手机：提醒占用时延后执行，返回 false。 */
    private boolean claimPhoneForTimer(Runnable action) {
        synchronized (phoneLock) {
            if (phoneOwner == PhoneOwner.REMINDER) {
                deferredTimerPhone = action;
                return false;
            }
            phoneOwner = PhoneOwner.TIMER;
            return true;
        }
    }

    /** Plays the configured default completion sound on PolarBell. */
    public void playFinishSound() {
        if (!claimPhoneForTimer(this::playFinishSound)) {
            return;
        }
        long token = folderGeneration.incrementAndGet();
        String audioId = finishAudioId;
        long seconds = finishSeconds;
        if (audioId == null || audioId.isBlank()) {
            polarBell.playFinishSoundAsync(error ->
                    errorHandler.accept("完成提示音播放失败", error)
            );
            // 内置提示音是整首歌，必须限时
            if (seconds <= 0) {
                seconds = FINISH_SOUND_SECONDS;
            }
        } else {
            polarBell.playAudioAsync(audioId, finishVolume, error ->
                    errorHandler.accept("完成提示音播放失败", error));
        }
        if (seconds <= 0) {
            return;
        }
        // 计划结束后只响一小段，之后自动停止。
        folderScheduler.schedule(() -> {
            synchronized (phoneLock) {
                if (token != folderGeneration.get() || phoneOwner != PhoneOwner.TIMER) {
                    return;
                }
                phoneOwner = PhoneOwner.NONE;
            }
            polarBell.stopAsync(error -> errorHandler.accept("停止完成提示音失败", error));
        }, seconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static final long FINISH_SOUND_SECONDS = 15;

    private volatile String finishAudioId = "";
    private volatile long finishSeconds = FINISH_SOUND_SECONDS;
    private volatile int finishVolume = 100;

    /**
     * 完成提示音：audioId 为空 = 手机内置提示音（最多 15 秒），否则播放手机音频库中的音频；
     * seconds = 0 表示播完为止（仅音频库音频）。
     */
    public void setFinishSound(String audioId, int seconds, int volume) {
        finishAudioId = audioId == null ? "" : audioId;
        finishSeconds = Math.max(0, seconds);
        finishVolume = Math.max(0, Math.min(100, volume));
    }

    /**
     * 手机端是否已连接并支持文件上传播放。
     * 用于判断视频提醒能否把原声交给手机。
     */
    public boolean isPhoneUploadPlaybackAvailable() {
        return polarBell.getConnectionState() == PolarBellConnectionState.CONNECTED
                && polarBell.isPlayUploadSupported();
    }

    /** 手机端单次上传的大小上限（字节）。 */
    public long getPhoneUploadLimitBytes() {
        return polarBell.getUploadLimitBytes();
    }

    /**
     * 上传视频文件到手机，由手机播放其中的音轨。
     *
     * onStarted 和 onFailure 都在后台线程执行，
     * JavaFX 界面回调需要调用方自行切回 FX 线程。
     */
    public void playReminderVideoSoundOnPhone(
            Path videoFile,
            Runnable onStarted,
            Consumer<Throwable> onFailure) {
        playReminderVideoSoundOnPhone(videoFile, 100, onStarted, onFailure);
    }

    public void playReminderVideoSoundOnPhone(
            Path videoFile,
            int volume,
            Runnable onStarted,
            Consumer<Throwable> onFailure) {

        synchronized (phoneLock) {
            phoneOwner = PhoneOwner.REMINDER;
        }

        Consumer<Exception> failure = error -> {
            if (onFailure != null) {
                onFailure.accept(error);
            } else {
                errorHandler.accept("手机播放视频原声失败", error);
            }
        };

        if (!audioExtractor.isAvailable()) {
            polarBell.playUploadAsync(videoFile, volume, onStarted, failure);
            return;
        }

        // 提取音轨可能需要几秒，不占用 PolarBell 的通信线程。
        Thread worker = new Thread(() -> {
            Path upload = audioExtractor.extractAudio(videoFile).orElse(videoFile);
            polarBell.playUploadAsync(upload, volume, onStarted, failure);
        }, "audio-extract");
        worker.setDaemon(true);
        worker.start();
    }

    /** 把一个音频文件上传到手机播放（开屏音乐用），占用方式与提醒相同。 */
    public void playReminderFileOnPhone(
            Path audioFile,
            int volume,
            Runnable onStarted,
            Consumer<Throwable> onFailure) {
        synchronized (phoneLock) {
            phoneOwner = PhoneOwner.REMINDER;
        }
        polarBell.playUploadAsync(audioFile, volume, onStarted, error -> {
            if (onFailure != null) {
                onFailure.accept(error);
            } else {
                errorHandler.accept("手机播放音频失败", error);
            }
        });
    }

    /** 电脑上是否有 ffmpeg，可以只把视频音轨发给手机。 */
    public boolean isVideoAudioExtractionAvailable() {
        return audioExtractor.isAvailable();
    }

    // ==================== 生命保障的阶段转换音乐 ====================

    /**
     * 生命保障音乐（电脑端）：任意 VLC 支持的音频文件，用计时器的播放器，
     * 这样休息结束时的停止命令和计划音乐一致。文件夹由 RestMusicPlayer 逐首展开。
     */
    public void playLifeGuardFile(Path file, int volume, Runnable whenFinished) {
        if (file == null) {
            return;
        }
        try {
            localAudio.playAnyFormat(file, volume, whenFinished);
        } catch (RuntimeException e) {
            errorHandler.accept("休息音乐播放失败", e);
        }
    }

    /** 生命保障音乐（手机端）：单个音频或 folder:RANDOM/ORDER:文件夹（播完自动接下一首）。 */
    public void playLifeGuardPhone(String phoneAudioId, int volume) {
        if (phoneAudioId == null || phoneAudioId.isBlank()) {
            return;
        }
        if (!PhoneFolderPlaylist.isFolderId(phoneAudioId)) {
            playPhoneAudio(phoneAudioId, volume);
            return;
        }
        if (!claimPhoneForTimer(() -> playLifeGuardPhone(phoneAudioId, volume))) {
            return;
        }
        playPhoneFolder(phoneAudioId, volume);
    }

    /** 停止生命保障音乐（电脑和手机），不影响下一次播放。 */
    public void stopLifeGuardAudio() {
        stopAll();
    }

    /**
     * 提醒释放手机：只有提醒仍占用手机时才发送停止，
     * 然后补播提醒期间被延后的计时声音。
     */
    public void stopPhoneAudio() {
        Runnable deferred;
        synchronized (phoneLock) {
            if (phoneOwner != PhoneOwner.REMINDER) {
                return;
            }
            phoneOwner = PhoneOwner.NONE;
            deferred = deferredTimerPhone;
            deferredTimerPhone = null;
        }
        polarBell.stopAsync(error ->
                errorHandler.accept("停止手机播放失败", error)
        );
        if (deferred != null) {
            deferred.run();
        }
    }

    /** 提醒窗口关闭后手机才开始播放（上传迟到）：无条件停止这段声音。 */
    public void stopLateReminderPhoneAudio() {
        polarBell.stopAsync(error ->
                errorHandler.accept("停止手机播放失败", error)
        );
    }

    /** 提醒音频（电脑 VLC）；whenFinished 在自然播完时回调（后台线程）。 */
    public void playReminderAudio(Path audioFile, Runnable whenFinished) {
        playReminderAudio(audioFile, 100, whenFinished);
    }

    public void playReminderAudio(Path audioFile, int volume, Runnable whenFinished) {
        if (audioFile == null) {
            throw new IllegalArgumentException("提醒音频文件路径为空");
        }
        reminderAudio.play(audioFile, volume, whenFinished);
    }

    /** Plays one item from the phone's media library. */
    public void playPhoneAudio(String audioId) {
        playPhoneAudio(audioId, 100);
    }

    /** volume：手机播放音量 0-100。 */
    public void playPhoneAudio(String audioId, int volume) {
        if (audioId == null || audioId.isBlank()) {
            return;
        }
        if (!claimPhoneForTimer(() -> playPhoneAudio(audioId, volume))) {
            return;
        }

        polarBell.playAudioAsync(
                audioId,
                volume,
                error -> errorHandler.accept("手机音频播放失败", error)
        );
    }
}
