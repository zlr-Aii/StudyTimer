package com.studytimer.ui;

import com.studytimer.audio.RestMusicPlayer;
import com.studytimer.service.AppLog;
import com.studytimer.service.IdleDetector;
import com.studytimer.service.LifeGuardEngine;
import com.studytimer.service.LifeGuardStore;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 生命保障：每秒驱动 {@link LifeGuardEngine}，显示顶部栏徽章和全屏休息窗口，定期保存状态。
 */
public final class LifeGuardController {

    private final LifeGuardStore store;
    private final LifeGuardEngine engine = new LifeGuardEngine();
    private final IdleDetector idleDetector = new IdleDetector();
    private final LifeGuardRestWindow restWindow = new LifeGuardRestWindow();
    private final Label badge = new Label();
    private final BooleanSupplier activeRunning;
    private final BooleanSupplier activePaused;
    private final Consumer<String> notifier;
    private final RestMusicPlayer music;

    private Timeline timeline;
    private long activePausedSinceMs;
    private int ticksSinceSave;
    /** 休息结束音乐的自动停止时间；0 = 不自动停止。 */
    private long musicStopAtMs;

    /**
     * @param activeRunning 主动 Focus Plan 正在运行
     * @param activePaused  主动 Focus Plan 已开始但处于暂停
     * @param notifier      状态栏提示
     */
    public LifeGuardController(LifeGuardStore store, BooleanSupplier activeRunning,
                               BooleanSupplier activePaused, Consumer<String> notifier) {
        this(store, activeRunning, activePaused, notifier, null);
    }

    /** audio 为 null 时阶段音乐不播放。 */
    public LifeGuardController(LifeGuardStore store, BooleanSupplier activeRunning,
                               BooleanSupplier activePaused, Consumer<String> notifier,
                               com.studytimer.audio.AudioPlaybackCoordinator audio) {
        this.store = store;
        this.activeRunning = activeRunning;
        this.activePaused = activePaused;
        this.notifier = notifier;
        this.music = audio == null ? null : new RestMusicPlayer(audio);
        badge.getStyleClass().addAll("status-badge", "status-neutral", "lifeguard-badge");
    }

    /** 试听（对话框用）：播放 15 秒后自动停止。 */
    public void previewMusic(String pcMusic, String phoneMusic, int volume) {
        if (music != null) {
            music.play(pcMusic, phoneMusic, volume, 15);
        }
    }

    public void stopPreview() {
        if (music != null) {
            music.stop();
        }
    }


    public Label getBadge() {
        return badge;
    }

    /** 读取设置和上次状态并开始计时。返回上次被强行结束时的提示，否则 null。 */
    public String start() {
        store.load();
        LifeGuardStore.Settings settings = store.get();
        engine.setDurations(settings.workMinutes, settings.restMinutes);
        LifeGuardEngine.State state = store.loadState();
        String notice = null;
        if (state != null) {
            engine.restore(state);
            if (state.running) {
                notice = "StudyTimer 上次没有正常退出（可能被强行结束），生命保障已继续计时";
                AppLog.warn(notice);
            }
        }
        saveState(true);

        timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
        tick();
        return notice;
    }

    public void shutdown() {
        if (timeline != null) {
            timeline.stop();
        }
        restWindow.close();
        stopMusic();
        saveState(false);
    }

    public LifeGuardStore.Settings getSettings() {
        return store.get();
    }

