package com.studytimer.service;

import com.studytimer.model.StudySession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StudySessionStoreTest {

    @TempDir
    Path dir;

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);

    private static StudySession session(LocalDate date, String stage, long elapsed) {
        return new StudySession(date.toString(), date + "T10:00", "p1", "计划",
                stage, 1500, elapsed, elapsed >= 1500, StudySessionStore.isFocusStage(stage));
    }

    @Test
    void recordsPersistAndSkipTinySessions() throws Exception {
        StudySessionStore store = new StudySessionStore(dir);
        store.load();
        assertTrue(store.add(session(TODAY, "专注", 1500)));
        assertFalse(store.add(session(TODAY, "专注", 3)));

        StudySessionStore reloaded = new StudySessionStore(dir);
        reloaded.load();
        assertEquals(1, reloaded.records().size());
        assertEquals(1500, reloaded.focusSeconds(TODAY));
    }

    @Test
    void restStagesAreNotFocus() throws Exception {
        StudySessionStore store = new StudySessionStore(dir);
        store.load();
        store.add(session(TODAY, "专注", 600));
        store.add(session(TODAY, "休息", 300));
        store.add(session(TODAY, "Short Break", 300));
        assertEquals(600, store.focusSeconds(TODAY));
    }

    @Test
    void dailyFocusAndStreak() throws Exception {
        StudySessionStore store = new StudySessionStore(dir);
        store.load();
        store.add(session(TODAY.minusDays(1), "专注", 600));
        store.add(session(TODAY.minusDays(2), "专注", 600));
        store.add(session(TODAY.minusDays(4), "专注", 600));

        Map<LocalDate, Long> week = store.dailyFocus(TODAY, 7);
        assertEquals(7, week.size());
        assertEquals(600L, week.get(TODAY.minusDays(1)));
        assertEquals(0L, week.get(TODAY));
        // 今天还没学，从昨天往前数
        assertEquals(2, store.streakDays(TODAY));

        store.add(session(TODAY, "专注", 120));
        assertEquals(3, store.streakDays(TODAY));
    }

    @Test
    void goalReachedOnlyOncePerDay() throws Exception {
        StudySessionStore store = new StudySessionStore(dir);
        store.load();
        store.setDailyGoalMinutes(20);
        store.add(session(TODAY, "专注", 600));
        assertFalse(store.markGoalReachedIfNew(TODAY));
        store.add(session(TODAY, "专注", 600));
        assertTrue(store.markGoalReachedIfNew(TODAY));
        assertFalse(store.markGoalReachedIfNew(TODAY));

        StudySessionStore reloaded = new StudySessionStore(dir);
        reloaded.load();
        assertEquals(20, reloaded.getDailyGoalMinutes());
        assertFalse(reloaded.markGoalReachedIfNew(TODAY));
    }

    @Test
    void corruptFileIsMovedAside() throws Exception {
        Files.writeString(dir.resolve(StudySessionStore.FILE_NAME), "{broken");
        StudySessionStore store = new StudySessionStore(dir);
        store.load();
        assertTrue(store.records().isEmpty());
        assertNotNull(store.takeRecoveryNotice());
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")));
        }
    }

    @Test
    void csvEscapesCommas() throws Exception {
        StudySessionStore store = new StudySessionStore(dir);
        store.load();
        store.add(new StudySession(TODAY.toString(), "x", "p", "a,b", "专注", 60, 60, true, true));
        Path csv = dir.resolve("out.csv");
        store.exportCsv(csv);
        assertTrue(Files.readString(csv).contains("\"a,b\""));
    }
}
