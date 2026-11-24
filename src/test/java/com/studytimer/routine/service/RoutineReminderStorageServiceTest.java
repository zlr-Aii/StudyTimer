package com.studytimer.routine.service;

import com.studytimer.routine.model.RoutineReminder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoutineReminderStorageServiceTest {

    @TempDir
    Path dir;

    private static RoutineReminder reminder(String name) {
        return new RoutineReminder(null, name, "08:30", Set.of("MONDAY"),
                true, "msg", "NONE", "");
    }

    @Test
    void missingFileLoadsEmpty() throws IOException {
        var storage = new RoutineReminderStorageService(dir.resolve("r.json"));
        assertTrue(storage.loadAll().isEmpty());
        assertNull(storage.takeRecoveryNotice());
    }

    @Test
    void saveThenLoadRoundTrip() throws IOException {
        var storage = new RoutineReminderStorageService(dir.resolve("r.json"));
        RoutineReminder a = reminder("喝水");
        a.setAudioOutput("PHONE");
        storage.saveAll(List.of(a));

        List<RoutineReminder> loaded = storage.loadAll();
        assertEquals(1, loaded.size());
        assertEquals(a.getId(), loaded.get(0).getId());
        assertEquals("喝水", loaded.get(0).getName());
        assertEquals("PHONE", loaded.get(0).getAudioOutput());
    }

    @Test
    void secondSaveCreatesBackupOfPrevious() throws IOException {
        var storage = new RoutineReminderStorageService(dir.resolve("r.json"));
        storage.saveAll(List.of(reminder("A")));
        assertFalse(Files.exists(storage.backupFile()));

        storage.saveAll(List.of(reminder("A"), reminder("B")));
        assertTrue(Files.exists(storage.backupFile()));
    }

    @Test
    void corruptFileRecoversFromBackupAndKeepsOriginal() throws IOException {
        Path file = dir.resolve("r.json");
        var storage = new RoutineReminderStorageService(file);
        storage.saveAll(List.of(reminder("A")));
        storage.saveAll(List.of(reminder("A"), reminder("B")));

        Files.writeString(file, "{ broken");

        List<RoutineReminder> loaded = storage.loadAll();
        assertEquals(1, loaded.size(), "应恢复到上一次保存前的备份");
        String notice = storage.takeRecoveryNotice();
        assertNotNull(notice);
        assertNull(storage.takeRecoveryNotice(), "提示只返回一次");

        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("r.json.corrupt-")));
        }
        assertEquals(1, storage.loadAll().size(), "主文件已被修复");
    }

    @Test
    void corruptFileWithoutBackupThrows() throws IOException {
        Path file = dir.resolve("r.json");
        Files.writeString(file, "not json");
        var storage = new RoutineReminderStorageService(file);
        assertThrows(IOException.class, storage::loadAll);
        assertEquals("not json", Files.readString(file), "原文件不能被改动");
    }

    @Test
    void corruptMainDoesNotOverwriteGoodBackup() throws IOException {
        Path file = dir.resolve("r.json");
        var storage = new RoutineReminderStorageService(file);
        storage.saveAll(List.of(reminder("A")));
        storage.saveAll(List.of(reminder("A"), reminder("B")));
        String goodBackup = Files.readString(storage.backupFile());

        Files.writeString(file, "garbage");
        storage.saveAll(List.of(reminder("C")));

        assertEquals(goodBackup, Files.readString(storage.backupFile()));
    }

    @Test
    void duplicateIdsRejectedOnSave() {
        var storage = new RoutineReminderStorageService(dir.resolve("r.json"));
        RoutineReminder a = reminder("A");
        assertThrows(IllegalArgumentException.class, () -> storage.saveAll(List.of(a, a.copy())));
    }
}
