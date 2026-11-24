package com.studytimer.routine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.studytimer.routine.model.RoutineReminder;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Persistent storage for routine reminder definitions. */
public final class RoutineReminderStorageService {

    private final ObjectMapper objectMapper;
    private final Path storageFile;
    private volatile String recoveryNotice;

    public RoutineReminderStorageService() {
        this(defaultStorageFile());
    }

    public RoutineReminderStorageService(Path storageFile) {
        if (storageFile == null) {
            throw new IllegalArgumentException("提醒存储文件路径不能为空");
        }
        this.storageFile = storageFile.toAbsolutePath().normalize();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 读取提醒。主文件损坏时尝试从上一次保存的 .bak 恢复：
     * 损坏文件另存为 .corrupt-时间戳 保留，恢复结果通过 {@link #takeRecoveryNotice()} 提示。
     */
    public List<RoutineReminder> loadAll() throws IOException {
        recoveryNotice = null;
        if (!Files.exists(storageFile)) {
            Path backup = backupFile();
            if (!Files.exists(backup)) {
                return List.of();
            }
            List<RoutineReminder> restored = readFile(backup);
            Files.copy(backup, storageFile, StandardCopyOption.REPLACE_EXISTING);
            recoveryNotice = "提醒文件缺失，已从备份恢复 " + restored.size() + " 条提醒";
            return restored;
        }
        try {
            return readFile(storageFile);
        } catch (IOException mainError) {
            Path backup = backupFile();
            if (!Files.exists(backup)) {
                throw mainError;
            }
            List<RoutineReminder> restored;
            try {
                restored = readFile(backup);
            } catch (IOException backupError) {
                mainError.addSuppressed(backupError);
                throw mainError;
            }
            Path corrupt = storageFile.resolveSibling(storageFile.getFileName()
                    + ".corrupt-" + System.currentTimeMillis());
            Files.copy(storageFile, corrupt, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(backup, storageFile, StandardCopyOption.REPLACE_EXISTING);
            recoveryNotice = "提醒文件损坏，已从备份恢复 " + restored.size()
                    + " 条提醒；原文件保留为 " + corrupt.getFileName();
            return restored;
        }
    }

    /** 返回并清除最近一次读取时发生的恢复提示；没有则为 null。 */
    public String takeRecoveryNotice() {
        String notice = recoveryNotice;
        recoveryNotice = null;
        return notice;
    }

    public Path backupFile() {
        return storageFile.resolveSibling(storageFile.getFileName() + ".bak");
    }

    private List<RoutineReminder> readFile(Path file) throws IOException {
        RoutineReminder[] reminders = objectMapper.readValue(
                file.toFile(), RoutineReminder[].class);
        if (reminders == null) {
            throw new IOException("提醒文件内容为空：" + file);
        }
        List<RoutineReminder> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (RoutineReminder reminder : reminders) {
            if (reminder == null) {
                throw new IOException("提醒文件包含空记录：" + storageFile);
            }
            try {
                reminder.validate();
            } catch (IllegalArgumentException e) {
                throw new IOException("提醒数据无效：" + e.getMessage(), e);
            }
            if (!ids.add(reminder.getId())) {
                throw new IOException("提醒文件包含重复 ID：" + reminder.getId());
            }
            result.add(reminder.copy());
        }
        return List.copyOf(result);
    }

    public void saveAll(List<RoutineReminder> reminders) throws IOException {
        if (reminders == null) {
            throw new IllegalArgumentException("提醒列表不能为空");
        }
        List<RoutineReminder> snapshot = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (RoutineReminder reminder : reminders) {
            if (reminder == null) {
                throw new IllegalArgumentException("提醒列表不能包含空记录");
            }
            reminder.validate();
            if (!ids.add(reminder.getId())) {
                throw new IllegalArgumentException("提醒 ID 重复：" + reminder.getId());
            }
            snapshot.add(reminder.copy());
        }

        Path parent = storageFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tempFile = Files.createTempFile(parent,
                storageFile.getFileName().toString(), ".tmp");
        try {
            objectMapper.writeValue(tempFile.toFile(), snapshot);
            backupCurrentIfReadable();
            try {
                Files.move(tempFile, storageFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, storageFile,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /** 只备份能正常读取的旧文件，避免用损坏内容覆盖好的备份。 */
    private void backupCurrentIfReadable() {
        if (!Files.exists(storageFile)) {
            return;
        }
        try {
            readFile(storageFile);
            Files.copy(storageFile, backupFile(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 旧文件不可读：保留原有备份
        }
    }

    public Path getStorageFile() {
        return storageFile;
    }

    private static Path defaultStorageFile() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            appData = System.getProperty("user.home");
        }
        if (appData == null || appData.isBlank()) {
            throw new IllegalStateException("无法确定提醒数据保存目录");
        }
        return Path.of(appData, "StudyTimer", "routine-reminders.json");
    }
}
