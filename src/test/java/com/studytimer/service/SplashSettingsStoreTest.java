package com.studytimer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class SplashSettingsStoreTest {

    @TempDir
    Path dir;

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);

    private SplashSettingsStore storeWith(String timing) throws Exception {
        SplashSettingsStore store = new SplashSettingsStore(dir);
        store.load();
        SplashSettingsStore.Settings s = store.get();
        s.enabled = true;
        s.timing = timing;
        store.save(s);
        return store;
    }

    @Test
    void disabledByDefault() {
        SplashSettingsStore store = new SplashSettingsStore(dir);
        store.load();
        assertFalse(store.shouldShow(true, TODAY));
        assertEquals("Shall we dance?", store.get().message);
    }

    @Test
    void alwaysShowsOnManualLaunch() throws Exception {
        SplashSettingsStore store = storeWith(SplashSettingsStore.Settings.TIMING_ALWAYS);
        store.markShown(false, TODAY);
        assertTrue(store.shouldShow(false, TODAY));
    }

    @Test
    void autostartOnlyOncePerDay() throws Exception {
        SplashSettingsStore store = storeWith(SplashSettingsStore.Settings.TIMING_AUTOSTART);
        assertFalse(store.shouldShow(false, TODAY));
        assertTrue(store.shouldShow(true, TODAY));
        store.markShown(true, TODAY);
        assertFalse(store.shouldShow(true, TODAY));
        assertTrue(store.shouldShow(true, TODAY.plusDays(1)));

        // 保存设置不会清掉今天已显示的记录
        store.save(store.get());
        SplashSettingsStore reloaded = new SplashSettingsStore(dir);
        reloaded.load();
        assertFalse(reloaded.shouldShow(true, TODAY));
    }

    @Test
    void responseSettingsHaveDefaultsAndPersist() throws Exception {
        SplashSettingsStore store = new SplashSettingsStore(dir);
        store.load();
        SplashSettingsStore.Settings s = store.get();
        assertTrue(s.noMessage.startsWith("You said someday."));
        assertEquals(15, s.noDurationSeconds);

        s.noMessage = "自定义";
        s.noMediaType = SplashSettingsStore.Settings.MEDIA_VIDEO;
        s.noMediaPath = "a.mp4";
        s.noDurationSeconds = 0;
        store.save(s);

        SplashSettingsStore reloaded = new SplashSettingsStore(dir);
        reloaded.load();
        assertEquals("自定义", reloaded.get().noMessage);
        assertEquals(0, reloaded.get().noDurationSeconds);
        assertEquals("a.mp4", reloaded.get().noMediaPath);
    }

    @Test
    void oldFileWithoutResponseFieldsUsesDefaults() throws Exception {
        Files.writeString(dir.resolve(SplashSettingsStore.FILE_NAME),
                "{\"enabled\":true,\"message\":\"hi\"}");
        SplashSettingsStore store = new SplashSettingsStore(dir);
        store.load();
        assertEquals("hi", store.get().message);
        assertEquals(15, store.get().noDurationSeconds);
        assertEquals(SplashSettingsStore.Settings.MEDIA_NONE, store.get().noMediaType);
    }

    @Test
    void corruptFileFallsBackToDefaults() throws Exception {
        Files.writeString(dir.resolve(SplashSettingsStore.FILE_NAME), "{bad");
        SplashSettingsStore store = new SplashSettingsStore(dir);
        store.load();
        assertFalse(store.get().enabled);
    }
}
