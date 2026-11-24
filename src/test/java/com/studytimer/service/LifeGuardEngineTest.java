package com.studytimer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LifeGuardEngineTest {

    private static final long MIN = 60_000L;

    /** 每秒推进一次，持续 minutes 分钟，返回最后一个非 NONE 事件。 */
    private static LifeGuardEngine.Event run(LifeGuardEngine e, long[] clock, int minutes, boolean holds) {
        LifeGuardEngine.Event last = LifeGuardEngine.Event.NONE;
        for (int i = 0; i < minutes * 60; i++) {
            clock[0] += 1000;
            LifeGuardEngine.Event ev = e.tick(clock[0], 0, holds);
            if (ev != LifeGuardEngine.Event.NONE) {
                last = ev;
            }
        }
        return last;
    }

    private static LifeGuardEngine engine(long[] clock) {
        LifeGuardEngine e = new LifeGuardEngine();
        e.setDurations(50, 10);
        e.tick(clock[0], 0, false);
        return e;
    }

    @Test
    void restStartsAfterWorkAndEndsAfterRest() {
        long[] clock = {1_000_000};
        LifeGuardEngine e = engine(clock);
        assertEquals(LifeGuardEngine.Event.REST_STARTED, run(e, clock, 50, false));
        assertEquals(LifeGuardEngine.Phase.REST, e.getPhase());
        assertEquals(LifeGuardEngine.Event.REST_ENDED, run(e, clock, 10, false));
        assertEquals(LifeGuardEngine.Phase.WORK, e.getPhase());
        assertEquals(50 * MIN, e.getRemainingWorkMs());
    }

    @Test
    void activePlanPausesWorkCounting() {
        long[] clock = {1_000_000};
        LifeGuardEngine e = engine(clock);
        run(e, clock, 20, false);
        run(e, clock, 60, true);
        assertEquals(30 * MIN, e.getRemainingWorkMs());
        assertEquals(LifeGuardEngine.Phase.WORK, e.getPhase());
    }

    @Test
    void longIdleCountsAsRest() {
        long[] clock = {1_000_000};
        LifeGuardEngine e = engine(clock);
        run(e, clock, 30, false);
        clock[0] += 1000;
        assertEquals(LifeGuardEngine.Event.RESET_BY_IDLE, e.tick(clock[0], 10 * MIN, false));
        assertEquals(50 * MIN, e.getRemainingWorkMs());
    }

    @Test
    void shortIdleStillCountsAsWork() {
        long[] clock = {1_000_000};
        LifeGuardEngine e = engine(clock);
        run(e, clock, 30, false);
        clock[0] += 1000;
        e.tick(clock[0], 9 * MIN, false);
        assertTrue(e.getRemainingWorkMs() < 20 * MIN);
    }

    @Test
    void gapLongerThanRestCountsAsRest() {
        long[] clock = {1_000_000};
        LifeGuardEngine e = engine(clock);
        run(e, clock, 30, false);
        clock[0] += 3 * 60 * MIN; // 睡眠 3 小时
        assertEquals(LifeGuardEngine.Event.RESET_BY_IDLE, e.tick(clock[0], 0, false));
        assertEquals(50 * MIN, e.getRemainingWorkMs());
    }

    @Test
    void idleDuringRestEndsRest() {
        long[] clock = {1_000_000};
        LifeGuardEngine e = engine(clock);
        run(e, clock, 50, false);
        clock[0] += 1000;
        assertEquals(LifeGuardEngine.Event.REST_ENDED, e.tick(clock[0], 10 * MIN, false));
    }

    @Test
    void postponeLimitedToTwicePerRound() {
        long[] clock = {1_000_000};
        LifeGuardEngine e = engine(clock);
        run(e, clock, 50, false);
        assertTrue(e.postpone());
        assertEquals(LifeGuardEngine.Event.REST_STARTED, run(e, clock, 5, false));
        assertTrue(e.postpone());
        assertEquals(LifeGuardEngine.Event.REST_STARTED, run(e, clock, 5, false));
        assertFalse(e.postpone());
        assertEquals(LifeGuardEngine.Event.REST_ENDED, run(e, clock, 10, false));
        assertEquals(LifeGuardEngine.MAX_POSTPONES, e.getPostponesLeft());
    }

    @Test
    void durationsAreClamped() {
        LifeGuardEngine e = new LifeGuardEngine();
        e.setDurations(999, 0);
        assertEquals(LifeGuardEngine.MAX_WORK_MINUTES, e.getWorkMinutes());
        assertEquals(LifeGuardEngine.MIN_REST_MINUTES, e.getRestMinutes());
    }

    @Test
    void snapshotRestoreKeepsProgress() {
        long[] clock = {1_000_000};
        LifeGuardEngine e = engine(clock);
        run(e, clock, 20, false);
        LifeGuardEngine copy = new LifeGuardEngine();
        copy.setDurations(50, 10);
        copy.restore(e.snapshot());
        assertEquals(e.getRemainingWorkMs(), copy.getRemainingWorkMs());
        clock[0] += 60_000;
        copy.tick(clock[0], 0, false);
        assertEquals(29 * MIN, copy.getRemainingWorkMs());
    }

    @Test
    void storeRoundTripAndNormalize(@TempDir Path dir) throws Exception {
        LifeGuardStore store = new LifeGuardStore(dir);
        store.load();
        LifeGuardStore.Settings s = store.get();
        assertTrue(s.enabled);
        assertEquals(3, s.questions.size());
        s.workMinutes = 500;
        s.questions.get(1).answer = "  就是想 ";
        String[] pw = LifeGuardBarrier.hashNewPassword("abc");
        s.passwordHash = pw[0];
        s.passwordSalt = pw[1];
        store.save(s);

        LifeGuardStore again = new LifeGuardStore(dir);
        again.load();
        LifeGuardStore.Settings loaded = again.get();
        assertEquals(LifeGuardEngine.MAX_WORK_MINUTES, loaded.workMinutes);
        assertEquals("就是想", loaded.questions.get(1).answer);
        assertTrue(LifeGuardBarrier.passwordMatches(loaded, "abc"));
        assertFalse(LifeGuardBarrier.passwordMatches(loaded, "abd"));
        assertFalse(LifeGuardBarrier.passwordMatches(loaded, ""));
    }

    @Test
    void answerMatching() {
        assertTrue(LifeGuardBarrier.answerMatches("", "随便"));
        assertFalse(LifeGuardBarrier.answerMatches("", "  "));
        assertTrue(LifeGuardBarrier.answerMatches("我确定", " 我确定 "));
        assertFalse(LifeGuardBarrier.answerMatches("我确定", "确定"));
    }
}
