package com.studytimer.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;

/**
 * 开屏设置，保存在 splash-settings.json。文件缺失或损坏时使用默认值（关闭）。
 */
public final class SplashSettingsStore {

    public static final String FILE_NAME = "splash-settings.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path file;
    private Settings settings = new Settings();

    public SplashSettingsStore(Path dataDirectory) {
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
            AppLog.warn("开屏设置读取失败，使用默认设置：" + e.getMessage());
            settings = new Settings();
        }
    }

    public synchronized Settings get() {
        return settings.copy();
    }

    public synchronized void save(Settings value) throws IOException {
        Settings copy = value.copy();
        copy.lastAutostartShownDate = settings.lastAutostartShownDate;
        write(copy);
        settings = copy;
    }

    /**
     * 本次启动是否显示开屏。
     * 时机为 AUTOSTART 时只在开机启动时显示；开机启动每天只显示一次。
     */
    public synchronized boolean shouldShow(boolean autostart, LocalDate today) {
        Settings s = settings;
        if (!s.enabled) {
            return false;
        }
        if (Settings.TIMING_AUTOSTART.equals(s.timing) && !autostart) {
            return false;
        }
        return !autostart || !today.toString().equals(s.lastAutostartShownDate);
    }

    public synchronized void markShown(boolean autostart, LocalDate today) {
        if (!autostart) {
            return;
        }
        Settings copy = settings.copy();
        copy.lastAutostartShownDate = today.toString();
        try {
            write(copy);
            settings = copy;
        } catch (IOException e) {
            AppLog.warn("保存开屏显示日期失败：" + e.getMessage());
        }
    }

    private void write(Settings value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            mapper.writeValue(temp.toFile(), value);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static final class Settings {
        public static final String MEDIA_NONE = "NONE";
        public static final String MEDIA_IMAGE = "IMAGE";
        public static final String MEDIA_VIDEO = "VIDEO";
        public static final String MEDIA_AUDIO = "AUDIO";
        public static final String TIMING_ALWAYS = "ALWAYS";
        public static final String TIMING_AUTOSTART = "AUTOSTART";
        public static final String OUTPUT_LOCAL = "LOCAL";
        public static final String OUTPUT_PHONE = "PHONE";

        public boolean enabled = false;
        public String mediaType = MEDIA_NONE;
        public String mediaPath = "";
        public String timing = TIMING_ALWAYS;
        public String message = "Shall we dance?";
        public String yesText = "Yes";
        public String noText = "Not now";
        public String output = OUTPUT_LOCAL;
        public int volume = 100;
        public String lastAutostartShownDate;

        /** 点右按钮后显示的回应：文字 + 媒体，显示完自动进入主界面。 */
        public String noMessage = DEFAULT_NO_MESSAGE;
        public String noMediaType = MEDIA_NONE;
        public String noMediaPath = "";
        /** 回应最多显示多少秒；0 = 视频/音乐播完为止（图片和纯文字按 15 秒）。 */
        public int noDurationSeconds = 15;

        public static final String DEFAULT_NO_MESSAGE = "You said someday. Not today or tomorrow or next week. "
                + "Just someday. 'Someday' sounds a lot like the thing people say when they actually mean 'never'.";

        public boolean hasPlayback() {
            return (MEDIA_VIDEO.equals(mediaType) || MEDIA_AUDIO.equals(mediaType))
                    && mediaPath != null && !mediaPath.isBlank();
        }

        public Settings copy() {
            Settings c = new Settings();
            c.enabled = enabled;
            c.mediaType = mediaType;
            c.mediaPath = mediaPath;
            c.timing = timing;
            c.message = message;
            c.yesText = yesText;
            c.noText = noText;
            c.output = output;
            c.volume = Math.max(0, Math.min(100, volume));
            c.lastAutostartShownDate = lastAutostartShownDate;
            c.noMessage = noMessage;
            c.noMediaType = noMediaType == null ? MEDIA_NONE : noMediaType;
            c.noMediaPath = noMediaPath == null ? "" : noMediaPath;
            c.noDurationSeconds = Math.max(0, Math.min(600, noDurationSeconds));
            return c;
        }
    }
}
