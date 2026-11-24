package com.studytimer.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.studytimer.model.StudySession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 学习记录与每日目标，保存在 study-sessions.json。
 * 文件损坏时另存为 .corrupt-时间戳，并从空记录开始，不影响计时。
 */
public final class StudySessionStore {

    public static final String FILE_NAME = "study-sessions.json";
    static final long MIN_RECORD_SECONDS = 10;

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path file;
    private Data data = new Data();
    private String recoveryNotice;

    public StudySessionStore(Path dataDirectory) {
        this.file = dataDirectory.resolve(FILE_NAME);
    }

    public static Path defaultDataDirectory() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            appData = System.getProperty("user.home");
        }
        return Path.of(appData, "StudyTimer");
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            data = new Data();
            return;
        }
        try {
            Data loaded = mapper.readValue(file.toFile(), Data.class);
            data = loaded == null ? new Data() : loaded;
            if (data.records == null) {
                data.records = new ArrayList<>();
            }
        } catch (IOException e) {
            Path corrupt = file.resolveSibling(FILE_NAME + ".corrupt-"
                    + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            try {
                Files.move(file, corrupt, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 移动失败也继续用空记录
            }
            data = new Data();
            recoveryNotice = "学习记录文件损坏，已另存为 " + corrupt.getFileName() + "，从空记录开始";
        }
    }

    public synchronized String takeRecoveryNotice() {
        String notice = recoveryNotice;
        recoveryNotice = null;
        return notice;
    }

    /** 太短的记录（误触跳过）不保存；返回是否保存。 */
    public synchronized boolean add(StudySession session) throws IOException {
        if (session.elapsedSeconds() < MIN_RECORD_SECONDS) {
            return false;
        }
        data.records.add(session);
        save();
        return true;
    }

    public synchronized List<StudySession> records() {
        return List.copyOf(data.records);
    }

    public synchronized int getDailyGoalMinutes() {
        return Math.max(0, data.dailyGoalMinutes);
    }

    public synchronized void setDailyGoalMinutes(int minutes) throws IOException {
        data.dailyGoalMinutes = Math.max(0, Math.min(24 * 60, minutes));
        save();
    }

    /** 今天第一次达到目标时返回 true（每天只提示一次）。 */
    public synchronized boolean markGoalReachedIfNew(LocalDate today) throws IOException {
        int goal = getDailyGoalMinutes();
        if (goal <= 0 || today.toString().equals(data.goalReachedDate)) {
            return false;
        }
        if (focusSeconds(today) < goal * 60L) {
            return false;
        }
        data.goalReachedDate = today.toString();
        save();
        return true;
    }

    public synchronized long focusSeconds(LocalDate day) {
        String key = day.toString();
        long total = 0;
        for (StudySession record : data.records) {
            if (record.focus() && key.equals(record.date())) {
                total += record.elapsedSeconds();
            }
        }
        return total;
    }

    /** 从 today 往前 days 天（含今天）的每日专注秒数，按日期升序。 */
    public synchronized Map<LocalDate, Long> dailyFocus(LocalDate today, int days) {
        Map<LocalDate, Long> result = new LinkedHashMap<>();
        for (int i = days - 1; i >= 0; i--) {
            result.put(today.minusDays(i), 0L);
        }
        for (StudySession record : data.records) {
            if (!record.focus()) {
                continue;
            }
            LocalDate date = parseDate(record.date());
            if (date != null && result.containsKey(date)) {
                result.merge(date, record.elapsedSeconds(), Long::sum);
            }
        }
        return result;
    }

    /**
     * 连续学习天数：有专注记录（至少 1 分钟）的连续日期数。
     * 今天还没学不算断，从昨天往前数。
     */
    public synchronized int streakDays(LocalDate today) {
        LocalDate day = focusSeconds(today) >= 60 ? today : today.minusDays(1);
        int streak = 0;
        while (focusSeconds(day) >= 60) {
            streak++;
            day = day.minusDays(1);
        }
        return streak;
    }

    public synchronized void exportCsv(Path target) throws IOException {
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("日期,结束时间,计划,阶段,计划分钟,实际分钟,完成,计入专注\r\n");
        for (StudySession r : data.records) {
            csv.append(csvField(r.date())).append(',')
                    .append(csvField(r.endedAt())).append(',')
                    .append(csvField(r.planName())).append(',')
                    .append(csvField(r.stageName())).append(',')
                    .append(minutes(r.plannedSeconds())).append(',')
                    .append(minutes(r.elapsedSeconds())).append(',')
                    .append(r.completed() ? "是" : "否").append(',')
                    .append(r.focus() ? "是" : "否").append("\r\n");
        }
        Files.writeString(target, csv, StandardCharsets.UTF_8);
    }

    public static boolean isFocusStage(String stageName) {
        if (stageName == null) {
            return true;
        }
        String name = stageName.toLowerCase(Locale.ROOT);
        return !(name.contains("休息") || name.contains("rest") || name.contains("break"));
    }

    private static String minutes(long seconds) {
        return String.format(Locale.ROOT, "%.1f", seconds / 60.0);
    }

    private static String csvField(String value) {
        String text = value == null ? "" : value;
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static LocalDate parseDate(String text) {
        try {
            return text == null ? null : LocalDate.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            mapper.writeValue(temp.toFile(), data);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static final class Data {
        public int schemaVersion = 1;
        public int dailyGoalMinutes = 0;
        public String goalReachedDate;
        public List<StudySession> records = new ArrayList<>();
    }
}
