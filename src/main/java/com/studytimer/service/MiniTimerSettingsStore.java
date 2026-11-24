package com.studytimer.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 迷你悬浮计时窗设置，保存在 mini-timer.json。文件缺失或损坏时使用默认值（启用，位置自动）。
 */
public final class MiniTimerSettingsStore {

    public static final String FILE_NAME = "mini-timer.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path file;
    private Settings settings = new Settings();

    public MiniTimerSettingsStore(Path dataDirectory) {
        this.file = dataDirectory.resolve(FILE_NAME);
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            settings = new Settings();
            return;
        }
        try {
            Settings loaded = mapper.readValue(file.toFile(), Settings.class);
            settings = loaded == null ? new Settings() : loaded;
        } catch (IOException e) {
            AppLog.warn("悬浮计时窗设置读取失败，使用默认设置：" + e.getMessage());
            settings = new Settings();
        }
    }

    public synchronized Settings get() {
        return settings.copy();
    }

    public synchronized void save(Settings value) {
        Settings copy = value.copy();
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            try {
                mapper.writeValue(temp.toFile(), copy);
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            settings = copy;
        } catch (IOException e) {
            AppLog.warn("保存悬浮计时窗设置失败：" + e.getMessage());
        }
    }

    public static final class Settings {
        public boolean enabled = true;
        /** 上次位置；null 表示放在主屏幕右下角。 */
        public Double x;
        public Double y;

        public Settings copy() {
            Settings c = new Settings();
            c.enabled = enabled;
            c.x = x;
            c.y = y;
            return c;
        }
    }
}
