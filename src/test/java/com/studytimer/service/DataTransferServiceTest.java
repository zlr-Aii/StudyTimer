package com.studytimer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DataTransferServiceTest {

    @TempDir
    Path dir;

    private Path dataDir(String name) throws IOException {
        Path data = Files.createDirectories(dir.resolve(name));
        Files.createDirectories(data.resolve("plans"));
        return data;
    }

    @Test
    void exportSkipsLogsAndCache() throws Exception {
        Path data = dataDir("a");
        Files.writeString(data.resolve("routine-reminders.json"), "[1]");
        Files.writeString(data.resolve("plans/plan-1.json"), "{}");
        Files.createDirectories(data.resolve("logs"));
        Files.writeString(data.resolve("logs/x.log"), "log");
        Files.writeString(data.resolve("studytimer.lock"), "");

        Path zip = dir.resolve("out.zip");
        assertEquals(2, new DataTransferService(data).exportTo(zip));
        assertEquals(2, DataTransferService.validate(zip));
    }

    @Test
    void importIsStagedThenAppliedWithBackup() throws Exception {
        Path source = dataDir("src");
        Files.writeString(source.resolve("routine-reminders.json"), "[\"new\"]");
        Files.writeString(source.resolve("plans/plan-new.json"), "{\"n\":1}");
        Path zip = dir.resolve("export.zip");
        new DataTransferService(source).exportTo(zip);

        Path target = dataDir("dst");
        Files.writeString(target.resolve("routine-reminders.json"), "[\"old\"]");
        Files.writeString(target.resolve("plans/plan-old.json"), "{}");
        DataTransferService service = new DataTransferService(target);

        service.stageImport(zip);
        // 暂存阶段不改动现有数据
        assertEquals("[\"old\"]", Files.readString(target.resolve("routine-reminders.json")));
        assertTrue(service.hasPendingImport());

        Path backup = service.applyPendingImport();
        assertNotNull(backup);
        assertTrue(Files.isRegularFile(backup));
        assertFalse(service.hasPendingImport());
        assertEquals("[\"new\"]", Files.readString(target.resolve("routine-reminders.json")));
        assertTrue(Files.exists(target.resolve("plans/plan-new.json")));
        assertFalse(Files.exists(target.resolve("plans/plan-old.json")));

        // 备份里是导入前的数据
        Path restore = dataDir("restore");
        DataTransferService restoreService = new DataTransferService(restore);
        restoreService.stageImport(backup);
        restoreService.applyPendingImport();
        assertEquals("[\"old\"]", Files.readString(restore.resolve("routine-reminders.json")));
    }

    @Test
    void dailyBackupOncePerDayKeepsLatest() throws Exception {
        Path data = dataDir("auto");
        Files.writeString(data.resolve("routine-reminders.json"), "[]");
        DataTransferService service = new DataTransferService(data);
        java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 1);

        assertNotNull(service.createDailyBackup(day, 3));
        assertNull(service.createDailyBackup(day, 3));
        for (int i = 1; i <= 4; i++) {
            assertNotNull(service.createDailyBackup(day.plusDays(i), 3));
        }
        try (var files = Files.list(data.resolve("backups"))) {
            var names = files.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(java.util.List.of("auto-2026-11-03.zip", "auto-2026-11-04.zip", "auto-2026-11-05.zip"), names);
        }
    }

    @Test
    void dailyBackupSkipsEmptyData() throws Exception {
        assertNull(new DataTransferService(dataDir("empty")).createDailyBackup(java.time.LocalDate.now(), 7));
    }

    @Test
    void noPendingImportReturnsNull() throws Exception {
        assertNull(new DataTransferService(dataDir("x")).applyPendingImport());
    }

    @Test
    void rejectsZipWithoutMarker() throws Exception {
        Path zip = dir.resolve("other.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("routine-reminders.json"));
            out.write("[]".getBytes());
            out.closeEntry();
        }
        assertThrows(IOException.class,
                () -> new DataTransferService(dataDir("y")).stageImport(zip));
    }

    @Test
    void rejectsPathTraversal() {
        assertFalse(DataTransferService.isAllowed("../evil.json"));
        assertFalse(DataTransferService.isAllowed("plans/../../evil.json"));
        assertFalse(DataTransferService.isAllowed("plans/sub/a.json"));
        assertFalse(DataTransferService.isAllowed("logs/a.log"));
        assertTrue(DataTransferService.isAllowed("plans/plan-1.json"));
        assertTrue(DataTransferService.isAllowed("settings.json"));
    }
}
