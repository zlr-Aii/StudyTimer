
package com.studytimer;

import java.io.IOException;
import com.studytimer.service.AppLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动检测 Android 设备，并管理 ADB 端口转发。
 */
public class AdbForwardManager {

    private static final int LOCAL_PORT = 8988;
    private static final int REMOTE_PORT = 8988;

    private static final long CHECK_INTERVAL_SECONDS = 3;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "ADB-Forward-Manager");
                thread.setDaemon(true);
                return thread;
            });

    private volatile boolean closed = false;

    // 转发成功后执行的回调
    private volatile Runnable onForwardReady;

    // 防止同一次连接重复触发回调
    private final AtomicBoolean readyNotified =
            new AtomicBoolean(false);

    private String lastForwardedSerial;

    // 只在状态变化时写日志，避免每 3 秒刷屏
    private String lastLogMessage;

    private Path findAdb() {

        List<Path> candidates = new ArrayList<>();
        for (String env : List.of("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            String sdk = System.getenv(env);
            if (sdk != null && !sdk.isBlank()) {
                candidates.add(Path.of(sdk, "platform-tools", "adb.exe"));
            }
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            candidates.add(Path.of(localAppData, "Android", "Sdk", "platform-tools", "adb.exe"));
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) {
                    try {
                        candidates.add(Path.of(dir.trim(), "adb.exe"));
                    } catch (RuntimeException ignored) {
                        // PATH 里的非法路径直接跳过
                    }
                }
            }
        }

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException("找不到 adb.exe（Android SDK platform-tools）");
    }

    public void start(Runnable onForwardReady) {

        this.onForwardReady = onForwardReady;

        scheduler.scheduleWithFixedDelay(
                this::checkDevice,
                0,
                CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private void notifyForwardReady() {

        if (readyNotified.compareAndSet(false, true)) {

            Runnable callback = onForwardReady;

            if (callback != null) {
                callback.run();
            }
        }
    }

    private void checkDevice() {

        if (closed) {
            return;
        }

        try {
            Path adb = findAdb();

            String devicesOutput = runAdb(
                    adb,
                    "devices"
            );

            List<String> devices = parseAuthorizedDevices(
                    devicesOutput
            );

            if (devices.isEmpty()) {

                if (lastForwardedSerial != null) {
                    lastForwardedSerial = null;
                    readyNotified.set(false);
                }

                logChanged("ADB：没有已授权的设备（" + summarizeDevices(devicesOutput) + "）", false);
                return;
            }

            if (devices.size() > 1) {
                logChanged("ADB：检测到多个已授权设备，暂不自动选择：" + devices, true);
                return;
            }

            String serial = devices.get(0);

            String forwardList = runAdb(
                    adb,
                    "forward",
                    "--list"
            );

            String expectedForward =
                    serial + " tcp:" + LOCAL_PORT
                            + " tcp:" + REMOTE_PORT;

            if (forwardList.lines().anyMatch(
                    line -> line.trim().equals(expectedForward)
            )) {
                lastForwardedSerial = serial;
                logChanged("ADB：端口转发已存在，设备：" + serial, false);
                notifyForwardReady();
                return;
            }

            runAdb(
                    adb,
                    "-s",
                    serial,
                    "forward",
                    "tcp:" + LOCAL_PORT,
                    "tcp:" + REMOTE_PORT
            );

            lastForwardedSerial = serial;

            logChanged("ADB：端口转发已建立，设备：" + serial, false);

            notifyForwardReady();

        } catch (Exception e) {
            logChanged("ADB 自动管理失败：" + e.getMessage(), true);
        }
    }

    private static String summarizeDevices(String output) {
        List<String> states = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("List of devices") || trimmed.startsWith("*")) {
                continue;
            }
            states.add(trimmed.replaceAll("\\s+", " "));
        }
        return states.isEmpty() ? "未检测到手机" : String.join("; ", states);
    }

    private List<String> parseAuthorizedDevices(String output) {

        List<String> result = new ArrayList<>();

        for (String line : output.split("\\R")) {

            String trimmed = line.trim();

            if (trimmed.isEmpty()
                    || trimmed.startsWith("List of devices")) {
                continue;
            }

            String[] parts = trimmed.split("\\s+");

            if (parts.length >= 2
                    && "device".equals(parts[1])) {
                result.add(parts[0]);
            }
        }

        return result;
    }

    private String runAdb(
            Path adb,
            String... arguments
    ) throws IOException, InterruptedException {

        List<String> command = new ArrayList<>();
        command.add(adb.toString());
        command.addAll(List.of(arguments));

        // 输出写到临时文件而不是管道：adb 首次运行会拉起后台服务，
        // 服务进程继承管道句柄后，读管道会永远等不到结束（开机后无法连接的原因）。
        Path outputFile = Files.createTempFile("studytimer-adb", ".txt");

        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start();

            boolean finished = process.waitFor(
                    20,
                    TimeUnit.SECONDS
            );

            if (!finished) {
                process.destroyForcibly();
                throw new IOException("ADB 命令执行超时");
            }

            String output = Files.readString(outputFile, StandardCharsets.UTF_8);

            if (process.exitValue() != 0) {
                throw new IOException(
                        "ADB 命令失败：" + output.trim()
                );
            }

            return output;
        } finally {
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException ignored) {
                outputFile.toFile().deleteOnExit();
            }
        }
    }

    private void logChanged(String message, boolean warn) {
        if (message.equals(lastLogMessage)) {
            return;
        }
        lastLogMessage = message;
        if (warn) {
            AppLog.warn(message);
        } else {
            AppLog.info(message);
        }
    }

    public void close() {

        closed = true;
        scheduler.shutdownNow();

        System.out.println("ADB 自动管理已停止");
    }
}
