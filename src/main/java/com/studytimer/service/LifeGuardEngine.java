package com.studytimer.service;

/**
 * 生命保障计时逻辑（不依赖界面，时间由调用方传入，便于测试）。
 *
 * 工作阶段累计使用时间，到点进入休息；键鼠连续空闲达到休息时长、或两次检查间隔达到休息时长
 * （睡眠/关机/程序未运行）都视为已经休息，工作计时清零。主动 Focus Plan 占用时工作计时暂停。
 */
public final class LifeGuardEngine {

    public enum Phase { WORK, REST }

    public enum Event { NONE, REST_STARTED, REST_ENDED, RESET_BY_IDLE }

    public static final int MIN_WORK_MINUTES = 5;
    public static final int MAX_WORK_MINUTES = 120;
    public static final int MIN_REST_MINUTES = 5;
    public static final int MAX_REST_MINUTES = 60;
    public static final int POSTPONE_MINUTES = 5;
    public static final int MAX_POSTPONES = 2;
    /** 主动计划暂停超过这个时长，生命保障恢复计时。 */
    public static final long ACTIVE_PAUSE_LIMIT_MS = 10 * 60_000L;

    private int workMinutes = 50;
    private int restMinutes = 10;

    private Phase phase = Phase.WORK;
    private long workMs;
    private long restRemainingMs;
    private int postponesUsed;
    private long lastTickMs;

    public static int clampWork(int minutes) {
        return Math.max(MIN_WORK_MINUTES, Math.min(MAX_WORK_MINUTES, minutes));
    }

    public static int clampRest(int minutes) {
        return Math.max(MIN_REST_MINUTES, Math.min(MAX_REST_MINUTES, minutes));
    }

    public void setDurations(int workMinutes, int restMinutes) {
        this.workMinutes = clampWork(workMinutes);
        this.restMinutes = clampRest(restMinutes);
        if (phase == Phase.REST) {
            restRemainingMs = Math.min(restRemainingMs, restMs());
        }
    }

    /**
     * @param nowMs             当前时间（epoch 毫秒）
     * @param idleMs            键鼠已连续空闲的毫秒数
     * @param activePlanHolds   主动 Focus Plan 正在占用（运行中，或暂停未超过 10 分钟）
     */
    public Event tick(long nowMs, long idleMs, boolean activePlanHolds) {
        long dt = lastTickMs <= 0 ? 0 : Math.max(0, nowMs - lastTickMs);
        lastTickMs = nowMs;

        if (dt >= restMs() || idleMs >= restMs()) {
            boolean wasRest = phase == Phase.REST;
            boolean hadWork = workMs > 0;
            resetToWork(nowMs);
            if (wasRest) {
                return Event.REST_ENDED;
            }
            return hadWork ? Event.RESET_BY_IDLE : Event.NONE;
        }

        if (phase == Phase.REST) {
            restRemainingMs -= dt;
            if (restRemainingMs <= 0) {
                resetToWork(nowMs);
                return Event.REST_ENDED;
            }
            return Event.NONE;
        }

        if (activePlanHolds) {
            return Event.NONE;
        }

        workMs += dt;
        if (workMs >= workMs()) {
            phase = Phase.REST;
            restRemainingMs = restMs();
            return Event.REST_STARTED;
        }
        return Event.NONE;
    }

    /** 休息中推迟 5 分钟；每轮最多 2 次，用完返回 false。 */
    public boolean postpone() {
        if (phase != Phase.REST || postponesUsed >= MAX_POSTPONES) {
            return false;
        }
        postponesUsed++;
        phase = Phase.WORK;
        workMs = Math.max(0, workMs() - POSTPONE_MINUTES * 60_000L);
        restRemainingMs = 0;
        return true;
    }

    public void resetToWork(long nowMs) {
        phase = Phase.WORK;
        workMs = 0;
        restRemainingMs = 0;
        postponesUsed = 0;
        lastTickMs = nowMs;
    }

    public Phase getPhase() {
        return phase;
    }

    public long getRemainingWorkMs() {
        return Math.max(0, workMs() - workMs);
    }

    public long getRestRemainingMs() {
        return Math.max(0, restRemainingMs);
    }

    public int getPostponesLeft() {
        return MAX_POSTPONES - postponesUsed;
    }

    public int getWorkMinutes() {
        return workMinutes;
    }

    public int getRestMinutes() {
        return restMinutes;
    }

    public State snapshot() {
        State s = new State();
        s.phase = phase.name();
        s.workMs = workMs;
        s.restRemainingMs = restRemainingMs;
        s.postponesUsed = postponesUsed;
        s.lastTickMs = lastTickMs;
        return s;
    }

    public void restore(State s) {
        if (s == null) {
            return;
        }
        phase = "REST".equals(s.phase) ? Phase.REST : Phase.WORK;
        workMs = Math.max(0, s.workMs);
        restRemainingMs = Math.max(0, Math.min(s.restRemainingMs, restMs()));
        postponesUsed = Math.max(0, Math.min(MAX_POSTPONES, s.postponesUsed));
        lastTickMs = Math.max(0, s.lastTickMs);
        if (phase == Phase.REST && restRemainingMs <= 0) {
            resetToWork(lastTickMs);
        }
    }

    private long workMs() {
        return workMinutes * 60_000L;
    }

    private long restMs() {
        return restMinutes * 60_000L;
    }

    /** 可持久化的计时状态。 */
    public static final class State {
        public String phase = "WORK";
        public long workMs;
        public long restRemainingMs;
        public int postponesUsed;
        public long lastTickMs;
        /** 程序运行中为 true，正常退出时写回 false；启动时仍为 true 说明上次被强行结束。 */
        public boolean running;
    }
}
