package com.studytimer.audio;

import com.studytimer.service.AppLog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 用 ffmpeg 从视频中提取音轨（.m4a），只把音轨发给手机。
 *
 * 查找顺序：环境变量 STUDYTIMER_FFMPEG → 安装目录\ffmpeg\ffmpeg.exe → %APPDATA%\StudyTimer\tools\ffmpeg.exe → PATH。
 * 找不到 ffmpeg 或提取失败时返回 empty，调用方退回上传整个视频。
 * 结果按“源文件路径 + 大小 + 修改时间”缓存在 %APPDATA%\StudyTimer\audio-cache。
 */
public class AudioExtractor {

    private static final long TIMEOUT_SECONDS = 180;

    private final Path ffmpeg;
    private final Path cacheDirectory;

    public AudioExtractor() {
        this(findFfmpeg(), defaultCacheDirectory());
    }

    public AudioExtractor(Path ffmpeg, Path cacheDirectory) {
        this.ffmpeg = ffmpeg;
        this.cacheDirectory = cacheDirectory;
    }

    public boolean isAvailable() {
        return ffmpeg != null && cacheDirectory != null;
    }

    /** 返回提取出的音频文件；不可用或失败时返回 empty（不抛异常）。 */
    public Optional<Path> extractAudio(Path video) {
        if (!isAvailable() || video == null || !Files.isRegularFile(video)) {
            return Optional.empty();
        }
        try {
            Files.createDirectories(cacheDirectory);
            Path output = cacheDirectory.resolve(cacheKey(video) + ".m4a");
            if (Files.isRegularFile(output) && Files.size(output) > 0) {
                return Optional.of(output);
            }

            Path temp = cacheDirectory.resolve(output.getFileName() + ".tmp.m4a");
            // 先直接复制音轨（快、无损），失败再转码为 AAC。
            if (run(video, temp, true) || run(video, temp, false)) {
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
                AppLog.info("已提取视频音轨：" + video.getFileName()
                        + " → " + Files.size(output) / 1024 + "KB");
                return Optional.of(output);
            }
            Files.deleteIfExists(temp);
            AppLog.warn("提取视频音轨失败，改为上传整个视频：" + video.getFileName());
        } catch (Exception e) {
            AppLog.warn("提取视频音轨失败，改为上传整个视频：" + e.getMessage());
        }
        return Optional.empty();
    }

    static List<String> buildCommand(Path ffmpeg, Path video, Path output, boolean copy) {
        List<String> command = new ArrayList<>(List.of(
                ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y",
                "-i", video.toString(), "-vn", "-sn", "-dn", "-map", "0:a:0"));
        if (copy) {
            command.addAll(List.of("-c:a", "copy"));
        } else {
            command.addAll(List.of("-c:a", "aac", "-b:a", "160k"));
        }
        command.addAll(List.of("-f", "mp4", output.toString()));
        return command;
    }

    private boolean run(Path video, Path output, boolean copy)
            throws IOException, InterruptedException {

        Files.deleteIfExists(output);
        Path log = cacheDirectory.resolve("ffmpeg-last.log");
        Process process = new ProcessBuilder(buildCommand(ffmpeg, video, output, copy))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            AppLog.warn("ffmpeg 超时（" + TIMEOUT_SECONDS + " 秒）");
            return false;
        }
        boolean ok = process.exitValue() == 0
                && Files.isRegularFile(output) && Files.size(output) > 0;
        if (!ok) {
            AppLog.warn("ffmpeg " + (copy ? "复制音轨" : "转码") + "失败，退出码 "
                    + process.exitValue() + "，详情见 " + log);
        }
        return ok;
    }

    static String cacheKey(Path video) throws IOException {
        String source = video.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT)
                + "|" + Files.size(video)
                + "|" + Files.getLastModifiedTime(video).toMillis();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }

    static Path findFfmpeg() {
        String configured = System.getenv("STUDYTIMER_FFMPEG");
        if (configured != null && !configured.isBlank()
                && Files.isRegularFile(Path.of(configured.trim()))) {
            return Path.of(configured.trim());
        }

        // 安装包内置：<安装目录>\ffmpeg\ffmpeg.exe（jpackage 提供 jpackage.app-path）
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            Path packaged = Path.of(appPath).getParent().resolve("ffmpeg").resolve("ffmpeg.exe");
            if (Files.isRegularFile(packaged)) {
                return packaged;
            }
        }

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            Path bundled = Path.of(appData, "StudyTimer", "tools", "ffmpeg.exe");
            if (Files.isRegularFile(bundled)) {
                return bundled;
            }
        }

        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                for (String name : new String[]{"ffmpeg.exe", "ffmpeg"}) {
                    try {
                        Path candidate = Path.of(dir.trim(), name);
                        if (Files.isRegularFile(candidate)) {
                            return candidate;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static Path defaultCacheDirectory() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return null;
        }
        return Path.of(appData, "StudyTimer", "audio-cache");
    }
}
