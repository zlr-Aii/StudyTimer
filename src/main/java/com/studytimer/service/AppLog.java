package com.studytimer.service;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 轻量文件日志。
 *
 * 目标是在用户机器上留下可追溯的证据：出问题时不用只看控制台。
 * 日志写入 %APPDATA%\StudyTimer\logs\studytimer.log，按大小滚动。
 *
 * 该类的任何失败都必须静默：日志系统不能反过来让程序崩溃。
 */
public final class AppLog {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final int MAX_BACKUPS = 3;

    private static final Object LOCK = new Object();

    private static Path logDirectory;
    private static Path logFile;
    private static boolean disabled;

    private AppLog() {
    }

    /** 日志目录；无法定位时返回 null。 */
    public static Path directory() {
        synchronized (LOCK) {
            if (logDirectory == null && !disabled) {
                logDirectory = resolveDirectory();
            }
            return logDirectory;
        }
    }

    /** 当前日志文件；无法定位时返回 null。 */
    public static Path file() {
        synchronized (LOCK) {
            if (logFile == null) {
                Path directory = directory();
                if (directory == null) {
                    return null;
                }
                logFile = directory.resolve("studytimer.log");
            }
            return logFile;
        }
    }

    public static void info(String message) {
        write("INFO ", message, null);
    }

    public static void warn(String message) {
        write("WARN ", message, null);
    }

    public static void error(String message, Throwable error) {
        write("ERROR", message, error);
    }

    /**
     * 供界面展示的错误摘要：包含日志文件位置，方便用户反馈问题。
     */
    public static String describeForUser() {
        Path file = file();
        return file == null ? "" : file.toString();
    }

    private static Path resolveDirectory() {
        try {
            String appData = System.getenv("APPDATA");
            if (appData == null || appData.isBlank()) {
                appData = System.getProperty("user.home");
            }
            if (appData == null || appData.isBlank()) {
                disabled = true;
                return null;
            }
            Path directory = Path.of(appData, "StudyTimer", "logs");
            Files.createDirectories(directory);
            return directory;
        } catch (IOException | RuntimeException e) {
            disabled = true;
            System.err.println("无法初始化日志目录：" + e.getMessage());
            return null;
        }
    }

    private static void write(String level, String message, Throwable error) {
        String text = message == null ? "" : message;

        // 控制台保留原有行为，开发时仍能直接看到。
        if (error == null) {
            System.err.println(level + " " + text);
        } else {
            System.err.println(level + " " + text + "：" + describe(error));
        }

        synchronized (LOCK) {
            Path target = file();
            if (target == null) {
                return;
            }
            try {
                rotateIfNeeded(target);

                StringBuilder line = new StringBuilder(256)
                        .append(LocalDateTime.now().format(TIMESTAMP))
                        .append(' ')
                        .append(level)
                        .append(' ')
                        .append(text)
                        .append(System.lineSeparator());

                if (error != null) {
                    StringWriter buffer = new StringWriter();
                    error.printStackTrace(new PrintWriter(buffer));
                    line.append(indent(buffer.toString()));
                }

                Files.writeString(
                        target,
                        line.toString(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE
                );
            } catch (IOException | RuntimeException e) {
                // 日志失败不再抛出，避免级联故障。
                System.err.println("写入日志失败：" + e.getMessage());
            }
        }
    }

    private static void rotateIfNeeded(Path target) throws IOException {
        if (!Files.isRegularFile(target) || Files.size(target) < MAX_BYTES) {
            return;
        }

        for (int index = MAX_BACKUPS - 1; index >= 1; index--) {
            Path older = target.resolveSibling("studytimer." + index + ".log");
            Path newer = target.resolveSibling("studytimer." + (index + 1) + ".log");
            if (Files.isRegularFile(older)) {
                Files.move(older, newer,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(target, target.resolveSibling("studytimer.1.log"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String indent(String text) {
        return "    " + text.replace(System.lineSeparator(), System.lineSeparator() + "    ");
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }
}
