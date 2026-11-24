package com.studytimer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PolarBellClientFolderTest {

    @Test
    void acceptsNormalNamesAndEmptyWhenAllowed() {
        assertDoesNotThrow(() -> PolarBellClient.requireValidFolder("英语听力", false));
        assertDoesNotThrow(() -> PolarBellClient.requireValidFolder("", true));
    }

    @Test
    void rejectsInvalidNames() {
        assertThrows(IllegalArgumentException.class, () -> PolarBellClient.requireValidFolder("", false));
        assertThrows(IllegalArgumentException.class, () -> PolarBellClient.requireValidFolder("a:b", false));
        assertThrows(IllegalArgumentException.class, () -> PolarBellClient.requireValidFolder("a\nb", true));
        assertThrows(IllegalArgumentException.class, () -> PolarBellClient.requireValidFolder("未分类", false));
        assertThrows(IllegalArgumentException.class,
                () -> PolarBellClient.requireValidFolder("x".repeat(41), false));
    }

    @Test
    void parsesLibraryChunk() throws Exception {
        var chunk = PolarBellClient.parseLibraryChunk("DATA:10:mp3:"
                + java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        assertEquals(10, chunk.total());
        assertEquals("mp3", chunk.extension());
        assertArrayEquals(new byte[]{1, 2, 3}, chunk.data());
        assertEquals(0, PolarBellClient.parseLibraryChunk("DATA:10:m4a:").data().length);
        assertThrows(java.io.IOException.class, () -> PolarBellClient.parseLibraryChunk("AUDIO_NOT_FOUND"));
    }

    @Test
    void phoneAudioDefaultsToUncategorized() {
        assertEquals("", new PhoneAudio("a", "n", 0, 0).getFolder());
        assertEquals("", new PhoneAudio("a", "n", 0, 0, null).getFolder());
    }
}
