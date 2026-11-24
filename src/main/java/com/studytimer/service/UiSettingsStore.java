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
 * 界面偏好，保存在 ui-settings.json：是否显示操作提示文字、是否显示 Routine Reminder 面板。
 * 文件缺失或损坏时使用默认值（都显示）。
 */
public final class UiSettingsStore {

    public static final String FILE_NAME = "ui-settings.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path file;
    private Settings settings = new Settings();

    public UiSettingsStore(Path dataDirectory) {
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
            AppLog.warn("界面设置读取失败，使用默认设置：" + e.getMessage());
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
            AppLog.warn("保存界面设置失败：" + e.getMessage());
        }
    }

    public static final class Settings {
        public boolean showHints = true;
        public boolean showReminderPanel = true;
        /** 长期目标横条是否展开。 */
        public boolean goalStripExpanded = true;
        /** 计划完成提示音：空 = 手机内置提示音，否则为手机音频库 audioId。 */
        public String finishAudioId = "";
        /** 完成提示音最多播放秒数，0 = 播完为止（内置提示音仍限 15 秒）。 */
        public int finishSeconds = 15;
        public int finishVolume = 100;
        /** 已删除的计划分类（默认分类也可删除；被计划使用的仍会显示）。 */
        public java.util.List<String> hiddenPlanCategories = new java.util.ArrayList<>();

        public Settings copy() {
            Settings c = new Settings();
            c.showHints = showHints;
            c.showReminderPanel = showReminderPanel;
            c.goalStripExpanded = goalStripExpanded;
            c.finishAudioId = finishAudioId;
            c.finishSeconds = finishSeconds;
            c.finishVolume = finishVolume;
            c.hiddenPlanCategories = hiddenPlanCategories == null
                    ? new java.util.ArrayList<>() : new java.util.ArrayList<>(hiddenPlanCategories);
            return c;
        }
    }
}
