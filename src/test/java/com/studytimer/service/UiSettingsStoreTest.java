package com.studytimer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UiSettingsStoreTest {

    @TempDir
    Path dir;

    @Test
    void defaultsWhenMissingOrCorrupt() throws Exception {
        UiSettingsStore store = new UiSettingsStore(dir);
        store.load();
        assertTrue(store.get().showHints);
        assertTrue(store.get().showReminderPanel);

        Files.writeString(dir.resolve(UiSettingsStore.FILE_NAME), "{bad");
        store.load();
        assertTrue(store.get().showHints);
    }

    @Test
    void savesAndReloads() {
        UiSettingsStore store = new UiSettingsStore(dir);
        var s = store.get();
        s.showHints = false;
        s.showReminderPanel = false;
        store.save(s);

        UiSettingsStore again = new UiSettingsStore(dir);
        again.load();
        assertFalse(again.get().showHints);
        assertFalse(again.get().showReminderPanel);
    }
}
