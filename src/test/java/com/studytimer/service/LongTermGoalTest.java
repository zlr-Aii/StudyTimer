package com.studytimer.service;

import com.studytimer.model.StudySession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LongTermGoalTest {

    // 2026-11-16 是周一
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);

    private static LongTermGoalStore.Goal goal() {
        LongTermGoalStore.Goal g = new LongTermGoalStore.Goal();
        g.id = "g1";
        g.name = "数学";
        g.planId = "p1";
        g.totalHours = 10;
        g.timesPerDay = 2;
        g.weekdays = List.of(1, 2, 3, 4, 5);
        g.startDate = MONDAY.toString();
        return g;
    }

    private static StudySession session(LocalDate day, String planId, long seconds, boolean focus) {
        return new StudySession(day.toString(), day + "T10:00:00", planId, "x", "s",
                seconds, seconds, true, focus);
    }

    @Test
    void countsOnlyFocusOfThatPlanSinceStart() {
        List<StudySession> records = List.of(
                session(MONDAY, "p1", 1500, true),
                session(MONDAY, "p1", 300, false),
                session(MONDAY, "p2", 1500, true),
                session(MONDAY.minusDays(1), "p1", 1500, true));
        LongTermGoalProgress p = LongTermGoalProgress.compute(goal(), records, 25, MONDAY);
        assertEquals(1500, p.doneSeconds());
        assertEquals(1500, p.todaySeconds());
        assertEquals(1, p.todayRuns());
        assertEquals(0, p.expectedSeconds());
        assertEquals((10 * 3600 - 1500 + 1499) / 1500, p.remainingRuns());
    }

    @Test
    void expectedSkipsUnscheduledDays() {
        // 到下周一：上周一~周五 5 天 × 2 遍 × 25 分钟
        LongTermGoalProgress p = LongTermGoalProgress.compute(goal(), List.of(), 25, MONDAY.plusDays(7));
        assertEquals(5 * 2 * 25 * 60, p.expectedSeconds());
        assertTrue(p.aheadSeconds() < 0);
        assertTrue(p.todayScheduled());
        LongTermGoalProgress sat = LongTermGoalProgress.compute(goal(), List.of(), 25, MONDAY.plusDays(5));
        assertFalse(sat.todayScheduled());
    }

    @Test
    void planChangeRecalculatesPerRun() {
        List<StudySession> records = List.of(session(MONDAY, "p1", 3000, true));
        LongTermGoalProgress p25 = LongTermGoalProgress.compute(goal(), records, 25, MONDAY);
        LongTermGoalProgress p50 = LongTermGoalProgress.compute(goal(), records, 50, MONDAY);
        assertEquals(p25.doneSeconds(), p50.doneSeconds());
        assertEquals(2, p25.todayRuns());
        assertEquals(1, p50.todayRuns());
        assertTrue(p50.remainingRuns() < p25.remainingRuns());
    }

    @Test
    void reachedAndFinishEstimate() {
        LongTermGoalProgress done = LongTermGoalProgress.compute(goal(),
                List.of(session(MONDAY, "p1", 10 * 3600, true)), 25, MONDAY);
        assertTrue(done.reached());
        assertEquals(0, done.remainingRuns());
        // 10 小时，每天 50 分钟，只排工作日：要 12 个工作日，第 12 个是第三周周二
        LongTermGoalProgress fresh = LongTermGoalProgress.compute(goal(), List.of(), 25, MONDAY);
        assertEquals(MONDAY.plusDays(15), fresh.estimatedFinish());
    }

    @Test
    void invalidPlanHasNoForecast() {
        LongTermGoalProgress p = LongTermGoalProgress.compute(goal(), List.of(), 0, MONDAY.plusDays(3));
        assertEquals(-1, p.remainingRuns());
        assertNull(p.estimatedFinish());
        assertEquals(0, p.expectedSeconds());
    }

    @Test
    void storeRoundTripAndCorruptFile(@TempDir Path dir) throws Exception {
        LongTermGoalStore store = new LongTermGoalStore(dir);
        store.load();
        LongTermGoalStore.Goal g = goal();
        g.id = null;
        g.weekdays = new java.util.ArrayList<>(List.of(7, 1, 1, 9));
        g.timesPerDay = 0;
        store.put(g);
        LongTermGoalStore again = new LongTermGoalStore(dir);
        again.load();
        LongTermGoalStore.Goal loaded = again.goals().get(0);
        assertNotNull(loaded.id);
        assertEquals(List.of(1, 7), loaded.weekdays);
        assertEquals(1, loaded.timesPerDay);
        again.delete(loaded.id);
        assertTrue(again.goals().isEmpty());

        Files.writeString(dir.resolve(LongTermGoalStore.FILE_NAME), "{bad");
        LongTermGoalStore broken = new LongTermGoalStore(dir);
        broken.load();
        assertTrue(broken.goals().isEmpty());
        assertNotNull(broken.takeRecoveryNotice());
    }
}
