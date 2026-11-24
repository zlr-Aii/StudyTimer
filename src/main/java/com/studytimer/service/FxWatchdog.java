package com.studytimer.service;

import javafx.application.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * JavaFX 线程看门狗。
 *
 * 界面的所有交互（包括托盘双击唤醒主窗口）都排在 JavaFX 线程上，一旦该线程被
 * 某个同步调用卡住，表现就是"点什么都没反应"，而且从外部很难判断原因。
 * 这里每 5 秒投递一次心跳，超过 {@link #STALL_SECONDS} 秒没跑到就视为卡住，
 * 把所有线程的堆栈写到 logs 目录（每个卡死周期最多一次）。
 */
public final class FxWatchdog implements AutoCloseable {

    private static final long CHECK_MS = 5_000;
    private static final long STALL_SECONDS = 10;
    private static final long REPEAT_MS = 60_000;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Thread thread;
    private volatile boolean running = true;
    private volatile long lastBeat = System.currentTimeMillis();

    public void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::loop, "fx-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        long lastReportAt = 0;
        while (running) {
            try {
                Platform.runLater(() -> lastBeat = System.currentTimeMillis());
            } catch (RejectedExecutionException | IllegalStateException e) {
                // JavaFX 已退出：看门狗无事可做。
                return;
            }
            try {
                Thread.sleep(CHECK_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!running) {
                return;
            }
            long stalledMs = System.currentTimeMillis() - lastBeat;
            if (stalledMs < STALL_SECONDS * 1000) {
                continue;
            }
            long now = System.currentTimeMillis();
            if (now - lastReportAt < REPEAT_MS) {
                continue;
            }
            lastReportAt = now;
            report(stalledMs);
        }
    }

    private void report(long stalledMs) {
        AppLog.warn("JavaFX 线程已 " + (stalledMs / 1000) + " 秒没有响应，线程堆栈已写入日志目录");
        try {
            Path directory = AppLog.directory();
            if (directory == null) {
                return;
            }
            Path file = directory.resolve("fx-stall-" + LocalDateTime.now().format(STAMP) + ".txt");
            StringBuilder text = new StringBuilder();
            text.append("JavaFX 线程无响应 ").append(stalledMs / 1000).append(" 秒\n\n");
            for (Map.Entry<Thread, StackTraceElement[]> entry
                    : Thread.getAllStackTraces().entrySet()) {
                Thread item = entry.getKey();
                text.append('"').append(item.getName()).append("\" ")
                        .append(item.getState()).append('\n');
                for (StackTraceElement element : entry.getValue()) {
                    text.append("    at ").append(element).append('\n');
                }
                text.append('\n');
            }
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            AppLog.warn("写入线程堆栈失败：" + e.getMessage());
        }
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }
}
