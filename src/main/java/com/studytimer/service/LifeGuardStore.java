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
 * 生命保障：设置保存在 life-guard.json，计时状态保存在 life-guard-state.json。
 * 文件缺失或损坏时使用默认值（开启，工作 50 分钟 / 休息 10 分钟）。
 */
public final class LifeGuardStore {

    public static final String SETTINGS_FILE = "life-guard.json";
    public static final String STATE_FILE = "life-guard-state.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path settingsFile;
    private final Path stateFile;
    private Settings settings = new Settings();

    public LifeGuardStore(Path dataDirectory) {
        this.settingsFile = dataDirectory.resolve(SETTINGS_FILE);
        this.stateFile = dataDirectory.resolve(STATE_FILE);
    }

    public synchronized void load() {
        settings = read(settingsFile, Settings.class, new Settings());
        settings.normalize();
    }

    public synchronized Settings get() {
        return settings.copy();
    }

    public synchronized void save(Settings value) throws IOException {
        Settings copy = value.copy();
        copy.normalize();
        write(settingsFile, copy);
        settings = copy;
    }

    public synchronized LifeGuardEngine.State loadState() {
        return read(stateFile, LifeGuardEngine.State.class, null);
    }

    public synchronized void saveState(LifeGuardEngine.State state) {
        try {
            write(stateFile, state);
        } catch (IOException e) {
            AppLog.warn("保存生命保障状态失败：" + e.getMessage());
        }
    }

    private <T> T read(Path file, Class<T> type, T fallback) {
        if (!Files.isRegularFile(file)) {
            return fallback;
        }
        try {
            T value = mapper.readValue(file.toFile(), type);
            return value == null ? fallback : value;
        } catch (IOException e) {
            AppLog.warn("读取 " + file.getFileName() + " 失败，使用默认值：" + e.getMessage());
            return fallback;
        }
    }

    private void write(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
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

    public static final class Question {
        public String question = "";
        /** 预设答案；空表示输入任意文字即可。 */
        public String answer = "";

        public Question() {
        }

        public Question(String question, String answer) {
            this.question = question;
            this.answer = answer;
        }
    }

    /** 全屏休息窗口的默认提示文字。 */
    public static final String DEFAULT_REST_HINT =
            "离开屏幕，起来走走、喝水、看看远处。\n休息结束后窗口会自动关闭。";

    public static final class Settings {
        public boolean enabled = true;
        public int workMinutes = 50;
        public int restMinutes = 10;
        /** 全屏休息窗口中间的提示文字（可自定义，留空则用默认）。 */
        public String restHint = DEFAULT_REST_HINT;
        /** 休息时播放的电脑音乐：单曲或整个文件夹（空 = 不播放）。 */
        public String restPcMusic = "";
        /** 休息时播放的手机音频：audioId 或 folder:RANDOM:文件夹（空 = 不播放）。 */
        public String restPhoneMusic = "";
        public int restMusicVolume = 100;
        /** 休息结束、回到工作时播放的电脑音乐（空 = 不播放）。 */
        public String workPcMusic = "";
        public String workPhoneMusic = "";
        public int workMusicVolume = 100;
        /** 休息结束音乐的播放秒数；0 = 直到下一次切换或停止。 */
        public int workMusicSeconds = 20;
        /** 三个问题；第一个同时用于简化障碍。 */
        public java.util.List<Question> questions = defaultQuestions();
        /** 可选密码（PBKDF2 哈希与盐，Base64）；为空表示未设置。 */
        public String passwordHash;
        public String passwordSalt;

        public static java.util.List<Question> defaultQuestions() {
            return new java.util.ArrayList<>(java.util.List.of(
                    new Question("你当时开启生命保障是为了什么？", "为了活得长一点，健康一点"),
                    new Question("你现在为什么想这样做？", ""),
                    new Question("你确定吗？", "我确定")));
        }

        void normalize() {
            workMinutes = LifeGuardEngine.clampWork(workMinutes);
            restMinutes = LifeGuardEngine.clampRest(restMinutes);
            if (restHint == null || restHint.isBlank()) {
                restHint = DEFAULT_REST_HINT;
            } else {
                restHint = restHint.replace("\r\n", "\n").strip();
            }
            restPcMusic = blankToEmpty(restPcMusic);
            restPhoneMusic = blankToEmpty(restPhoneMusic);
            workPcMusic = blankToEmpty(workPcMusic);
            workPhoneMusic = blankToEmpty(workPhoneMusic);
            restMusicVolume = clampVolume(restMusicVolume);
            workMusicVolume = clampVolume(workMusicVolume);
            workMusicSeconds = Math.max(0, Math.min(600, workMusicSeconds));
            java.util.List<Question> fixed = new java.util.ArrayList<>();
            java.util.List<Question> defaults = defaultQuestions();
            for (int i = 0; i < 3; i++) {
                Question q = questions != null && i < questions.size() ? questions.get(i) : null;
                if (q == null || q.question == null || q.question.isBlank()) {
                    q = defaults.get(i);
                }
                fixed.add(new Question(q.question.trim(), q.answer == null ? "" : q.answer.trim()));
            }
            questions = fixed;
            if (passwordHash != null && passwordHash.isBlank()) {
                passwordHash = null;
                passwordSalt = null;
            }
        }

        public boolean hasPassword() {
            return passwordHash != null && passwordSalt != null;
        }

        private static String blankToEmpty(String value) {
            return value == null || value.isBlank() ? "" : value.trim();
        }

        private static int clampVolume(int volume) {
            return Math.max(0, Math.min(100, volume));
        }

        public Settings copy() {
            Settings c = new Settings();
            c.enabled = enabled;
            c.workMinutes = workMinutes;
            c.restMinutes = restMinutes;
            c.restHint = restHint;
            c.restPcMusic = restPcMusic;
            c.restPhoneMusic = restPhoneMusic;
            c.restMusicVolume = restMusicVolume;
            c.workPcMusic = workPcMusic;
            c.workPhoneMusic = workPhoneMusic;
            c.workMusicVolume = workMusicVolume;
            c.workMusicSeconds = workMusicSeconds;
            c.questions = new java.util.ArrayList<>();
            if (questions != null) {
                for (Question q : questions) {
                    c.questions.add(q == null ? null : new Question(q.question, q.answer));
                }
            }
            c.passwordHash = passwordHash;
            c.passwordSalt = passwordSalt;
            return c;
        }
    }
}
