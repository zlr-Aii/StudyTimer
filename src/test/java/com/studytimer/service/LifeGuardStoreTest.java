package com.studytimer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 生命保障设置的读写：休息画面文字与阶段音乐。 */
class LifeGuardStoreTest {

    @Test
    void defaultsHaveHintAndNoMusic(@TempDir Path dir) {
        LifeGuardStore store = new LifeGuardStore(dir);
        store.load();

        LifeGuardStore.Settings s = store.get();
        assertEquals(LifeGuardStore.DEFAULT_REST_HINT, s.restHint);
        assertEquals("", s.restPcMusic);
        assertEquals("", s.restPhoneMusic);
        assertEquals("", s.workPcMusic);
        assertEquals("", s.workPhoneMusic);
        assertEquals(20, s.workMusicSeconds);
    }

    @Test
    void savesAndReloadsMusicAndHint(@TempDir Path dir) throws IOException {
        LifeGuardStore store = new LifeGuardStore(dir);
        store.load();

        LifeGuardStore.Settings s = store.get();
        s.restHint = "起来走动一下\n看看窗外";
        s.restPcMusic = "D:/music";
        s.restPhoneMusic = "folder:RANDOM:休息";
        s.restMusicVolume = 60;
        s.workPcMusic = "D:/a.mp3";
        s.workPhoneMusic = "abc123";
        s.workMusicVolume = 40;
        s.workMusicSeconds = 30;
        store.save(s);

        LifeGuardStore reloaded = new LifeGuardStore(dir);
        reloaded.load();
        LifeGuardStore.Settings r = reloaded.get();

        assertEquals("起来走动一下\n看看窗外", r.restHint);
        assertEquals("D:/music", r.restPcMusic);
        assertEquals("folder:RANDOM:休息", r.restPhoneMusic);
        assertEquals(60, r.restMusicVolume);
        assertEquals("D:/a.mp3", r.workPcMusic);
        assertEquals("abc123", r.workPhoneMusic);
        assertEquals(40, r.workMusicVolume);
        assertEquals(30, r.workMusicSeconds);
    }

    @Test
    void normalizesBadValues(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(LifeGuardStore.SETTINGS_FILE), """
                {
                  "enabled": true,
                  "workMinutes": 50,
                  "restMinutes": 10,
                  "restHint": "   ",
                  "restPcMusic": "  ",
                  "restMusicVolume": 250,
                  "workMusicVolume": -5,
                  "workMusicSeconds": 9999
                }
                """);

        LifeGuardStore store = new LifeGuardStore(dir);
        store.load();
        LifeGuardStore.Settings s = store.get();

        assertEquals(LifeGuardStore.DEFAULT_REST_HINT, s.restHint);
        assertEquals("", s.restPcMusic);
        assertEquals(100, s.restMusicVolume);
        assertEquals(0, s.workMusicVolume);
        assertEquals(600, s.workMusicSeconds);
    }

    @Test
    void copyKeepsMusicFields(@TempDir Path dir) {
        LifeGuardStore store = new LifeGuardStore(dir);
        store.load();

        LifeGuardStore.Settings s = store.get();
        s.restPhoneMusic = "folder:ORDER:轻音乐";
        LifeGuardStore.Settings copy = s.copy();

        assertEquals("folder:ORDER:轻音乐", copy.restPhoneMusic);
        assertFalse(store.get().restPhoneMusic.equals("folder:ORDER:轻音乐"));
    }

    @Test
    void enabledByDefault(@TempDir Path dir) {
        LifeGuardStore store = new LifeGuardStore(dir);
        store.load();
        assertTrue(store.get().enabled);
    }
}
