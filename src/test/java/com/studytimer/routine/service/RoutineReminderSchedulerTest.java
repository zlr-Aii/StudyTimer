package com.studytimer.routine.service;

import com.studytimer.routine.model.RoutineReminder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoutineReminderSchedulerTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;

    @TempDir
    Path dir;

    private RoutineReminderController controller;
    private final List<RoutineReminderScheduler.TriggeredReminder> fired = new ArrayList<>();
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void setUp() throws IOException {
        controller = new RoutineReminderController(
                new RoutineReminderStorageService(dir.resolve("r.json")));
        controller.initialize();
    }

    private RoutineReminderScheduler scheduler() {
        return new RoutineReminderScheduler(controller, fired::add,
                e -> fail(e), clock, 1_000);
    }

    // 2026-11-16 是周一
    private static final LocalDateTime MONDAY_0830 = LocalDateTime.of(2026, 11, 16, 8, 30, 5);

    private RoutineReminder weekly(String time, String... days) {
        return new RoutineReminder(null, "R", time, Set.of(days), true, "", "NONE", "");
    }

    @Test
    void weeklyFiresOnMatchingDayAndMinuteOnlyOnce() throws IOException {
        controller.add(weekly("08:30", "MONDAY"));
        var s = scheduler();

        clock.set(MONDAY_0830.minusMinutes(1));
        s.checkNow();
        assertTrue(fired.isEmpty());

        clock.set(MONDAY_0830);
        s.checkNow();
        s.checkNow();
        clock.set(MONDAY_0830.plusSeconds(30));
        s.checkNow();
        assertEquals(1, fired.size(), "同一天同一分钟只触发一次");
    }

    @Test
    void weeklyDoesNotFireOnOtherDay() throws IOException {
        controller.add(weekly("08:30", "TUESDAY"));
        var s = scheduler();
        clock.set(MONDAY_0830);
        s.checkNow();
        assertTrue(fired.isEmpty());
    }

    @Test
    void disabledReminderNeverFires() throws IOException {
        RoutineReminder r = weekly("08:30", "MONDAY");
        r.setEnabled(false);
        controller.add(r);
        var s = scheduler();
        clock.set(MONDAY_0830);
        s.checkNow();
        assertTrue(fired.isEmpty());
    }

    @Test
    void oneTimeFiresOnceAndIsMarkedCompleted() throws IOException {
        RoutineReminder r = weekly("08:30", "MONDAY");
        r.setRepeatDays(Set.of());
        r.setOnceDate("2026-11-16");
        controller.add(r);
        var s = scheduler();

        clock.set(MONDAY_0830);
        s.checkNow();
        assertEquals(1, fired.size());
        assertTrue(controller.getReminders().get(0).isCompleted());

        clock.set(MONDAY_0830.plusDays(7));
        s.checkNow();
        assertEquals(1, fired.size());
    }

    @Test
    void missedOneTimeIsCompletedWithoutFiring() throws IOException {
        RoutineReminder r = weekly("08:30", "MONDAY");
        r.setRepeatDays(Set.of());
        r.setOnceDate("2026-11-16");
        controller.add(r);
        var s = scheduler();

        clock.set(MONDAY_0830.plusHours(2));
        s.checkNow();
        assertTrue(fired.isEmpty());
        assertTrue(controller.getReminders().get(0).isCompleted());
    }

    @Test
    void snoozeFiresAfterDelay() throws IOException {
        controller.add(weekly("08:30", "MONDAY"));
        var s = scheduler();
        clock.set(MONDAY_0830);
        s.checkNow();
        assertEquals(1, fired.size());

        s.snooze(fired.get(0), 5);
        assertEquals(1, s.getPendingSnoozeCount());

        clock.set(MONDAY_0830.plusMinutes(4));
        s.checkNow();
        assertEquals(1, fired.size());

        clock.set(MONDAY_0830.plusMinutes(5));
        s.checkNow();
        assertEquals(2, fired.size());
        assertEquals(0, s.getPendingSnoozeCount());
    }

    @Test
    void snoozeRejectsInvalidMinutes() throws IOException {
        controller.add(weekly("08:30", "MONDAY"));
        var s = scheduler();
        clock.set(MONDAY_0830);
        s.checkNow();
        assertThrows(IllegalArgumentException.class, () -> s.snooze(fired.get(0), 0));
        assertThrows(IllegalArgumentException.class, () -> s.snooze(fired.get(0), 24 * 60 + 1));
    }

    private RoutineReminderScheduler persistentScheduler() {
        return new RoutineReminderScheduler(controller, fired::add,
                e -> fail(e), clock, 1_000,
                new RoutineReminderStateStore(dir.resolve("state.json")));
    }

    private RoutineReminder oneTime(String date, String time) {
        RoutineReminder r = weekly(time, "MONDAY");
        r.setRepeatDays(Set.of());
        r.setOnceDate(date);
        return r;
    }

    @Test
    void weeklyMissedDuringSleepFiresOnceAsMissed() throws IOException {
        controller.add(weekly("08:30", "MONDAY"));
        var s = scheduler();
        clock.set(MONDAY_0830.minusHours(1));
        s.checkNow();

        clock.set(MONDAY_0830.plusHours(2)); // 睡眠 3 小时后唤醒
        s.checkNow();
        s.checkNow();
        assertEquals(1, fired.size());
        assertTrue(fired.get(0).missed());
        assertEquals(MONDAY_0830.withSecond(0), fired.get(0).scheduledAt());
    }

    @Test
    void onTimeTriggerIsNotMarkedMissed() throws IOException {
        controller.add(weekly("08:30", "MONDAY"));
        var s = scheduler();
        clock.set(MONDAY_0830.minusSeconds(10));
        s.checkNow();
        clock.set(MONDAY_0830);
        s.checkNow();
        assertEquals(1, fired.size());
        assertFalse(fired.get(0).missed());
    }

    @Test
    void missedOlderThanLimitIsDropped() throws IOException {
        controller.add(weekly("08:30", "MONDAY"));
        controller.add(oneTime("2026-11-16", "08:30"));
        var s = scheduler();
        clock.set(MONDAY_0830.minusHours(1));
        s.checkNow();

        clock.set(MONDAY_0830.plusHours(25));
        s.checkNow();
        assertTrue(fired.isEmpty());
        assertTrue(controller.getReminders().stream()
                .filter(RoutineReminder::isOneTime).findFirst().orElseThrow().isCompleted());
    }

    @Test
    void oneTimeMissedWhileAppClosedFiresAfterRestart() throws IOException {
        controller.add(oneTime("2026-11-16", "08:30"));
        clock.set(MONDAY_0830.minusHours(1));
        persistentScheduler().checkNow();

        clock.set(MONDAY_0830.plusHours(3)); // 关机后重新启动
        persistentScheduler().checkNow();
        assertEquals(1, fired.size());
        assertTrue(fired.get(0).missed());
        assertTrue(controller.getReminders().get(0).isCompleted());
    }

    @Test
    void firstRunWithoutStateDoesNotBackfill() throws IOException {
        controller.add(weekly("08:30", "MONDAY"));
        clock.set(MONDAY_0830.plusHours(1));
        persistentScheduler().checkNow();
        assertTrue(fired.isEmpty());
    }

    @Test
    void snoozeSurvivesRestart() throws IOException {
        controller.add(weekly("08:30", "MONDAY"));
        clock.set(MONDAY_0830);
        var first = persistentScheduler();
        first.checkNow();
        first.snooze(fired.get(0), 10);
        first.close();

        var second = persistentScheduler();
        assertEquals(1, second.getPendingSnoozeCount());
        clock.set(MONDAY_0830.plusMinutes(10));
        second.checkNow();
        assertEquals(2, fired.size());
        assertEquals(0, second.getPendingSnoozeCount());
    }

    @Test
    void snoozeOfDeletedReminderIsDropped() throws IOException {
        RoutineReminder r = controller.add(weekly("08:30", "MONDAY"));
        var s = scheduler();
        clock.set(MONDAY_0830);
        s.checkNow();
        s.snooze(fired.get(0), 5);
        controller.delete(r.getId());

        clock.set(MONDAY_0830.plusMinutes(5));
        s.checkNow();
        assertEquals(1, fired.size());
        assertEquals(0, s.getPendingSnoozeCount());
    }

    @Test
    void corruptStateFileIsIgnored() throws IOException {
        java.nio.file.Files.writeString(dir.resolve("state.json"), "{broken");
        controller.add(weekly("08:30", "MONDAY"));
        clock.set(MONDAY_0830);
        persistentScheduler().checkNow();
        assertEquals(1, fired.size());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.EPOCH;

        void set(LocalDateTime time) {
            now = time.atZone(ZONE).toInstant();
        }

        @Override public ZoneId getZone() { return ZONE; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
