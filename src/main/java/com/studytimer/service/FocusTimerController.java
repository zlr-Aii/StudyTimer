package com.studytimer.service;

import com.studytimer.model.TimerStage;

import java.util.List;
import java.util.Objects;

/**
 * Coordinates focus-timer operations independently of the JavaFX interface.
 * The controller owns TimerEngine and exposes the application-level controls
 * needed by Main, while UI presentation remains in Main.
 */
public final class FocusTimerController {

    private final TimerEngine timerEngine;

    public FocusTimerController(
            TimerEngine.TickListener tickListener,
            TimerEngine.StageChangeListener stageChangeListener,
            Runnable finishedListener) {
        this.timerEngine = new TimerEngine(
                Objects.requireNonNull(tickListener, "tickListener"),
                Objects.requireNonNull(stageChangeListener, "stageChangeListener"),
                Objects.requireNonNull(finishedListener, "finishedListener")
        );
    }

    public void setAudioListener(AudioListener listener) {
        Objects.requireNonNull(listener, "listener");
        timerEngine.setAudioListener(new TimerEngine.AudioListener() {
            @Override
            public void onPlay(TimerStage stage) {
                listener.onPlay(stage);
            }

            @Override
            public void onStop() {
                listener.onStop();
            }
        });
    }

    public void setStageEndListener(TimerEngine.StageEndListener listener) {
        timerEngine.setStageEndListener(Objects.requireNonNull(listener, "listener"));
    }

    public void setStages(List<TimerStage> stages) {
        timerEngine.setStages(List.copyOf(stages));
    }

    public void start() {
        timerEngine.start();
    }

    public void pause() {
        timerEngine.pause();
    }

    public void toggle() {
        if (timerEngine.isRunning()) {
            timerEngine.pause();
        } else {
            timerEngine.start();
        }
    }

    public void skip() {
        timerEngine.skip();
    }

    public void reset() {
        timerEngine.reset();
    }


    /**
     * 获取当前计时状态。
     */
    public TimerEngine.Snapshot createSnapshot() {
        return timerEngine.createSnapshot();
    }

    /**
     * 恢复计时状态，恢复后保持暂停。
     */
    public void restorePaused(TimerEngine.Snapshot snapshot) {
        timerEngine.restorePaused(snapshot);
    }

    public boolean isRunning() {
        return timerEngine.isRunning();
    }

    public void shutdown() {
        timerEngine.shutdown();
    }

    public interface AudioListener {
        void onPlay(TimerStage stage);

        void onStop();
    }
}
