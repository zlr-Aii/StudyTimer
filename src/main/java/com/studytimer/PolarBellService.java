
package com.studytimer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * PolarBell 手机音频服务
 *
 * 负责：
 * 1. 管理 PolarBellClient 连接
 * 2. 异步执行手机通信操作
 * 3. 播放和停止手机音频
 * 4. 查询手机媒体库
 * 5. 缓存手机音频名称
 */
public class PolarBellService {

    private final PolarBellClient client;
    // 防止服务被重复关闭
    private final AtomicBoolean closeRequested = new AtomicBoolean(false);
    // 统一协调任务提交与服务关闭，避免并发竞争
    private final Object lifecycleLock = new Object();

    // 单线程执行器，保证手机通信操作按提交顺序执行
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "PolarBell-Worker");
                thread.setDaemon(true);
                return thread;
            });

    // 定时触发连接检测
    private final ScheduledExecutorService healthScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "PolarBell-Health");
                thread.setDaemon(true);
                return thread;
            });

    // 防止上一次检测尚未执行完，又重复提交检测任务
    private final AtomicBoolean healthCheckQueued = new AtomicBoolean(false);

    // 手机音频 ID -> 名称
    private final Map<String, String> audioNames =
            new ConcurrentHashMap<>();

    // 当前连接状态
    private volatile PolarBellConnectionState connectionState =
            PolarBellConnectionState.DISCONNECTED;

    // 手机端支持的命令；旧版 App 没有 CAPABILITIES 时为空集合
    private volatile Set<String> capabilities = Set.of();
    private volatile boolean capabilitiesLoaded = false;

    // 状态监听器
    private final List<Consumer<PolarBellConnectionState>> stateListeners =
            new CopyOnWriteArrayList<>();

    public PolarBellService() {
        this.client = new PolarBellClient();

        // 每隔 5 秒触发一次连接检测
        healthScheduler.scheduleAtFixedRate(
                this::scheduleHealthCheck,
                5,
                5,
                TimeUnit.SECONDS
        );
    }

    /**
     * 安全提交异步任务。
     *
     * 关闭操作与任务提交使用同一把锁：
     * 关闭开始后，不再接受新任务。
     */
    private void submitTask(
            Runnable task,
            Consumer<Exception> onError) {

        boolean rejected = false;

        synchronized (lifecycleLock) {
            if (closeRequested.get()) {
                rejected = true;
            } else {
                executor.execute(task);
            }
        }

        if (rejected && onError != null) {
            onError.accept(
                    new IllegalStateException("PolarBell 服务已经关闭")
            );
        }
    }

    private void ensureConnected() throws IOException {

        try {
            // 当前连接仍然有效
            if (client.ping()) {
                if (!capabilitiesLoaded) {
                    loadCapabilitiesQuietly();
                }
                setConnectionState(PolarBellConnectionState.CONNECTED);
                return;
            }

        } catch (IOException ignored) {
            // 当前连接可能已经失效，尝试重新连接
        }

        // 开始连接或重新连接
        setConnectionState(PolarBellConnectionState.CONNECTING);

        try {
            client.connect();

            if (!client.ping()) {
                throw new IOException("PolarBell 连接失败");
            }

            // 每次建立新的连接后都刷新媒体库。
            // 这样首次自动重试成功时，也能获得完整的音频名称缓存。
            loadCapabilitiesQuietly();
            refreshAudioNamesQuietly();

            setConnectionState(PolarBellConnectionState.CONNECTED);

        } catch (IOException e) {
            clearCapabilities();
            markConnectionFailure(e);
            throw e;
        }
    }

    /**
     * 把连接失败归类成对用户有意义的状态。
     *
     * Connection refused 意味着手机端 App 没在监听端口（没启动，
     * 或 adb 转发未建立），这是常见状态而不是程序缺陷；
     * 只有其它错误才算真正的连接失败。
     */
    private void markConnectionFailure(IOException error) {
        PolarBellConnectionState next = isPhoneUnavailable(error)
                ? PolarBellConnectionState.UNAVAILABLE
                : PolarBellConnectionState.FAILED;

        boolean changed = getConnectionState() != next;
        setConnectionState(next);

        // 心跳每 5 秒执行一次，只在状态真正变化时记日志，避免刷屏。
        if (!changed) {
            return;
        }
        if (next == PolarBellConnectionState.UNAVAILABLE) {
            com.studytimer.service.AppLog.info(
                    "PolarBell 手机端未运行，等待其启动后自动连接");
        } else {
            com.studytimer.service.AppLog.warn(
                    "PolarBell 连接失败：" + error.getMessage());
        }
    }

    private static boolean isPhoneUnavailable(Throwable error) {
        Throwable current = error;
        int guard = 0;
        while (current != null && guard++ < 8) {
            if (current instanceof java.net.ConnectException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("Connection refused")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 连接后查询一次手机能力。
     *
     * 查询本身失败时保留“未加载”状态，下次心跳会重试；
     * 旧版 App 返回 UNKNOWN_COMMAND 时得到空集合，属于正常降级。
     */
    private void loadCapabilitiesQuietly() {

        try {
            Set<String> loaded = Set.copyOf(client.getCapabilities());
            capabilities = loaded;
            capabilitiesLoaded = true;

            System.out.println(
                    "PolarBell 手机能力："
                            + (loaded.isEmpty() ? "旧版协议" : String.join(",", loaded))
            );

        } catch (Exception e) {
            capabilities = Set.of();
            capabilitiesLoaded = false;
            System.err.println(
                    "PolarBell 能力查询失败：" + e.getMessage()
            );
        }
    }

    private void clearCapabilities() {
        capabilities = Set.of();
        capabilitiesLoaded = false;
    }

    /** 手机端是否支持上传音频/视频文件并播放。 */
    public boolean isPlayUploadSupported() {
        return capabilities.contains("PLAY_UPLOAD")
                || capabilities.contains("PLAY_UPLOAD_HASH");
    }

    /** 手机端是否支持分块/断点续传（0.92 起）。 */
    public boolean isChunkedUploadSupported() {
        return capabilities.contains("UPLOAD_CHUNKED");
    }

    /** 当前手机端可接受的单个上传文件大小上限（字节）。 */
    public long getUploadLimitBytes() {
        return isChunkedUploadSupported()
                ? PolarBellClient.MAX_CHUNKED_UPLOAD_BYTES
                : PolarBellClient.MAX_UPLOAD_BYTES;
    }

    /** 上传中断时重新连接并从手机已收到的位置继续，最多重试 2 次。 */
    private void uploadChunkedWithResume(Path file) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (attempt > 0) {
                    com.studytimer.service.AppLog.warn(
                            "PolarBell 上传中断，正在续传（第 " + attempt + " 次）："
                                    + last.getMessage());
                    ensureConnected();
                }
                client.playUploadChunked(file, null);
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    public Set<String> getCapabilities() {
        return capabilities;
    }

    /** 手机端是否支持电脑端管理音频库（1.0 起）。 */
    public boolean isLibraryManageSupported() {
        return capabilities.contains("LIBRARY_MANAGE");
    }

    /** 手机端是否支持音量控制（1.0 起）。 */
    public boolean isVolumeSupported() {
        return capabilities.contains("VOLUME");
    }

    /** 播放前设置音量；旧版 App 不支持时忽略。 */
    private void applyVolume(int volume) throws IOException {
        if (isVolumeSupported()) {
            client.setVolume(volume);
        }
    }

    private void requireLibraryManage() {
        if (!isLibraryManageSupported()) {
            throw new UnsupportedOperationException(
                    "手机端 PolarBell 版本过旧，不支持在电脑端管理音频库，请更新手机 App");
        }
    }

    /** 手机端能否查询是否仍在播放（1.6 起，用于文件夹接播下一首）。 */
    public boolean isPlayStateSupported() {
        return capabilities.contains("PLAY_STATE");
    }

    /** 手机端能否把音频库文件传回电脑（1.6 起，用于电脑试听）。 */
    public boolean isLibraryReadSupported() {
        return capabilities.contains("LIB_READ");
    }

    /** 查询手机是否仍在播放；回调在后台线程。 */
    public void getPlayStateAsync(Consumer<Boolean> onResult, Consumer<Exception> onError) {
        submitTask(() -> {
            try {
                ensureConnected();
                onResult.accept(client.isPlaying());
            } catch (Exception e) {
                if (onError != null) {
                    onError.accept(e);
                }
            }
        }, onError);
    }

    /** 每次读取 256KB；每段单独排队，不长时间占用通信线程。 */
    private static final int DOWNLOAD_CHUNK_BYTES = 256 * 1024;

    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Path>>
            downloadsInFlight = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 把手机音频库里的音频下载到 cacheDir（文件名 audioId.扩展名）；
     * 已有同样大小的缓存时直接返回。回调在后台线程。
     */
    public void downloadLibraryAudioAsync(
            PhoneAudio audio,
            Path cacheDir,
            java.util.function.BiConsumer<Long, Long> progress,
            Consumer<Path> onSuccess,
            Consumer<Exception> onError) {
        try {
            if (!isLibraryReadSupported()) {
                throw new UnsupportedOperationException(
                        "手机端 PolarBell 版本过旧，不支持电脑试听，请更新手机 App");
            }
            java.nio.file.Files.createDirectories(cacheDir);
            try (var files = java.nio.file.Files.list(cacheDir)) {
                Path cached = files
                        .filter(p -> p.getFileName().toString().startsWith(audio.getAudioId() + "."))
                        .filter(p -> !p.getFileName().toString().endsWith(".part"))
                        .filter(p -> p.toFile().length() == audio.getFileSize() && audio.getFileSize() > 0)
                        .findFirst().orElse(null);
                if (cached != null) {
                    onSuccess.accept(cached);
                    return;
                }
            }
            // 同一首正在下载（例如预取中）时不重复下载，等它完成
            java.util.concurrent.CompletableFuture<Path> created = new java.util.concurrent.CompletableFuture<>();
            java.util.concurrent.CompletableFuture<Path> running =
                    downloadsInFlight.putIfAbsent(audio.getAudioId(), created);
            java.util.concurrent.CompletableFuture<Path> future = running != null ? running : created;
            future.whenComplete((path, error) -> {
                if (error == null) {
                    onSuccess.accept(path);
                } else if (onError != null) {
                    onError.accept(error instanceof Exception ex ? ex : new IOException(error));
                }
            });
            if (running != null) {
                return;
            }
            created.whenComplete((path, error) -> downloadsInFlight.remove(audio.getAudioId(), created));
            Path part = cacheDir.resolve(audio.getAudioId() + ".part");
            java.nio.file.Files.deleteIfExists(part);
            downloadChunk(audio, cacheDir, part, 0, progress, created::complete, created::completeExceptionally);
        } catch (Exception e) {
            if (onError != null) {
                onError.accept(e);
            }
        }
    }

    private void downloadChunk(
            PhoneAudio audio, Path cacheDir, Path part, long offset,
            java.util.function.BiConsumer<Long, Long> progress,
            Consumer<Path> onSuccess, Consumer<Exception> onError) {
        submitTask(() -> {
            try {
                ensureConnected();
                PolarBellClient.LibraryChunk chunk =
                        client.readLibraryChunk(audio.getAudioId(), offset, DOWNLOAD_CHUNK_BYTES);
                java.nio.file.Files.write(part, chunk.data(),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                long next = offset + chunk.data().length;
                if (progress != null) {
                    progress.accept(next, chunk.total());
                }
                if (next >= chunk.total() || chunk.data().length == 0) {
                    String ext = chunk.extension().isEmpty() ? "bin" : chunk.extension();
                    Path target = cacheDir.resolve(audio.getAudioId() + "." + ext);
                    java.nio.file.Files.move(part, target,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    onSuccess.accept(target);
                } else {
                    downloadChunk(audio, cacheDir, part, next, progress, onSuccess, onError);
                }
            } catch (Exception e) {
                try {
                    java.nio.file.Files.deleteIfExists(part);
                } catch (IOException ignored) {
                }
                if (onError != null) {
                    onError.accept(e);
                }
            }
        }, onError);
    }

    /** 手机端是否支持音频库文件夹（1.4 起）。 */
    public boolean isLibraryFoldersSupported() {
        return capabilities.contains("LIB_FOLDERS");
    }

    /** 最近一次读取音频列表时的文件夹（不含未分类）。 */
    public List<String> getFolders() {
        return client.getLastFolders();
    }

    private void requireFolders() {
        if (!isLibraryFoldersSupported()) {
            throw new UnsupportedOperationException(
                    "手机端 PolarBell 版本过旧，不支持文件夹，请更新手机 App");
        }
    }

    /** 文件夹操作（新建/改名/删除/移动）共用：在通信线程执行，失败回调错误。 */
    public interface FolderOperation {
        void run(PolarBellClient client) throws IOException;
    }

    public void folderOperationAsync(
            FolderOperation operation, Runnable onSuccess, Consumer<Exception> onError) {
        submitTask(() -> {
            try {
                ensureConnected();
                requireFolders();
                operation.run(client);
                if (onSuccess != null) {
                    onSuccess.run();
                }
            } catch (Exception e) {
                if (onError != null) {
                    onError.accept(e);
                }
            }
        }, onError);
    }

    /** 上传本地音频到手机音频库，成功后刷新名称缓存。onSuccess 参数为新 audioId。 */
    public void uploadToLibraryAsync(
            Path file,
            java.util.function.BiConsumer<Long, Long> progress,
            Consumer<String> onSuccess,
            Consumer<Exception> onError) {
        uploadToLibraryAsync(file, null, progress, onSuccess, onError);
    }

    /** folder 非 null 且手机支持文件夹时上传到该文件夹（"" = 未分类）。 */
    public void uploadToLibraryAsync(
            Path file,
            String folder,
            java.util.function.BiConsumer<Long, Long> progress,
            Consumer<String> onSuccess,
            Consumer<Exception> onError) {

        submitTask(() -> {
            try {
                ensureConnected();
                requireLibraryManage();
                String audioId = null;
                IOException last = null;
                for (int attempt = 0; attempt < 3 && audioId == null; attempt++) {
                    try {
                        if (attempt > 0) {
                            ensureConnected();
                        }
                        audioId = client.uploadToLibrary(file,
                                isLibraryFoldersSupported() ? folder : null, progress);
                    } catch (IOException e) {
                        last = e;
                    }
                }
                if (audioId == null) {
                    throw last;
                }
                refreshAudioNamesQuietly();
                if (onSuccess != null) {
                    onSuccess.accept(audioId);
                }
            } catch (Exception e) {
                if (onError != null) {
                    onError.accept(e);
                }
            }
        }, onError);
    }

    public void deleteLibraryAudioAsync(
            String audioId, Runnable onSuccess, Consumer<Exception> onError) {
        submitTask(() -> {
            try {
                ensureConnected();
                requireLibraryManage();
                client.deleteLibraryAudio(audioId);
                refreshAudioNamesQuietly();
                if (onSuccess != null) {
                    onSuccess.run();
                }
            } catch (Exception e) {
                if (onError != null) {
                    onError.accept(e);
                }
            }
        }, onError);
    }

    public void renameLibraryAudioAsync(
            String audioId, String name, Runnable onSuccess, Consumer<Exception> onError) {
        submitTask(() -> {
            try {
                ensureConnected();
                requireLibraryManage();
                client.renameLibraryAudio(audioId, name);
                refreshAudioNamesQuietly();
                if (onSuccess != null) {
                    onSuccess.run();
                }
            } catch (Exception e) {
                if (onError != null) {
                    onError.accept(e);
                }
            }
        }, onError);
    }

    /**
     * 向通信队列提交一次连接检测或自动重连尝试。
     * 首次连接尚未成功时也必须持续尝试，不能只在曾经连接成功后检测。
     */

    private void scheduleHealthCheck() {

        if (!healthCheckQueued.compareAndSet(false, true)) {
            return;
        }

        synchronized (lifecycleLock) {

            if (closeRequested.get()) {
                healthCheckQueued.set(false);
                return;
            }

            try {
                executor.execute(() -> {
                    try {
                        // 无论此前是否连接成功，都持续检测并尝试建立连接。
                        // 这覆盖首次连接失败、手机稍后启动以及断线后的恢复。
                        if (closeRequested.get()) {
                            return;
                        }

                        ensureConnected();

                    } catch (IOException e) {
                        // ensureConnected() 已经把状态归好类；这里不覆盖，
                        // 否则"App 未运行"会被重新标成"连接失败"。
                        markConnectionFailure(e);

                    } finally {
                        healthCheckQueued.set(false);
                    }
                });

            } catch (java.util.concurrent.RejectedExecutionException e) {
                healthCheckQueued.set(false);
            }
        }
    }


    /**
     * 尝试刷新手机媒体库名称缓存。
     *
     * 刷新失败时保留旧缓存，不影响已经恢复的连接。
     */
    private void refreshAudioNamesQuietly() {

        try {
            List<PhoneAudio> audios = client.getAudioList();

            audioNames.clear();

            for (PhoneAudio audio : audios) {
                audioNames.put(
                        audio.getAudioId(),
                        audio.getName()
                );
            }

            System.out.println(
                    "PolarBell 连接后媒体库缓存已刷新，共 "
                            + audios.size() + " 条"
            );

        } catch (Exception e) {
            System.err.println(
                    "PolarBell 重连后刷新媒体库失败："
                            + e.getMessage()
            );
        }
    }

    /**
     * 异步连接手机。
     */
    public void connectAsync(
            Consumer<Boolean> onSuccess,
            Consumer<Exception> onError) {

        submitTask(() -> {
            try {
                ensureConnected();

                if (onSuccess != null) {
                    onSuccess.accept(true);
                }

            } catch (Exception e) {
                if (onError != null) {
                    onError.accept(e);
                }
            }
        }, onError);
    }

    /**
     * 异步播放手机音频。
     *
     * builtin:1 ~ builtin:3 表示内置提示音。
     * audio_xxx 表示手机媒体库中的音频。
     */
    public void playAudioAsync(
            String audioId,
            Consumer<Exception> onError) {
        playAudioAsync(audioId, 100, onError);
    }

    /** volume：0-100，手机端不支持音量时忽略。 */
    public void playAudioAsync(
            String audioId,
            int volume,
            Consumer<Exception> onError) {

        submitTask(() -> {
            try {
                ensureConnected();

                if (audioId == null || audioId.isBlank()) {
                    throw new IllegalArgumentException("音频 ID 不能为空");
                }

                applyVolume(volume);

                if (audioId.startsWith("builtin:")) {

                    int type = Integer.parseInt(
                            audioId.substring("builtin:".length())
                    );

                    client.play(type);

                } else {
                    client.playAudio(audioId);
                }

            } catch (Exception e) {
                if (onError != null) {
                    onError.accept(e);
                }
            }
        }, onError);
    }

    /**
     * 异步上传本地文件并在手机上播放。
     *
     * 用于视频提醒：电脑静音显示画面，手机播放视频原声。
     * onStarted 在手机确认开始播放后执行（后台线程）。
     */
    public void playUploadAsync(
            Path file,
            Runnable onStarted,
            Consumer<Exception> onError) {
        playUploadAsync(file, 100, onStarted, onError);
    }

    public void playUploadAsync(
            Path file,
            int volume,
            Runnable onStarted,
            Consumer<Exception> onError) {

        submitTask(() -> {
            try {
                ensureConnected();

                if (!isPlayUploadSupported()) {
                    throw new UnsupportedOperationException(
                            "手机端 PolarBell 不支持文件上传播放，"
                                    + "请更新并重新安装手机 App"
                    );
                }

                applyVolume(volume);

                if (isChunkedUploadSupported()) {
                    uploadChunkedWithResume(file);
                } else {
                    boolean useHash = capabilities.contains("PLAY_UPLOAD_HASH");
                    client.playUpload(file, useHash);
                }

                if (onStarted != null) {
                    onStarted.run();
                }

            } catch (Exception e) {
                if (onError != null) {
                    onError.accept(e);
                }
            }
        }, onError);
    }

    /**
     * 异步播放默认完成提示音。
     */
    public void playFinishSoundAsync(
            Consumer<Exception> onError) {

        submitTask(() -> {
            try {
                ensureConnected();
                applyVolume(100);
                client.play(1);

            } catch (Exception e) {
                if (onError != null) {
                    onError.accept(e);
                }
            }
        }, onError);
    }

    /**
     * 异步停止手机播放。
     */
    public void stopAsync(
            Consumer<Exception> onError) {

        submitTask(() -> {
            try {
                ensureConnected();
                client.stop();

            } catch (Exception e) {
                if (onError != null) {
                    onError.accept(e);
                }
            }
        }, onError);
    }

    /**
     * 异步获取手机媒体库。
     *
     * 回调在后台线程执行。
     * 如果需要更新 JavaFX 界面，
     * 调用方必须使用 Platform.runLater()。
     */
    public void getAudioListAsync(
            Consumer<List<PhoneAudio>> onSuccess,
            Consumer<Exception> onError) {

        submitTask(() -> {
            try {
                ensureConnected();

                List<PhoneAudio> audios = client.getAudioList();

                // 更新音频名称缓存
                audioNames.clear();

                for (PhoneAudio audio : audios) {
                    audioNames.put(
                            audio.getAudioId(),
                            audio.getName()
                    );
                }

                if (onSuccess != null) {
                    onSuccess.accept(audios);
                }

            } catch (Exception e) {
                if (onError != null) {
                    onError.accept(e);
                }
            }
        }, onError);
    }

    /**
     * 获取当前连接状态
     */
    public PolarBellConnectionState getConnectionState() {
        return connectionState;
    }

    /**
     * 注册连接状态监听器
     */
    public void addStateListener(
            Consumer<PolarBellConnectionState> listener) {

        if (listener != null) {
            stateListeners.add(listener);
        }
    }

    /**
     * 更新连接状态，并通知所有监听器
     */
    private void setConnectionState(PolarBellConnectionState newState) {

        if (newState == null || connectionState == newState) {
            return;
        }

        connectionState = newState;

        for (Consumer<PolarBellConnectionState> listener : stateListeners) {
            try {
                listener.accept(newState);
            } catch (Exception e) {
                System.err.println("PolarBell 状态监听器执行失败：" + e.getMessage());
            }
        }
    }

    /**
     * 获取手机音频名称。
     */
    public String getAudioName(String audioId) {

        if (audioId == null || audioId.isBlank()) {
            return "无";
        }

        if (audioId.startsWith("builtin:")) {
            return "内置提示音 "
                    + audioId.substring("builtin:".length());
        }

        var folder = com.studytimer.audio.PhoneFolderPlaylist.parse(audioId);
        if (folder.isPresent()) {
            return folder.get().label();
        }

        return audioNames.getOrDefault(
                audioId,
                "音频不可用"
        );
    }

    /**
     * 获取当前缓存的手机音频名称。
     */
    public Map<String, String> getAudioNames() {
        return Map.copyOf(audioNames);
    }

    /**
     * 关闭服务。
     *
     * 关闭操作与任务提交使用同一把锁。
     * 关闭前已提交的任务会先执行，
     * 客户端关闭任务随后执行。
     */
    public void closeAsync() {

        synchronized (lifecycleLock) {

            if (!closeRequested.compareAndSet(false, true)) {
                return;
            }

            // 停止后续定时检测
            healthScheduler.shutdownNow();

            executor.execute(() -> {
                try {
                    client.close();

                } catch (IOException e) {
                    System.err.println(
                            "关闭 PolarBell 连接失败：" + e.getMessage()
                    );
                } finally {
                    setConnectionState(PolarBellConnectionState.CLOSED);
                }
            });

            executor.shutdown();
        }
    }
}
