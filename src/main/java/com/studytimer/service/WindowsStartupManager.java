package com.studytimer.service;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理 StudyTimer 的 Windows 当前用户登录启动项。
 *
 * 当前开发阶段通过隐藏的 VBS 脚本启动 Maven 工程，
 * 启动时带上 STUDYTIMER_AUTOSTART=1，让程序静默进入托盘。
 *
 * 重要：只判断"脚本文件存在"是不够的。
 * 工程目录被移动或重命名后，旧脚本仍然存在但完全无效，
 * 界面却会显示"已启用"。因此这里解析脚本内容并校验目标路径。
 *
 * Stable 打包后会替换为正式应用启动器（jpackage 生成的 exe）。
 */
public final class WindowsStartupManager {

    private static final String STARTUP_SCRIPT_NAME = "StudyTimer-Dev.vbs";

    /** 开机自动启动时传给应用的环境变量。 */
    public static final String AUTOSTART_ENV = "STUDYTIMER_AUTOSTART";

    private static final Pattern CURRENT_DIRECTORY_PATTERN =
            Pattern.compile("CurrentDirectory\\s*=\\s*\"([^\"]*)\"");

    public enum StartupState {
        /** 尚未设置登录启动。 */
        NOT_CONFIGURED,
        /** 已设置，且脚本指向当前有效的工程目录。 */
        ENABLED,
        /** 脚本存在但已失效（目录被移动、工程被删除、内容损坏）。 */
        BROKEN
    }

    public record StartupStatus(StartupState state, String detail) {

        public boolean isEnabled() {
            return state == StartupState.ENABLED;
        }

        public boolean isBroken() {
            return state == StartupState.BROKEN;
        }
    }

