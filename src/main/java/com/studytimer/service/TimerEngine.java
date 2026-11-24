package com.studytimer.service;

import com.studytimer.model.TimerStage;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TimerEngine {
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "study-timer-engine");
                thread.setDaemon(true);
                return thread;
            });

    private final TickListener tickListener;
    private final StageChangeListener stageChangeListener;
    private final Runnable finishedListener;

    // 音频回调，默认不执行任何操作
    private AudioListener audioListener = new AudioListener() {
        @Override
        public void onPlay(TimerStage stage) {
        }

        @Override
        public void onStop() {
        }
    };

    private StageEndListener stageEndListener = (stage, index, planned, elapsed, completed) -> {
    };

    private List<TimerStage> stages = List.of();

    private int currentIndex;
    private long remainingNanos;
    private long deadlineNanos;

    private boolean running;
    private boolean started;
    private boolean finished;

    // 当前阶段是否已经触发过音乐
    private boolean audioTriggered;

    public TimerEngine(
            TickListener tickListener,
            StageChangeListener stageChangeListener,
            Runnable finishedListener) {

        this.tickListener = tickListener;
        this.stageChangeListener = stageChangeListener;
        this.finishedListener = finishedListener;

        scheduler.scheduleAtFixedRate(
                this::pulse,
                0,
                200,
                TimeUnit.MILLISECONDS
        );
    }

    public synchronized void setAudioListener(AudioListener listener) {
        this.audioListener = listener;
    }

    public synchronized void setStageEndListener(StageEndListener listener) {
        this.stageEndListener = listener;
    }

    public synchronized void setStages(List<TimerStage> newStages) {
        if (running) return;

        emitStageEnded(currentRemainingNanos(), false);
        stopAudio();

        stages = List.copyOf(newStages);
        currentIndex = 0;
        started = false;
        finished = false;
        audioTriggered = false;

        remainingNanos = stages.isEmpty()
                ? 0
                : minutesToNanos(stages.get(0).getMinutes());
    }

    public synchronized void start() {
        if (stages.isEmpty() || finished || running) return;

        if (!started) {
            started = true;
            currentIndex = 0;
            remainingNanos = minutesToNanos(
                    stages.get(0).getMinutes()
            );
            stageChangeListener.onChanged(
                    stages.get(0),
                    currentIndex,
                    stages.size()
            );
        }

        deadlineNanos = System.nanoTime() + remainingNanos;
        running = true;
    }

    public synchronized void pause() {
        if (!running) return;

        remainingNanos = Math.max(
                0,
                deadlineNanos - System.nanoTime()
        );

        running = false;
        stopAudio();

        // 允许继续计时后重新触发播放
        audioTriggered = false;
    }

    public synchronized void skip() {
        if (stages.isEmpty() || finished) return;

        emitStageEnded(currentRemainingNanos(), false);
        stopAudio();
        audioTriggered = false;

        advanceStage();

        if (!finished && running) {
            deadlineNanos = System.nanoTime() + remainingNanos;
        }
    }

    public synchronized void reset() {
        emitStageEnded(currentRemainingNanos(), false);
        running = false;
        started = false;
        finished = false;
        currentIndex = 0;
        audioTriggered = false;

        stopAudio();

        remainingNanos = stages.isEmpty()
                ? 0
                : minutesToNanos(stages.get(0).getMinutes());

        tickListener.onTick(
                TimeUnit.NANOSECONDS.toSeconds(remainingNanos),
                currentIndex,
                stages.size()
        );
    }

    /**
     * 获取当前计时状态快照。
     *
     * 如果计时正在运行，计算截至当前时刻的剩余时间。
     * 快照只包含计时状态，不包含任何音频播放状态。
     */
    /**
     * 获取当前计时状态快照。
     */
    public synchronized Snapshot createSnapshot() {

        long nanos = running
                ? Math.max(0, deadlineNanos - System.nanoTime())
                : remainingNanos;

        long remainingSeconds = Math.max(
                0,
                (nanos + 999_999_999L) / 1_000_000_000L
        );

        return new Snapshot(
                currentIndex,
                remainingSeconds,
                started,
                finished
        );
    }

    /**
     * 恢复计时状态，但不自动启动计时或播放音频。
     */
    public synchronized void restorePaused(Snapshot snapshot) {

        if (snapshot == null) {
            throw new IllegalArgumentException("计时状态不能为空");
        }

        stopAudio();

        running = false;
        deadlineNanos = 0;
        audioTriggered = false;

        if (snapshot.finished()) {

            currentIndex = stages.size();
            started = true;
            finished = true;
            remainingNanos = 0;

            tickListener.onTick(0, currentIndex, stages.size());
            return;
        }

        if (stages.isEmpty()) {
            throw new IllegalStateException("没有可恢复的时间阶段");
        }

        int index = snapshot.currentIndex();
        long seconds = snapshot.remainingSeconds();

        if (index < 0 || index >= stages.size()) {
            throw new IllegalArgumentException("恢复阶段索引无效");
        }

        long maxSeconds = Math.max(
                1,
                stages.get(index).getMinutes()
        ) * 60L;

        if (seconds < 0 || seconds > maxSeconds) {
            throw new IllegalArgumentException("恢复剩余时间无效");
        }

        if (!snapshot.started() && index != 0) {
            throw new IllegalArgumentException("未启动状态的阶段索引无效");
        }

        currentIndex = index;
        started = snapshot.started();
        finished = false;

        remainingNanos = TimeUnit.SECONDS.toNanos(seconds);

        tickListener.onTick(
                seconds,
                currentIndex,
                stages.size()
        );
    }

    /**
     * 可持久化的计时状态。
     */
    public record Snapshot(
            int currentIndex,
            long remainingSeconds,
            boolean started,
            boolean finished
    ) {
    }

    public synchronized boolean isRunning() {
        return running;
    }

    private void pulse() {
        synchronized (this) {
            if (!running || finished) return;

            long now = System.nanoTime();

            if (now >= deadlineNanos) {
                emitStageEnded(0, true);
                stopAudio();
                audioTriggered = false;

                advanceStage();

                if (finished) return;

                deadlineNanos = System.nanoTime() + remainingNanos;
            }

            long remaining = Math.max(
                    0,
                    deadlineNanos - System.nanoTime()
            );

            // 检查是否到达音乐播放时间
            checkAudioTrigger(remaining);

            long seconds = Math.max(
                    0,
                    (remaining + 999_999_999L) / 1_000_000_000L
            );

            tickListener.onTick(
                    seconds,
                    currentIndex,
                    stages.size()
            );
        }
    }

    private void checkAudioTrigger(long remainingNanos) {
        if (audioTriggered || currentIndex >= stages.size()) {
            return;
        }

        TimerStage stage = stages.get(currentIndex);

        String audioFile = stage.getAudioFile();
        String phoneAudioId = stage.getPhoneAudioId();

// 电脑和手机都没有配置音频，不触发
        boolean hasComputerAudio =
                audioFile != null && !audioFile.isBlank();

        boolean hasPhoneAudio =
                phoneAudioId != null && !phoneAudioId.isBlank();

        if (!hasComputerAudio && !hasPhoneAudio) {
            return;
        }

        long leadNanos = TimeUnit.SECONDS.toNanos(
                stage.getAudioLeadSeconds()
        );

        if (remainingNanos <= leadNanos) {
            audioTriggered = true;

            try {
                audioListener.onPlay(stage);
            } catch (RuntimeException ex) {
                System.err.println("阶段音乐播放失败：" + ex.getMessage());
            }
        }
    }

    private void advanceStage() {
        currentIndex++;

        if (currentIndex >= stages.size()) {
            running = false;
            finished = true;
            remainingNanos = 0;

            stopAudio();

            finishedListener.run();
            return;
        }

        TimerStage stage = stages.get(currentIndex);

        remainingNanos = minutesToNanos(stage.getMinutes());
        audioTriggered = false;

        stageChangeListener.onChanged(
                stage,
                currentIndex,
                stages.size()
        );
    }

    private long currentRemainingNanos() {
        return running
                ? Math.max(0, deadlineNanos - System.nanoTime())
                : remainingNanos;
    }

    /** 当前阶段结束（自然结束、跳过、重置或换计划）时报告实际用时。 */
    private void emitStageEnded(long remaining, boolean completed) {
        if (!started || finished || currentIndex < 0 || currentIndex >= stages.size()) {
            return;
        }
        TimerStage stage = stages.get(currentIndex);
        long planned = Math.max(1, stage.getMinutes()) * 60L;
        long remainingSeconds = (remaining + 999_999_999L) / 1_000_000_000L;
        long elapsed = Math.max(0, Math.min(planned, planned - remainingSeconds));
        try {
            stageEndListener.onStageEnded(stage, currentIndex, planned, elapsed, completed);
        } catch (RuntimeException ex) {
            System.err.println("记录阶段失败：" + ex.getMessage());
        }
    }

    private void stopAudio() {
        try {
            audioListener.onStop();
        } catch (RuntimeException ex) {
            System.err.println("停止音乐失败：" + ex.getMessage());
        }
    }

    private long minutesToNanos(int minutes) {
        return TimeUnit.MINUTES.toNanos(
                Math.max(1, minutes)
        );
    }

    public void shutdown() {
        stopAudio();
        scheduler.shutdownNow();
    }

    @FunctionalInterface
    public interface TickListener {
        void onTick(long remainingSeconds, int currentIndex, int total);
    }

    @FunctionalInterface
    public interface StageChangeListener {
        void onChanged(TimerStage stage, int index, int total);
    }

    public interface AudioListener {
        void onPlay(TimerStage stage);

        void onStop();
    }

    @FunctionalInterface
    public interface StageEndListener {
        void onStageEnded(TimerStage stage, int index, long plannedSeconds,
                          long elapsedSeconds, boolean completed);
    }
}