    /** 保存设置（开关、时长）并立即生效。 */
    public void applySettings(LifeGuardStore.Settings settings) throws java.io.IOException {
        boolean wasEnabled = store.get().enabled;
        store.save(settings);
        LifeGuardStore.Settings saved = store.get();
        engine.setDurations(saved.workMinutes, saved.restMinutes);
        if (saved.enabled && !wasEnabled) {
            engine.resetToWork(System.currentTimeMillis());
        }
        if (!saved.enabled) {
            restWindow.close();
            stopMusic();
        }
        AppLog.info("生命保障设置：开启=" + saved.enabled + "，工作 " + saved.workMinutes
                + " 分钟，休息 " + saved.restMinutes + " 分钟");
        saveState(true);
        tick();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        LifeGuardStore.Settings settings = store.get();
        if (!settings.enabled) {
            engine.resetToWork(now);
            restWindow.close();
            stopMusic();
            badge.setVisible(false);
            badge.setManaged(false);
            return;
        }
        badge.setVisible(true);
        badge.setManaged(true);

        boolean running = activeRunning.getAsBoolean();
        boolean paused = !running && activePaused.getAsBoolean();
        if (paused) {
            if (activePausedSinceMs == 0) {
                activePausedSinceMs = now;
            }
        } else {
            activePausedSinceMs = 0;
        }
        boolean holds = running
                || (paused && now - activePausedSinceMs < LifeGuardEngine.ACTIVE_PAUSE_LIMIT_MS);

        LifeGuardEngine.Event event = engine.tick(now, idleDetector.idleMillis(), holds);
        switch (event) {
            case REST_STARTED -> {
                AppLog.info("生命保障：开始休息 " + engine.getRestMinutes() + " 分钟");
                restWindow.show(engine.getRestMinutes(), settings.restHint,
                        engine.getPostponesLeft(), this::postpone);
                playMusic(settings.restPcMusic, settings.restPhoneMusic, settings.restMusicVolume, 0);
                saveState(true);
            }
            case REST_ENDED -> {
                AppLog.info("生命保障：休息结束");
                restWindow.close();
                notifier.accept("生命保障：休息结束，工作计时重新开始");
                playMusic(settings.workPcMusic, settings.workPhoneMusic, settings.workMusicVolume,
                        settings.workMusicSeconds);
                saveState(true);
            }
            case RESET_BY_IDLE -> AppLog.info("生命保障：键鼠空闲已达休息时长，工作计时清零");
            default -> {
            }
        }

        if (engine.getPhase() == LifeGuardEngine.Phase.REST) {
            if (!restWindow.isShowing()) {
                restWindow.show(engine.getRestMinutes(), settings.restHint,
                        engine.getPostponesLeft(), this::postpone);
                playMusic(settings.restPcMusic, settings.restPhoneMusic, settings.restMusicVolume, 0);
            }
            restWindow.update(engine.getRestRemainingMs());
            badge.setText("生命保障 休息中");
        } else if (holds) {
            badge.setText("生命保障 已暂停");
        } else {
            badge.setText("生命保障 " + format(engine.getRemainingWorkMs()));
        }

        // 休息结束的音乐到点自动停止；这时已经开始新一轮计划的话就不打断它
        if (musicStopAtMs > 0 && now >= musicStopAtMs) {
            musicStopAtMs = 0;
            if (!running) {
                stopMusic();
            }
        }

        if (++ticksSinceSave >= 30) {
            saveState(true);
        }
    }

    private void postpone() {
        if (engine.postpone()) {
            AppLog.info("生命保障：推迟休息 5 分钟，本轮还可推迟 " + engine.getPostponesLeft() + " 次");
            restWindow.close();
            stopMusic();
            notifier.accept("生命保障：已推迟 5 分钟");
            saveState(true);
            tick();
        } else {
            restWindow.setPostponesLeft(0);
        }
    }

    /** 播放阶段转换音乐；两个来源都为空时只停止当前音乐。 */
    private void playMusic(String pcMusic, String phoneMusic, int volume, int seconds) {
        musicStopAtMs = 0;
        if (music == null) {
            return;
        }
        music.play(pcMusic, phoneMusic, volume, 0);
        if (seconds > 0 && (notEmpty(pcMusic) || notEmpty(phoneMusic))) {
            musicStopAtMs = System.currentTimeMillis() + seconds * 1000L;
        }
    }

    private void stopMusic() {
        musicStopAtMs = 0;
        if (music != null) {
            music.stop();
        }
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.isBlank();
    }

    private void saveState(boolean running) {
        ticksSinceSave = 0;
        LifeGuardEngine.State state = engine.snapshot();
        state.running = running;
        store.saveState(state);
    }

    private static String format(long ms) {
        long seconds = (ms + 999) / 1000;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
