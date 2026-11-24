package com.studytimer.routine.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.studytimer.routine.model.RoutineReminder;
import com.studytimer.service.AppLog;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 调度器运行状态（上次检查时间、待触发的稍后提醒），与提醒定义分开存放，
 * 不改变 routine-reminders.json 的格式。状态文件损坏时按空状态处理。
 */
public final class RoutineReminderStateStore {

    public record Snooze(RoutineReminder reminder, LocalDateTime dueAt) {
        public Snooze {
            reminder = Objects.requireNonNull(reminder, "reminder").copy();
            Objects.requireNonNull(dueAt, "dueAt");
        }

        @Override
        public RoutineReminder reminder() {
            return reminder.copy();
        }
    }

    public record State(LocalDateTime lastCheckedAt, List<Snooze> snoozes) {
        public State {
            snoozes = snoozes == null ? List.of() : List.copyOf(snoozes);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class FileData {
        public String lastCheckedAt;
        public List<SnoozeData> snoozes = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class SnoozeData {
        public String dueAt;
        public RoutineReminder reminder;
    }

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public RoutineReminderStateStore() {
        this(defaultFile());
    }

    public RoutineReminderStateStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    public Path getFile() {
        return file;
    }

    public State load() {
        if (!Files.exists(file)) {
            return new State(null, List.of());
        }
        try {
            FileData data = mapper.readValue(file.toFile(), FileData.class);
            LocalDateTime last = data.lastCheckedAt == null || data.lastCheckedAt.isBlank()
                    ? null : LocalDateTime.parse(data.lastCheckedAt);
            List<Snooze> snoozes = new ArrayList<>();
            if (data.snoozes != null) {
                for (SnoozeData s : data.snoozes) {
                    try {
                        s.reminder.validate();
                        snoozes.add(new Snooze(s.reminder, LocalDateTime.parse(s.dueAt)));
                    } catch (RuntimeException skip) {
                        AppLog.warn("忽略无效的稍后提醒记录：" + skip.getMessage());
                    }
                }
            }
            return new State(last, snoozes);
        } catch (IOException | RuntimeException e) {
            AppLog.warn("提醒状态文件无法读取，按空状态处理：" + e.getMessage());
            return new State(null, List.of());
        }
    }

    public void save(State state) throws IOException {
        FileData data = new FileData();
        data.lastCheckedAt = state.lastCheckedAt() == null
                ? null : state.lastCheckedAt().toString();
        for (Snooze s : state.snoozes()) {
            SnoozeData d = new SnoozeData();
            d.dueAt = s.dueAt().toString();
            d.reminder = s.reminder();
            data.snoozes.add(d);
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        try {
            mapper.writeValue(temp.toFile(), data);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Path defaultFile() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            appData = System.getProperty("user.home");
        }
        return Path.of(appData, "StudyTimer", "routine-reminder-state.json");
    }
}
