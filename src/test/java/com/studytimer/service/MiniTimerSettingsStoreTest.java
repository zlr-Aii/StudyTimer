package com.studytimer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MiniTimerSettingsStoreTest {

    @TempDir
    Path dir;

    @Test
    void defaultsEnabledWithoutPosition() {
        MiniTimerSettingsStore store = new MiniTimerSettingsStore(dir);
        store.load();
        assertTrue(store.get().enabled);
        assertNull(store.get().x);
    }

    @Test
    void savesAndReloads() {
        MiniTimerSettingsStore store = new MiniTimerSettingsStore(dir);
        store.load();
        MiniTimerSettingsStore.Settings s = store.get();
        s.enabled = false;
        s.x = 100.0;
        s.y = 200.0;
        store.save(s);

        MiniTimerSettingsStore reloaded = new MiniTimerSettingsStore(dir);
        reloaded.load();
        assertFalse(reloaded.get().enabled);
        assertEquals(100.0, reloaded.get().x);
        assertEquals(200.0, reloaded.get().y);
    }

    @Test
    void corruptFileUsesDefaults() throws Exception {
        Files.writeString(dir.resolve(MiniTimerSettingsStore.FILE_NAME), "{bad");
        MiniTimerSettingsStore store = new MiniTimerSettingsStore(dir);
        store.load();
        assertTrue(store.get().enabled);
    }
}
