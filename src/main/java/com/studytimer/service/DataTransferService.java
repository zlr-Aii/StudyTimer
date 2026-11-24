package com.studytimer.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 数据导出 / 导入（计划、提醒、设置），格式为 zip，内容与数据目录中的文件一一对应。
 *
 * 导入不在运行中直接覆盖（运行中的程序退出时还会写回内存里的旧数据），
 * 而是先校验并暂存为 pending-import.zip，下次启动、读取任何数据之前再应用；
 * 应用前把当前数据另存到 backups/before-import-时间戳.zip。
 */
public final class DataTransferService {

    public static final String MARKER = "studytimer-export.txt";
    static final String PENDING = "pending-import.zip";

    /** 只导出用户数据；日志、缓存、ffmpeg、启动脚本、锁文件不导出。 */
    private static final List<String> FILES = List.of(
            "plans-index.json",
            "study-plan.json",
            "routine-reminders.json",
            "routine-reminder-state.json",
            "focus-session-state.json",
            "settings.json",
            "study-sessions.json",
            "splash-settings.json",
            "mini-timer.json",
            "ui-settings.json",
            "life-guard.json",
            "long-term-goals.json"
    );
    private static final String PLANS_DIR = "plans";

    private final Path dataDirectory;

    public DataTransferService() {
        this(defaultDataDirectory());
    }

    public DataTransferService(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    /** 导出到 target，返回写入的文件数。 */
    public int exportTo(Path target) throws IOException {
        List<String> entries = collectEntries();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp))) {
            zip.putNextEntry(new ZipEntry(MARKER));
            zip.write(("StudyTimer data export\ncreated=" + LocalDateTime.now() + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            for (String name : entries) {
                zip.putNextEntry(new ZipEntry(name));
                Files.copy(dataDirectory.resolve(name), zip);
                zip.closeEntry();
            }
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        return entries.size();
    }

    /** 校验导出包并暂存，下次启动时应用。返回包内数据文件数。 */
    public int stageImport(Path source) throws IOException {
        int count = validate(source);
        Files.createDirectories(dataDirectory);
        Files.copy(source, dataDirectory.resolve(PENDING), StandardCopyOption.REPLACE_EXISTING);
        return count;
    }

    public boolean hasPendingImport() {
        return dataDirectory != null && Files.isRegularFile(dataDirectory.resolve(PENDING));
    }

    /**
     * 启动时调用：若有暂存的导入包，先备份当前数据，再替换。
     * 返回备份文件路径；没有待导入内容时返回 null。
     */
    public Path applyPendingImport() throws IOException {
        if (!hasPendingImport()) {
            return null;
        }
        Path pending = dataDirectory.resolve(PENDING);
        validate(pending);

        Path backups = dataDirectory.resolve("backups");
        Files.createDirectories(backups);
        Path backup = backups.resolve("before-import-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip");
        exportTo(backup);

        // 替换：先清掉旧的计划文件，避免导入后残留不在新索引里的计划。
        for (String name : FILES) {
            Files.deleteIfExists(dataDirectory.resolve(name));
        }
        Path plans = dataDirectory.resolve(PLANS_DIR);
        if (Files.isDirectory(plans)) {
            try (Stream<Path> stream = Files.list(plans)) {
                for (Path file : stream.toList()) {
                    if (Files.isRegularFile(file)) {
                        Files.delete(file);
                    }
                }
            }
        }

        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pending))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || MARKER.equals(name) || !isAllowed(name)) {
                    continue;
                }
                Path target = dataDirectory.resolve(name).normalize();
                Files.createDirectories(target.getParent());
                copy(zip, target);
            }
        }
        Files.delete(pending);
        return backup;
    }

    /**
     * 每天第一次调用时把当前数据备份到 backups/auto-yyyy-MM-dd.zip，只保留最近 keep 份。
     * 今天已有备份或没有任何数据时返回 null。
     */
    public Path createDailyBackup(java.time.LocalDate today, int keep) throws IOException {
        if (dataDirectory == null || collectEntries().isEmpty()) {
            return null;
        }
        Path backups = dataDirectory.resolve("backups");
        Path target = backups.resolve(AUTO_PREFIX + today + ".zip");
        if (Files.exists(target)) {
            return null;
        }
        Files.createDirectories(backups);
        exportTo(target);

        try (Stream<Path> stream = Files.list(backups)) {
            List<Path> autos = stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(AUTO_PREFIX) && n.endsWith(".zip");
                    })
                    .sorted()
                    .toList();
            for (int i = 0; i < autos.size() - Math.max(1, keep); i++) {
                Files.deleteIfExists(autos.get(i));
            }
        }
        return target;
    }

    static final String AUTO_PREFIX = "auto-";

    /** 检查是否为 StudyTimer 导出包；返回数据文件数。 */
    static int validate(Path source) throws IOException {
        boolean marker = false;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (MARKER.equals(name)) {
                    marker = true;
                } else if (!entry.isDirectory() && isAllowed(name)) {
                    count++;
                }
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("文件不是有效的 zip 包", e);
        }
        if (!marker) {
            throw new IOException("这不是 StudyTimer 导出的数据包");
        }
        if (count == 0) {
            throw new IOException("数据包里没有可导入的数据");
        }
        return count;
    }

    /** 只接受白名单文件名和 plans/ 下的单层 .json，防止路径穿越。 */
    static boolean isAllowed(String name) {
        if (name == null || name.contains("..") || name.contains("\\") || name.startsWith("/")) {
            return false;
        }
        if (FILES.contains(name)) {
            return true;
        }
        if (name.startsWith(PLANS_DIR + "/")) {
            String rest = name.substring(PLANS_DIR.length() + 1);
            return !rest.isEmpty() && !rest.contains("/") && rest.endsWith(".json");
        }
        return false;
    }

    private List<String> collectEntries() throws IOException {
        List<String> entries = new ArrayList<>();
        for (String name : FILES) {
            if (Files.isRegularFile(dataDirectory.resolve(name))) {
                entries.add(name);
            }
        }
        Path plans = dataDirectory.resolve(PLANS_DIR);
        if (Files.isDirectory(plans)) {
            try (Stream<Path> stream = Files.list(plans)) {
                stream.filter(Files::isRegularFile)
                        .map(file -> PLANS_DIR + "/" + file.getFileName())
                        .filter(DataTransferService::isAllowed)
                        .sorted()
                        .forEach(entries::add);
            }
        }
        return entries;
    }

    private static void copy(InputStream input, Path target) throws IOException {
        try (OutputStream output = Files.newOutputStream(target)) {
            input.transferTo(output);
        }
    }

    private static Path defaultDataDirectory() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return null;
        }
        return Path.of(appData, "StudyTimer");
    }
}
