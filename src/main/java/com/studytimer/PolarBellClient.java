package com.studytimer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class PolarBellClient implements AutoCloseable {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8988;

    private static final int CONNECT_TIMEOUT = 3000;
    private static final int READ_TIMEOUT = 3000;

    /**
     * 手机端单次上传上限。与 PolarBell SocketServer 的
     * MAX_UPLOAD_BYTES 保持一致。
     */
    public static final long MAX_UPLOAD_BYTES = 32L * 1024L * 1024L;

    /** 分块上传（UPLOAD_CHUNKED）总大小上限，与手机端一致。 */
    public static final long MAX_CHUNKED_UPLOAD_BYTES = 512L * 1024L * 1024L;

    /** 每块 1MB：中断时最多重传 1MB。手机端单块上限 4MB。 */
    private static final int CHUNK_BYTES = 1024 * 1024;

    /** 上传期间需要等待手机接收并准备播放，使用更长的读取超时。 */
    private static final int UPLOAD_READ_TIMEOUT = 60_000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    /**
     * 连接 Android 手机
     */
    public synchronized void connect() throws IOException {

        close();

        Socket newSocket = new Socket();

        try {
            newSocket.connect(
                    new InetSocketAddress(HOST, PORT),
                    CONNECT_TIMEOUT
            );

            newSocket.setSoTimeout(READ_TIMEOUT);

            BufferedReader newReader = new BufferedReader(
                    new InputStreamReader(
                            newSocket.getInputStream(),
                            StandardCharsets.UTF_8
                    )
            );

            BufferedWriter newWriter = new BufferedWriter(
                    new OutputStreamWriter(
                            newSocket.getOutputStream(),
                            StandardCharsets.UTF_8
                    )
            );

            socket = newSocket;
            reader = newReader;
            writer = newWriter;

            System.out.println("PolarBell 已连接");

        } catch (IOException e) {
            newSocket.close();
            throw e;
        }
    }

    /**
     * 检查手机通信是否正常
     */
    public synchronized boolean ping() throws IOException {
        return "PONG".equals(sendCommand("PING"));
    }

    /**
     * 播放指定提示音
     */
    public synchronized void play(int type) throws IOException {

        if (type < 1 || type > 3) {
            throw new IllegalArgumentException(
                    "提示音类型必须是 1、2 或 3"
            );
        }

        String response = sendCommand("PLAY:" + type);

        if (!"OK".equals(response)) {
            throw new IOException(
                    "手机播放失败，回复：" + response
            );
        }
    }

    /**
     * 停止手机播放
     */
    public synchronized void stop() throws IOException {

        String response = sendCommand("STOP");

        if (!"OK".equals(response)) {
            throw new IOException(
                    "手机停止播放失败，回复：" + response
            );
        }
    }

    /** 最近一次 LIST 返回的文件夹列表（不含未分类；旧版 App 为空）。 */
    private volatile List<String> lastFolders = List.of();

    public List<String> getLastFolders() {
        return lastFolders;
    }

    /**
     * 获取手机音频列表
     */
    public synchronized List<PhoneAudio> getAudioList()
            throws IOException {

        String response = sendCommand("LIST");

        try {
            JsonNode root = objectMapper.readTree(response);

            String status = root.path("status").asText();

            if (!"OK".equals(status)) {
                throw new IOException(
                        "获取手机音频列表失败：" + response
                );
            }

            JsonNode audiosNode = root.path("audios");

            if (!audiosNode.isArray()) {
                throw new IOException(
                        "手机返回的音频列表格式不正确"
                );
            }

            List<PhoneAudio> audioList = new ArrayList<>();

            for (JsonNode audioNode : audiosNode) {

                String audioId = audioNode.path("audioId").asText();
                String name = audioNode.path("name").asText();
                long duration = audioNode.path("duration").asLong();
                long fileSize = audioNode.path("fileSize").asLong();
                String folder = audioNode.path("folder").asText("");

                if (audioId.isEmpty()) {
                    continue;
                }

                audioList.add(
                        new PhoneAudio(
                                audioId,
                                name,
                                duration,
                                fileSize,
                                folder
                        )
                );
            }

            List<String> folders = new ArrayList<>();
            for (JsonNode folderNode : root.path("folders")) {
                String folder = folderNode.asText("").trim();
                if (!folder.isEmpty() && !folders.contains(folder)) {
                    folders.add(folder);
                }
            }
            lastFolders = List.copyOf(folders);

            return audioList;

        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(
                    "解析手机音频列表失败",
                    e
            );
        }
    }

    /**
     * 播放手机媒体库中的指定音频
     */
    public synchronized void playAudio(String audioId)
            throws IOException {

        if (audioId == null || audioId.isBlank()
                || audioId.contains("\n")
                || audioId.contains("\r")) {
            throw new IllegalArgumentException(
                    "音频 ID 不合法"
            );
        }

        String response = sendCommand("PLAY_AUDIO:" + audioId);

        if (!"OK".equals(response)) {
            throw new IOException(
                    "手机音频播放失败，回复：" + response
            );
        }
    }

    /**
     * 查询手机端支持的命令。
     *
     * 旧版 PolarBell 没有 CAPABILITIES 命令，会回复 UNKNOWN_COMMAND，
     * 此时返回空集合，调用方据此回退到旧功能。
     */
    public synchronized Set<String> getCapabilities() throws IOException {

        String response = sendCommand("CAPABILITIES");
        Set<String> result = new LinkedHashSet<>();

        if (response == null || !response.startsWith("CAP:")) {
            return result;
        }

        String payload = response.substring("CAP:".length());

        for (String item : payload.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }

        return result;
    }

    /**
     * 上传本地文件并让手机播放。
     *
     * useHash 为 true 且手机支持时，会使用 SHA-256 缓存协议，
     * 同一文件只传输一次。
     */
    public synchronized void playUpload(Path file, boolean useHash)
            throws IOException {

        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("上传文件不存在：" + file);
        }

        long size = Files.size(file);

        if (size <= 0 || size > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "上传文件大小必须在 1 字节到 "
                            + (MAX_UPLOAD_BYTES / 1024 / 1024) + "MB 之间，当前："
                            + size + " 字节"
            );
        }

        if (socket == null || socket.isClosed() || !socket.isConnected()) {
            throw new IOException("PolarBell 尚未连接");
        }

        String safeName = sanitizeFileName(
                file.getFileName().toString()
        );

        String command;

        if (useHash) {
            command = "PLAY_UPLOAD_HASH:" + size + ":"
                    + sha256(file) + ":" + safeName;
        } else {
            command = "PLAY_UPLOAD:" + size + ":" + safeName;
        }

        int previousTimeout = socket.getSoTimeout();

        try {
            socket.setSoTimeout(UPLOAD_READ_TIMEOUT);

            writer.write(command);
            writer.newLine();
            writer.flush();

            String response = reader.readLine();

            if ("CACHED".equals(response)) {
                return;
            }

            if (!"READY".equals(response)) {
                throw new IOException(
                        "手机拒绝接收上传文件，回复：" + response
                );
            }

            try (InputStream input = Files.newInputStream(file)) {
                OutputStream output = socket.getOutputStream();
                byte[] buffer = new byte[8192];
                int read;

                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                }

                output.flush();
            }

            response = reader.readLine();

            if (!"OK".equals(response)) {
                throw new IOException(
                        "手机播放上传文件失败，回复：" + response
                );
            }

        } catch (IOException e) {
            // 上传中途失败后协议可能已经错位，关闭连接等待下次重连，
            // 避免后续普通命令读到残留数据。
            try {
                close();
            } catch (IOException ignored) {
            }
            throw e;

        } finally {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.setSoTimeout(previousTimeout);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 分块上传并播放（手机能力 UPLOAD_CHUNKED）。
     *
     * 手机按 SHA-256 保存 .part 文件，连接中断后再次调用会从已收到的位置续传；
     * 已完整上传过的文件直接播放。progress 参数为（已发送字节，总字节），可为 null。
     */
    public synchronized void playUploadChunked(
            Path file,
            java.util.function.BiConsumer<Long, Long> progress)
            throws IOException {

        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("上传文件不存在：" + file);
        }

        long size = Files.size(file);

        if (size <= 0 || size > MAX_CHUNKED_UPLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "上传文件大小必须在 1 字节到 "
                            + (MAX_CHUNKED_UPLOAD_BYTES / 1024 / 1024) + "MB 之间，当前："
                            + size + " 字节"
            );
        }

        if (socket == null || socket.isClosed() || !socket.isConnected()) {
            throw new IOException("PolarBell 尚未连接");
        }

        String hash = sha256(file);
        int previousTimeout = socket.getSoTimeout();

        try {
            socket.setSoTimeout(UPLOAD_READ_TIMEOUT);

            sendChunks(file, size, hash, progress);

            String response = sendLine("UPLOAD_PLAY:" + hash);

            if (!"OK".equals(response)) {
                throw new IOException("手机播放上传文件失败，回复：" + response);
            }

        } catch (IOException e) {
            try {
                close();
            } catch (IOException ignored) {
            }
            throw e;

        } finally {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.setSoTimeout(previousTimeout);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** UPLOAD_BEGIN + UPLOAD_CHUNK：把文件完整传到手机（支持续传），不播放。 */
    private void sendChunks(
            Path file, long size, String hash,
            java.util.function.BiConsumer<Long, Long> progress) throws IOException {

        String safeName = sanitizeFileName(file.getFileName().toString());
        String response = sendLine(
                "UPLOAD_BEGIN:" + size + ":" + hash + ":" + safeName);

        if ("DONE".equals(response)) {
            if (progress != null) {
                progress.accept(size, size);
            }
            return;
        }

        long offset = parseOffset(response, "OFFSET:");

        try (java.nio.channels.FileChannel channel =
                     java.nio.channels.FileChannel.open(file)) {

            byte[] buffer = new byte[CHUNK_BYTES];
            OutputStream output = socket.getOutputStream();

            while (offset < size) {
                if (progress != null) {
                    progress.accept(offset, size);
                }

                int length = (int) Math.min(CHUNK_BYTES, size - offset);
                response = sendLine(
                        "UPLOAD_CHUNK:" + hash + ":" + offset + ":" + length);

                if (response != null && response.startsWith("OFFSET:")) {
                    offset = parseOffset(response, "OFFSET:");
                    continue;
                }

                if (!"READY".equals(response)) {
                    throw new IOException("手机拒绝接收分块，回复：" + response);
                }

                java.nio.ByteBuffer view = java.nio.ByteBuffer.wrap(buffer, 0, length);
                long position = offset;
                while (view.hasRemaining()) {
                    int read = channel.read(view, position);
                    if (read < 0) {
                        throw new IOException("读取上传文件时文件被截断");
                    }
                    position += read;
                }

                output.write(buffer, 0, length);
                output.flush();

                offset = parseOffset(reader.readLine(), "ACK:");
            }
        }

        if (progress != null) {
            progress.accept(size, size);
        }
    }

    /**
     * 把本地音频传进手机音频库（能力 LIBRARY_MANAGE），返回新的 audioId。
     * 与分块上传共用续传机制。
     */
    public synchronized String uploadToLibrary(
            Path file,
            java.util.function.BiConsumer<Long, Long> progress)
            throws IOException {
        return uploadToLibrary(file, null, progress);
    }

    /** folder 非 null 时用 LIB_ADD_TO 放进指定文件夹（能力 LIB_FOLDERS；"" = 未分类）。 */
    public synchronized String uploadToLibrary(
            Path file,
            String folder,
            java.util.function.BiConsumer<Long, Long> progress)
            throws IOException {
        if (folder != null) {
            requireValidFolder(folder, true);
        }

        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("上传文件不存在：" + file);
        }

        long size = Files.size(file);
        if (size <= 0 || size > MAX_CHUNKED_UPLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "音频大小必须在 1 字节到 "
                            + (MAX_CHUNKED_UPLOAD_BYTES / 1024 / 1024) + "MB 之间");
        }

        if (socket == null || socket.isClosed() || !socket.isConnected()) {
            throw new IOException("PolarBell 尚未连接");
        }

        String hash = sha256(file);
        int previousTimeout = socket.getSoTimeout();

        try {
            socket.setSoTimeout(UPLOAD_READ_TIMEOUT);

            sendChunks(file, size, hash, progress);

            String fileName = singleLine(file.getFileName().toString());
            String response = folder == null
                    ? sendLine("LIB_ADD:" + hash + ":" + fileName)
                    : sendLine("LIB_ADD_TO:" + hash + ":" + folder.trim() + ":" + fileName);

            if (response == null || !response.startsWith("OK:")) {
                throw new IOException("手机保存音频失败，回复：" + response);
            }
            return response.substring("OK:".length()).trim();

        } catch (IOException e) {
            try {
                close();
            } catch (IOException ignored) {
            }
            throw e;

        } finally {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.setSoTimeout(previousTimeout);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 从手机音频库删除（能力 LIBRARY_MANAGE）。 */
    public synchronized void deleteLibraryAudio(String audioId) throws IOException {
        requireValidId(audioId);
        String response = sendCommand("LIB_DELETE:" + audioId);
        if (!"OK".equals(response)) {
            throw new IOException("手机删除音频失败，回复：" + response);
        }
    }

    /** 重命名手机音频库中的音频，只改显示名称（能力 LIBRARY_MANAGE）。 */
    public synchronized void renameLibraryAudio(String audioId, String name) throws IOException {
        requireValidId(audioId);
        String clean = singleLine(name);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("名称不能为空");
        }
        String response = sendCommand("LIB_RENAME:" + audioId + ":" + clean);
        if (!"OK".equals(response)) {
            throw new IOException("手机重命名音频失败，回复：" + response);
        }
    }

    /** 设置手机播放音量 0-100（能力 VOLUME）。 */
    public synchronized void setVolume(int volume) throws IOException {
        int value = Math.max(0, Math.min(100, volume));
        String response = sendCommand("SET_VOLUME:" + value);
        if (!"OK".equals(response)) {
            throw new IOException("手机设置音量失败，回复：" + response);
        }
    }

    /** 手机是否仍在播放（能力 PLAY_STATE）。 */
    public synchronized boolean isPlaying() throws IOException {
        String response = sendCommand("PLAY_STATE");
        if ("PLAYING".equals(response)) {
            return true;
        }
        if ("IDLE".equals(response)) {
            return false;
        }
        throw new IOException("手机播放状态回复不正确：" + response);
    }

    /** LIB_READ 读到的一段：文件总大小、扩展名、本段数据。 */
    public record LibraryChunk(long total, String extension, byte[] data) {
    }

    /** 从手机音频库读取一段文件内容（能力 LIB_READ），用于电脑试听。 */
    public synchronized LibraryChunk readLibraryChunk(String audioId, long offset, int length)
            throws IOException {
        requireValidId(audioId);
        return parseLibraryChunk(sendCommand("LIB_READ:" + audioId + ":" + offset + ":" + length));
    }

    static LibraryChunk parseLibraryChunk(String response) throws IOException {
        if (response == null || !response.startsWith("DATA:")) {
            throw new IOException("读取手机音频失败，回复：" + response);
        }
        String[] parts = response.substring("DATA:".length()).split(":", 3);
        if (parts.length != 3) {
            throw new IOException("读取手机音频失败，回复格式不正确");
        }
        try {
            return new LibraryChunk(
                    Long.parseLong(parts[0]),
                    parts[1].replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT),
                    java.util.Base64.getDecoder().decode(parts[2]));
        } catch (IllegalArgumentException e) {
            throw new IOException("读取手机音频失败，数据格式不正确", e);
        }
    }

    // ==================== 文件夹（能力 LIB_FOLDERS） ====================

    public synchronized void createFolder(String name) throws IOException {
        requireValidFolder(name, false);
        expectOk(sendCommand("LIB_FOLDER_CREATE:" + name.trim()), "新建文件夹");
    }

    public synchronized void renameFolder(String oldName, String newName) throws IOException {
        requireValidFolder(oldName, false);
        requireValidFolder(newName, false);
        expectOk(sendCommand("LIB_FOLDER_RENAME:" + oldName.trim() + ":" + newName.trim()), "重命名文件夹");
    }

    public synchronized void deleteFolder(String name) throws IOException {
        requireValidFolder(name, false);
        expectOk(sendCommand("LIB_FOLDER_DELETE:" + name.trim()), "删除文件夹");
    }

    /** folder 为 "" 表示移回未分类。 */
    public synchronized void moveAudioToFolder(String audioId, String folder) throws IOException {
        requireValidId(audioId);
        requireValidFolder(folder, true);
        expectOk(sendCommand("LIB_MOVE:" + audioId + ":" + folder.trim()), "移动音频");
    }

    private static void expectOk(String response, String action) throws IOException {
        if (!"OK".equals(response)) {
            String reason = response != null && response.startsWith("ERROR:")
                    ? response.substring("ERROR:".length()) : "回复：" + response;
            throw new IOException("手机" + action + "失败，" + reason);
        }
    }

    /** 文件夹名称：不含冒号和换行，最多 40 字；allowEmpty 时空字符串表示未分类。 */
    static void requireValidFolder(String folder, boolean allowEmpty) {
        String clean = folder == null ? "" : folder.trim();
        if (clean.isEmpty()) {
            if (allowEmpty) {
                return;
            }
            throw new IllegalArgumentException("文件夹名称不能为空");
        }
        if (clean.length() > 40 || clean.contains(":") || clean.contains("\n") || clean.contains("\r")) {
            throw new IllegalArgumentException("文件夹名称不能包含冒号或换行，且不超过 40 个字");
        }
        if ("未分类".equals(clean)) {
            throw new IllegalArgumentException("“未分类”是保留名称");
        }
    }

    private static void requireValidId(String audioId) {
        if (audioId == null || audioId.isBlank()
                || audioId.contains(":") || audioId.contains("\n") || audioId.contains("\r")) {
            throw new IllegalArgumentException("音频 ID 不合法");
        }
    }

    static String singleLine(String text) {
        return text == null ? "" : text.replaceAll("[\\r\\n]+", " ").trim();
    }

    private String sendLine(String command) throws IOException {
        writer.write(command);
        writer.newLine();
        writer.flush();
        return reader.readLine();
    }

    static long parseOffset(String response, String prefix) throws IOException {
        if (response == null || !response.startsWith(prefix)) {
            throw new IOException("手机上传回复不正确：" + response);
        }
        try {
            return Long.parseLong(response.substring(prefix.length()).trim());
        } catch (NumberFormatException e) {
            throw new IOException("手机上传回复不正确：" + response, e);
        }
    }

    private static String sanitizeFileName(String name) {

        if (name == null || name.isBlank()) {
            return "audio.bin";
        }

        String base = name.replaceAll("[\\\\/\\s]+", "_");
        base = base.replaceAll("[^a-zA-Z0-9._-]", "");

        if (base.isEmpty()) {
            return "audio.bin";
        }

        if (base.length() > 64) {
            base = base.substring(base.length() - 64);
        }

        return base;
    }

    private static String sha256(Path file) throws IOException {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;

                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }

            StringBuilder hex = new StringBuilder(64);

            for (byte value : digest.digest()) {
                hex.append(String.format(Locale.ROOT, "%02x", value));
            }

            return hex.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new IOException("当前 Java 环境不支持 SHA-256", e);
        }
    }

    /**
     * 发送命令并读取手机回复
     */
    private String sendCommand(String command) throws IOException {

        if (socket == null || socket.isClosed()
                || !socket.isConnected()) {
            throw new IOException("PolarBell 尚未连接");
        }

        writer.write(command);
        writer.newLine();
        writer.flush();

        String response = reader.readLine();

        if (response == null) {
            close();
            throw new IOException("PolarBell 连接已断开");
        }

        return response;
    }

    /**
     * 关闭连接
     */
    @Override
    public synchronized void close() throws IOException {

        IOException exception = null;

        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                exception = e;
            }
        }

        socket = null;
        reader = null;
        writer = null;

        if (exception != null) {
            throw exception;
        }
    }
}