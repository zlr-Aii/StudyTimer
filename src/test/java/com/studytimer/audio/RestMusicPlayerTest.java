package com.studytimer.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 生命保障休息音乐的文件夹展开。 */
class RestMusicPlayerTest {

    @Test
    void blankPathHasNoQueue() {
        assertTrue(RestMusicPlayer.pcQueue(null).isEmpty());
        assertTrue(RestMusicPlayer.pcQueue("   ").isEmpty());
    }

    @Test
    void folderPlaysAudioFilesInNameOrder(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("b.mp3"));
        Files.createFile(dir.resolve("a.MP3"));
        Files.createFile(dir.resolve("c.flac"));
        Files.createFile(dir.resolve("notes.txt"));
        Files.createDirectories(dir.resolve("sub"));
        Files.createFile(dir.resolve("sub").resolve("d.mp3"));

        List<Path> queue = RestMusicPlayer.pcQueue(dir.toString());

        assertEquals(List.of("a.MP3", "b.mp3", "c.flac"),
                queue.stream().map(p -> p.getFileName().toString()).toList());
    }

    @Test
    void singleFileIsPlayedAsIs(@TempDir Path dir) throws IOException {
        Path file = Files.createFile(dir.resolve("song.m4a"));

        assertEquals(List.of(file), RestMusicPlayer.pcQueue(file.toString()));
    }

    @Test
    void missingFileGivesEmptyQueue(@TempDir Path dir) {
        assertTrue(RestMusicPlayer.pcQueue(dir.resolve("nope.mp3").toString()).isEmpty());
    }
}
