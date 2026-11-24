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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 长期目标，保存在 long-term-goals.json。文件损坏时另存为 .corrupt-时间戳 并从空列表开始。
 */
public final class LongTermGoalStore {

    public static final String FILE_NAME = "long-term-goals.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path file;
    private Data data = new Data();
    private String recoveryNotice;

    public LongTermGoalStore(Path dataDirectory) {
        this.file = dataDirectory.resolve(FILE_NAME);
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            data = new Data();
            return;
        }
        try {
            Data loaded = mapper.readValue(file.toFile(), Data.class);
            data = loaded == null ? new Data() : loaded;
            if (data.goals == null) {
                data.goals = new ArrayList<>();
            }
            data.goals.removeIf(g -> g == null);
            data.goals.forEach(Goal::normalize);
        } catch (IOException e) {
            Path corrupt = file.resolveSibling(FILE_NAME + ".corrupt-"
                    + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            try {
                Files.move(file, corrupt, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 移动失败也继续用空列表
            }
            data = new Data();
            recoveryNotice = "长期目标文件损坏，已另存为 " + corrupt.getFileName() + "，从空列表开始";
        }
    }

    public synchronized String takeRecoveryNotice() {
        String notice = recoveryNotice;
        recoveryNotice = null;
        return notice;
    }

    public synchronized List<Goal> goals() {
        List<Goal> copy = new ArrayList<>();
        for (Goal g : data.goals) {
            copy.add(g.copy());
        }
        return copy;
    }

    /** 新增或按 id 替换。 */
    public synchronized void put(Goal goal) throws IOException {
        Goal copy = goal.copy();
        if (copy.id == null || copy.id.isBlank()) {
            copy.id = UUID.randomUUID().toString();
        }
        copy.normalize();
        List<Goal> next = new ArrayList<>(data.goals);
        boolean replaced = false;
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id.equals(copy.id)) {
                next.set(i, copy);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            next.add(copy);
        }
        write(next);
    }

    public synchronized void delete(String id) throws IOException {
        List<Goal> next = new ArrayList<>(data.goals);
        next.removeIf(g -> g.id.equals(id));
        write(next);
    }

    private void write(List<Goal> goals) throws IOException {
        Data next = new Data();
        next.goals = goals;
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            mapper.writeValue(temp.toFile(), next);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        data = next;
    }

    public static final class Data {
        public int schemaVersion = 1;
        public List<Goal> goals = new ArrayList<>();
    }

    public static final class Goal {
        public String id;
        public String name = "";
        /** 动机：为什么要做这件事。 */
        public String motivation = "";
        public int totalHours = 100;
        public String planId;
        /** 每周哪几天，1=周一 … 7=周日。 */
        public List<Integer> weekdays = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7));
        public int timesPerDay = 1;
        /** yyyy-MM-dd */
        public String startDate = LocalDate.now().toString();
        /** 达到总小时数的日期；未完成为 null。 */
        public String completedDate;

        void normalize() {
            name = name == null ? "" : name.trim();
            motivation = motivation == null ? "" : motivation.trim();
            totalHours = Math.max(1, Math.min(100_000, totalHours));
            timesPerDay = Math.max(1, Math.min(20, timesPerDay));
            List<Integer> days = new ArrayList<>();
            if (weekdays != null) {
                for (Integer d : weekdays) {
                    if (d != null && d >= 1 && d <= 7 && !days.contains(d)) {
                        days.add(d);
                    }
                }
            }
            days.sort(Integer::compare);
            weekdays = days.isEmpty() ? new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7)) : days;
            if (startLocalDate() == null) {
                startDate = LocalDate.now().toString();
            }
        }

        public LocalDate startLocalDate() {
            try {
                return startDate == null ? null : LocalDate.parse(startDate);
            } catch (RuntimeException e) {
                return null;
            }
        }

        public boolean isCompleted() {
            return completedDate != null && !completedDate.isBlank();
        }

        public Goal copy() {
            Goal c = new Goal();
            c.id = id;
            c.name = name;
            c.motivation = motivation;
            c.totalHours = totalHours;
            c.planId = planId;
            c.weekdays = weekdays == null ? null : new ArrayList<>(weekdays);
            c.timesPerDay = timesPerDay;
            c.startDate = startDate;
            c.completedDate = completedDate;
            return c;
        }
    }
}
