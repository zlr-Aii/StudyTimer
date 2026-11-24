package com.studytimer.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 单实例守卫。
 *
 * 只做三件事：
 * 1. 通过文件锁保证同一时间只有一个 StudyTimer 进程；
 * 2. 启动一个只监听回环地址的唤醒服务；
 * 3. 第二个实例启动时把唤醒命令发给第一个实例，让它把窗口拉到前台。
 *
 * 从 Main 抽离出来，启动流程因此只剩"调用它"。
 */
public final class SingleInstanceGuard {

    private static final String PORT_FILE_NAME = "studytimer.instance.port";
    private static final String PORT_TEMP_NAME = "studytimer.instance.port.tmp";
    private static final String LOCK_FILE_NAME = "studytimer.lock";

    private Path appDirectory;
    private Path portFile;

    private FileChannel lockChannel;
    private FileLock lock;

    private volatile boolean anotherInstanceDetected;

    private volatile ServerSocket wakeServer;
    private volatile boolean wakeServerRunning;
    private Thread wakeThread;

    public boolean anotherInstanceDetected() {
        return anotherInstanceDetected;
    }

    /**
     * 尝试成为唯一实例。
     *
     * @return true 表示可以继续启动；false 表示已有实例或锁不可用。
     */
    public boolean acquire() {
        try {
            appDirectory = resolveAppDirectory();
            Files.createDirectories(appDirectory);
            portFile = appDirectory.resolve(PORT_FILE_NAME);
            anotherInstanceDetected = false;

            Path lockPath = appDirectory.resolve(LOCK_FILE_NAME);
            lockChannel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            );

            lock = lockChannel.tryLock();
            if (lock == null) {
                anotherInstanceDetected = true;
                AppLog.info("StudyTimer 已在运行，尝试唤醒已有实例");
                closeQuietly(lockChannel);
                lockChannel = null;
                return false;
            }

            AppLog.info("StudyTimer 单实例锁获取成功");
            return true;

        } catch (java.nio.channels.OverlappingFileLockException e) {
            anotherInstanceDetected = true;
            AppLog.warn("StudyTimer 已在当前 JVM 中运行");
            release();
            return false;

        } catch (IOException | RuntimeException e) {
            AppLog.error("无法获取单实例锁", e);
            release();
            return false;
        }
    }

    /**
     * 通知已经运行的实例把窗口显示出来。
     */
    public boolean signalExisting() {
        if (!anotherInstanceDetected || portFile == null) {
            return false;
        }

        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                if (Files.exists(portFile)) {
                    String value = Files.readString(portFile, StandardCharsets.UTF_8).trim();
                    int port = Integer.parseInt(value);
                    if (port > 0 && port <= 65535 && sendShowCommand(port)) {
                        AppLog.info("已向正在运行的 StudyTimer 发送唤醒请求");
                        return true;
                    }
                }
            } catch (IOException | NumberFormatException ignored) {
                // 已有实例可能仍在启动或正在退出，稍后重试。
            }

            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        AppLog.warn("未能连接到现有 StudyTimer 实例的唤醒服务");
        return false;
    }

    /**
     * 启动唤醒服务。onWake 会在 JavaFX 线程上执行。
     */
    public void startWakeServer(Runnable onWake) {
        ServerSocket server = null;
        try {
            if (portFile == null || appDirectory == null) {
                throw new IOException("StudyTimer 数据目录尚未初始化");
            }

            // 清除上次异常退出可能遗留的端口信息，避免第二实例误连旧端口。
            Files.deleteIfExists(portFile);
            Files.deleteIfExists(appDirectory.resolve(PORT_TEMP_NAME));

            server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            server.setSoTimeout(1000);

            writePortFile(server.getLocalPort());

            wakeServer = server;
            wakeServerRunning = true;
            wakeThread = new Thread(
                    () -> acceptRequests(onWake),
                    "studytimer-instance-wake"
            );
            wakeThread.setDaemon(true);
            wakeThread.start();
            AppLog.info("单实例唤醒服务已启动，端口：" + server.getLocalPort());

        } catch (IOException | RuntimeException e) {
            wakeServerRunning = false;
            wakeServer = null;
            closeQuietly(server);
            deletePortFile();
            AppLog.warn("单实例唤醒服务启动失败，程序仍可正常运行：" + e.getMessage());
        }
    }

    /** 停止唤醒服务并释放锁。 */
    public void stop() {
        wakeServerRunning = false;
        closeQuietly(wakeServer);
        wakeServer = null;

        if (wakeThread != null) {
            wakeThread.interrupt();
            wakeThread = null;
        }

        deletePortFile();
        release();
    }

    /** 仅释放单实例锁（唤醒服务已停止时使用）。 */
    public void release() {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException e) {
            AppLog.warn("释放单实例锁失败：" + e.getMessage());
        } finally {
            lock = null;
        }

        closeQuietly(lockChannel);
        lockChannel = null;
    }

    private boolean sendShowCommand(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    300
            );
            socket.setSoTimeout(500);
            try (PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                    true
            )) {
                writer.println("SHOW");
                writer.flush();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void acceptRequests(Runnable onWake) {
        while (wakeServerRunning) {
            ServerSocket server = wakeServer;
            if (server == null) {
                break;
            }
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(1000);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                )) {
                    if ("SHOW".equals(reader.readLine())) {
                        javafx.application.Platform.runLater(onWake);
                    }
                }
            } catch (java.net.SocketTimeoutException ignored) {
                // 定期检查关闭标志。
            } catch (IOException e) {
                if (wakeServerRunning) {
                    AppLog.warn("单实例唤醒服务通信异常：" + e.getMessage());
                }
            }
        }
    }

    private void writePortFile(int port) throws IOException {
        Path temporary = appDirectory.resolve(PORT_TEMP_NAME);
        Files.writeString(
                temporary,
                Integer.toString(port),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        try {
            Files.move(temporary, portFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            Files.move(temporary, portFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deletePortFile() {
        if (portFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(portFile);
        } catch (IOException e) {
            AppLog.warn("清理单实例端口文件失败：" + e.getMessage());
        }
    }

    private static Path resolveAppDirectory() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData, "StudyTimer");
        }
        return Paths.get(System.getProperty("user.home"), "StudyTimer");
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 关闭失败不影响退出流程。
        }
    }
}
