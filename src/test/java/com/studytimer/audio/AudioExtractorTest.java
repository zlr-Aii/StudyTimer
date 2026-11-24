package com.studytimer.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AudioExtractorTest {

    @TempDir
    Path dir;

    @Test
    void unavailableWithoutFfmpegReturnsEmpty() throws Exception {
        Path video = Files.writeString(dir.resolve("a.mp4"), "x");
        AudioExtractor extractor = new AudioExtractor(null, dir.resolve("cache"));
        assertFalse(extractor.isAvailable());
        assertTrue(extractor.extractAudio(video).isEmpty());
    }

    @Test
    void brokenFfmpegFallsBackToEmpty() throws Exception {
        Path video = Files.writeString(dir.resolve("a.mp4"), "x");
        Path fake = Files.writeString(dir.resolve("not-ffmpeg.exe"), "x");
        AudioExtractor extractor = new AudioExtractor(fake, dir.resolve("cache"));
        assertTrue(extractor.extractAudio(video).isEmpty());
    }

    @Test
    void copyCommandKeepsAudioOnly() {
        List<String> command = AudioExtractor.buildCommand(
                Path.of("ffmpeg"), Path.of("in.mp4"), Path.of("out.m4a"), true);
        assertTrue(command.contains("-vn"));
        assertEquals("copy", command.get(command.indexOf("-c:a") + 1));
        assertEquals("out.m4a", command.get(command.size() - 1));
    }

    @Test
    void transcodeCommandUsesAac() {
        List<String> command = AudioExtractor.buildCommand(
                Path.of("ffmpeg"), Path.of("in.mp4"), Path.of("out.m4a"), false);
        assertEquals("aac", command.get(command.indexOf("-c:a") + 1));
    }

    @Test
    void cacheKeyChangesWhenFileChanges() throws Exception {
        Path video = Files.writeString(dir.resolve("a.mp4"), "x");
        String first = AudioExtractor.cacheKey(video);
        assertEquals(first, AudioExtractor.cacheKey(video));

        Files.setLastModifiedTime(video, FileTime.fromMillis(1_000_000L));
        assertNotEquals(first, AudioExtractor.cacheKey(video));
    }
}