    private Path getStartupDirectory() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            throw new IllegalStateException("无法获取 Windows APPDATA 目录");
        }
        return Paths.get(appData, "Microsoft", "Windows",
                "Start Menu", "Programs", "Startup");
    }

    private Path getStartupScript() {
        return getStartupDirectory().resolve(STARTUP_SCRIPT_NAME);
    }

    /** 启动脚本的完整路径，供界面"打开所在位置"等入口使用。 */
    public Path scriptPath() {
        try {
            return getStartupScript();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public boolean isWindows() {
        return System.getProperty("os.name")
                .toLowerCase()
                .contains("win");
    }

    /**
     * 检查登录启动是否真的有效。
     */
    public boolean isEnabled() {
        return status().isEnabled();
    }

    /**
     * 详细状态：区分"没设置"和"设置了但已失效"。
     */
    public StartupStatus status() {
        if (!isWindows()) {
            return new StartupStatus(StartupState.NOT_CONFIGURED,
                    "当前系统不是 Windows");
        }

        Path script;
        try {
            script = getStartupScript();
        } catch (RuntimeException e) {
            return new StartupStatus(StartupState.NOT_CONFIGURED, e.getMessage());
        }

        if (!Files.isRegularFile(script)) {
            return new StartupStatus(StartupState.NOT_CONFIGURED, "未设置登录启动");
        }

        String content;
        try {
            content = readScriptText(script);
        } catch (IOException | RuntimeException e) {
            return new StartupStatus(StartupState.BROKEN,
                    "启动脚本无法读取：" + e.getMessage());
        }

        Matcher matcher = CURRENT_DIRECTORY_PATTERN.matcher(content);
        if (!matcher.find()) {
            return new StartupStatus(StartupState.BROKEN,
                    "启动脚本内容不完整，未找到工程目录");
        }

        String directory = matcher.group(1);
        Path projectRoot;
        try {
            projectRoot = Paths.get(directory);
        } catch (RuntimeException e) {
            return new StartupStatus(StartupState.BROKEN,
                    "启动脚本中的工程目录无效：" + directory);
        }

        if (!Files.isDirectory(projectRoot)) {
            return new StartupStatus(StartupState.BROKEN,
                    "启动脚本指向的目录已不存在：" + directory);
        }

        Path packagedExe = packagedExecutable();
        if (packagedExe != null) {
            if (sameDirectory(projectRoot, packagedExe.getParent())) {
                return new StartupStatus(StartupState.ENABLED,
                        "登录时将自动启动：" + packagedExe);
            }
            return new StartupStatus(StartupState.BROKEN,
                    "启动项指向其他位置：" + directory + "（当前程序：" + packagedExe + "）");
        }

        boolean hasPom = Files.isRegularFile(projectRoot.resolve("pom.xml"));
        boolean hasWrapper = Files.isRegularFile(projectRoot.resolve("mvnw.cmd"));
        if (!hasPom && !hasWrapper) {
            return new StartupStatus(StartupState.BROKEN,
                    "启动脚本指向的目录不是 StudyTimer 工程：" + directory);
        }

        // 关键：目录存在不代表是"当前这个"工程。
        // A:\xx 下同时存在多个历史副本时，旧脚本会静默启动旧版本。
        Path currentRoot = null;
        try {
            currentRoot = findProjectRoot();
        } catch (IOException | RuntimeException ignored) {
            // 无法确定当前工程时不做比较，退回"路径有效"判断。
        }

        if (currentRoot != null && !sameDirectory(projectRoot, currentRoot)) {
            return new StartupStatus(StartupState.BROKEN,
                    "启动项指向另一个工程副本：" + directory
                            + "（当前运行：" + currentRoot + "）");
        }

        return new StartupStatus(StartupState.ENABLED,
                "登录时将自动启动：" + projectRoot);
    }

    /** jpackage 安装版的 exe 路径；开发模式（mvn javafx:run）返回 null。 */
    private static Path packagedExecutable() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null || appPath.isBlank()) {
            return null;
        }
        Path exe = Paths.get(appPath).toAbsolutePath().normalize();
        return Files.isRegularFile(exe) ? exe : null;
    }

    private boolean sameDirectory(Path first, Path second) {
        try {
            return Files.isSameFile(first, second)
                    || first.toRealPath().equals(second.toRealPath());
        } catch (IOException | RuntimeException e) {
            return first.toAbsolutePath().normalize()
                    .toString()
                    .equalsIgnoreCase(second.toAbsolutePath().normalize().toString());
        }
    }

    /**
     * 启用 Windows 登录自动启动。
     *
     * 每次启用都会用"当前运行中的工程目录"重写脚本，
     * 因此工程被移动后，只要重新启用一次即可修复。
     */
    public void enable() throws IOException {
        if (!isWindows()) {
            throw new IOException("登录自动启动功能仅支持 Windows");
        }

        Path projectRoot;
        String launchCommand;
        Path packagedExe = packagedExecutable();

        if (packagedExe != null) {
            // 安装版：直接启动 StudyTimer.exe
            projectRoot = packagedExe.getParent();
            launchCommand = "cmd.exe /c \"set "
                    + AUTOSTART_ENV + "=1 && "
                    + quote(packagedExe.toString()) + "\"";
        } else {
            projectRoot = findProjectRoot();
            Path wrapper = projectRoot.resolve("mvnw.cmd");

            String mavenCommand;
            if (Files.isRegularFile(wrapper)) {
                mavenCommand = quote(wrapper.toAbsolutePath().toString());
            } else {
                verifyMavenAvailable();
                mavenCommand = "mvn";
            }

            launchCommand = "cmd.exe /c \"set "
                    + AUTOSTART_ENV + "=1 && "
                    + mavenCommand + " -q javafx:run\"";
        }

        String script = "Set shell = CreateObject(\"WScript.Shell\")\r\n"
                + "shell.CurrentDirectory = \""
                + escapeVbs(projectRoot.toString()) + "\"\r\n"
                + "shell.Run \"" + escapeVbs(launchCommand) + "\", 0, False\r\n";

        Path startupScript = getStartupScript();
        Path parent = startupScript.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("找不到 Windows 启动文件夹：" + parent);
        }

        Files.write(startupScript, encodeScript(script));

        StartupStatus afterWrite = status();
        if (!afterWrite.isEnabled()) {
            throw new IOException("写入启动脚本后校验失败：" + afterWrite.detail());
        }

        AppLog.info("登录自动启动已启用：" + startupScript + " -> " + projectRoot);
    }

    /**
     * 生成 Windows Script Host 可执行的脚本字节。
     *
     * 关键：Java 的 StandardCharsets.UTF_16 写出的是 **大端**（FE FF），
     * 而 WSH 只识别 UTF-16LE（FF FE）或 ANSI。用错编码时脚本会静默不执行，
     * 表现为"开机后什么都没发生"。这里显式写 UTF-16LE + BOM。
     */
    private byte[] encodeScript(String script) {
        byte[] body = script.getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[body.length + 2];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xFE;
        System.arraycopy(body, 0, bytes, 2, body.length);
        return bytes;
    }

    /**
     * 按 BOM 解码脚本，便于校验旧版本写出的文件。
     */
    private String readScriptText(Path script) throws IOException {
        byte[] bytes = Files.readAllBytes(script);
        if (bytes.length >= 2) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 0xFF && second == 0xFE) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
            }
            if (first == 0xFE && second == 0xFF) {
                throw new IOException(
                        "启动脚本是 UTF-16BE 编码，Windows 无法执行；请重新启用登录启动以修复");
            }
            if (first == 0xEF && second == 0xBB && bytes.length >= 3
                    && (bytes[2] & 0xFF) == 0xBF) {
                return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
            }
        }

        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.indexOf('\uFFFD') >= 0) {
            // 无 BOM 的旧文件可能使用系统 ANSI 编码。
            text = new String(bytes, java.nio.charset.Charset.defaultCharset());
        }
        return text;
    }

    /** 关闭 Windows 登录自动启动。 */
    public void disable() throws IOException {
        if (!isWindows()) {
            return;
        }
        Path script = getStartupScript();
        boolean deleted = Files.deleteIfExists(script);
        AppLog.info(deleted ? "登录自动启动已关闭" : "登录自动启动原本未启用");
    }

    /**
     * 修复失效的启动项：按当前工程目录重写脚本。
     */
    public void repair() throws IOException {
        disable();
        enable();
    }

    private Path findProjectRoot() throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();

        Path result = findPomUpwards(current);
        if (result != null) {
            return result;
        }

        try {
            Path location = Paths.get(getClass()
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()).toAbsolutePath().normalize();
            result = findPomUpwards(location);
            if (result != null) {
                return result;
            }
        } catch (URISyntaxException e) {
            throw new IOException("无法确定 StudyTimer 项目位置", e);
        }

        throw new IOException("无法找到 StudyTimer 的 pom.xml。请从项目根目录启动程序。");
    }

    private Path findPomUpwards(Path start) {
        Path current = Files.isDirectory(start) ? start : start.getParent();
        for (int i = 0; i < 6 && current != null; i++) {
            if (Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private void verifyMavenAvailable() throws IOException {
        Process process = new ProcessBuilder("cmd.exe", "/c", "where mvn")
                .redirectErrorStream(true)
                .start();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("检测 Maven 超时");
            }
            if (process.exitValue() != 0) {
                throw new IOException("系统 PATH 中找不到 Maven，请配置 Maven 环境变量后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("检测 Maven 时被中断", e);
        }
    }

    private String quote(String value) {
        return "\"" + value + "\"";
    }

    private String escapeVbs(String value) {
        return value.replace("\"", "\"\"");
    }
}
