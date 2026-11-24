package com.studytimer.audio;

import com.studytimer.PhoneAudio;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PhoneFolderPlaylistTest {

    private static final List<PhoneAudio> AUDIOS = List.of(
            new PhoneAudio("a1", "A1", 0, 0, "英语"),
            new PhoneAudio("u1", "U1", 0, 0, ""),
            new PhoneAudio("a2", "A2", 0, 0, "英语"),
            new PhoneAudio("a3", "A3", 0, 0, "英语"));

    @Test
    void parsesAndFormatsIds() {
        var selection = new PhoneFolderPlaylist.Selection(PhoneFolderPlaylist.Mode.ORDER, "英语");
        assertEquals("folder:ORDER:英语", selection.toId());
        assertEquals(selection, PhoneFolderPlaylist.parse(selection.toId()).orElseThrow());
        assertEquals("", PhoneFolderPlaylist.parse("folder:RANDOM:").orElseThrow().folder());
        assertTrue(PhoneFolderPlaylist.parse("folder:BAD:x").isEmpty());
        assertTrue(PhoneFolderPlaylist.parse("abc").isEmpty());
        assertFalse(PhoneFolderPlaylist.isFolderId("builtin:1"));
    }

    @Test
    void orderCyclesThroughFolder() {
        var playlist = new PhoneFolderPlaylist();
        var selection = new PhoneFolderPlaylist.Selection(PhoneFolderPlaylist.Mode.ORDER, "英语");
        assertEquals("a1", playlist.next(selection, AUDIOS).orElseThrow().getAudioId());
        assertEquals("a2", playlist.next(selection, AUDIOS).orElseThrow().getAudioId());
        assertEquals("a3", playlist.next(selection, AUDIOS).orElseThrow().getAudioId());
        assertEquals("a1", playlist.next(selection, AUDIOS).orElseThrow().getAudioId());
    }

    @Test
    void randomStaysInFolderAndAvoidsRepeat() {
        var playlist = new PhoneFolderPlaylist(new Random(42));
        var selection = new PhoneFolderPlaylist.Selection(PhoneFolderPlaylist.Mode.RANDOM, "英语");
        String last = null;
        for (int i = 0; i < 50; i++) {
            PhoneAudio audio = playlist.next(selection, AUDIOS).orElseThrow();
            assertEquals("英语", audio.getFolder());
            assertNotEquals(last, audio.getAudioId());
            last = audio.getAudioId();
        }
    }

    @Test
    void stepGoesBackAndForthInOrder() {
        var order = PhoneFolderPlaylist.Mode.ORDER;
        assertEquals(0, PhoneFolderPlaylist.step(-1, 3, order, null, 1));
        assertEquals(2, PhoneFolderPlaylist.step(-1, 3, order, null, -1));
        assertEquals(2, PhoneFolderPlaylist.step(0, 3, order, null, -1));
        assertEquals(0, PhoneFolderPlaylist.step(2, 3, order, null, 1));
        assertEquals(-1, PhoneFolderPlaylist.step(0, 0, order, null, 1));
    }

    @Test
    void emptyFolderGivesNothing() {
        var playlist = new PhoneFolderPlaylist();
        var selection = new PhoneFolderPlaylist.Selection(PhoneFolderPlaylist.Mode.RANDOM, "没有");
        assertTrue(playlist.next(selection, AUDIOS).isEmpty());
        var uncategorized = new PhoneFolderPlaylist.Selection(PhoneFolderPlaylist.Mode.RANDOM, "");
        assertEquals("u1", playlist.next(uncategorized, AUDIOS).orElseThrow().getAudioId());
    }
}
