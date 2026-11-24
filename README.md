# StudyTimer

一个 Windows 桌面端的学习计时器（Java 21 + JavaFX），把「专注计划」「日常提醒」「健康休息」「长期目标」和「学习统计」放在同一个界面里。

配套的 Android 应用 **PolarBell** 可以把手机当成外放音箱：手机连着电脑时，阶段音乐、提醒声音、完成提示音都交给手机的 MediaPlayer 播放。

<p align="center">
  <img src="packaging/icon-preview.png" width="120" alt="StudyTimer 图标">
</p>

## 功能

### Focus Plan（左侧面板）
- 多个计划、每个计划多个阶段，阶段可单独设时长、提前播放秒数
- 阶段音乐：电脑本地文件（MP3/FLAC），或手机音频库中的单曲 / 整个文件夹（顺序或随机）
- 计时环 + 开始 / 暂停 / 跳过 / 重置；可记住上次进度，下次启动继续
- 计划分类；菜单里可删除分类（用到的计划自动归为未分类）

### Routine Reminder（右侧面板）
- 一次性或每周定时提醒，全屏弹出，可稍后提醒
- 睡眠 / 关机期间错过的提醒会补发（24 小时内），并标注原定时间
- 提醒媒体支持图片、视频、音乐；播完自动关闭窗口

### 生命保障
- 默认「工作 50 分钟 / 休息 10 分钟」自动计时；主动计划运行时暂停
- 键鼠连续空闲达到休息时长、或睡眠/关机跨过休息时长，视为已休息并清零
- 到点弹出不可关闭的全屏休息窗，只能推迟 5 分钟（每轮最多 2 次）
- 休息画面提示文字、进入休息 / 回到工作时的电脑与手机音乐都可自定义
- 关闭或修改设置有一键（简化障碍）/ 三问（完整障碍）保护，可选密码（只存 PBKDF2 哈希）

### 长期目标
- 每个目标绑定一个 Focus Plan，按学习记录累计专注时长
- 显示进度条、应完成与实际对比（超前 / 落后）、今天完成遍数、预计完成日期

### 学习统计
- 每个阶段结束自动记录（不足 10 秒不记，休息阶段不计入专注）
- 今日 / 连续天数 / 7 天与 30 天柱状图，每日目标，导出 CSV
- 启动时自动备份数据（保留 7 份）

### 其他
- 迷你悬浮计时窗（最小化或隐藏到托盘时置顶显示）
- 可配置开屏（图片 / 视频 / 音乐，可选电脑或优先手机播放）
- 数据导出 / 导入（zip，导入前自动备份）
- 自绘标题栏、窗口贴靠（拖到屏幕边缘半屏 / Win+方向键）、系统托盘常驻
- 单实例运行：再次启动会把已有窗口唤到最前

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 系统 | Windows（窗口贴靠、空闲检测依赖 Windows API） |
| JDK | 21 及以上（`jpackage` 打包需要 JDK 21） |
| Maven | 3.8+ |
| VLC | 必须安装本机 VLC（vlcj 使用系统 libVLC 播放音频） |
| ffmpeg | 可选。有则可只把视频音轨发给手机，没有则整个视频上传 |

## 运行

```bash
mvn javafx:run
```

## 测试

```bash
mvn clean test -q
```

测试结果在 `target/surefire-reports/`。存储类测试全部使用 `@TempDir`，不会读写真实的 `%APPDATA%`。

## 打包为 exe

```bash
packaging\build-exe.cmd
```

产物：`dist\StudyTimer\StudyTimer.exe`（jpackage app-image，内置 Java 运行时，主类 `Launcher`）。
若存在 `packaging\ffmpeg\ffmpeg.exe` 会一起打包，程序优先使用自带的那份。

> 生成的是 app-image 目录而非 msi（需要安装 WiX）。整个目录复制走即可使用，目标机器仍需安装 VLC。

## 数据与日志

所有数据存放在 `%APPDATA%\StudyTimer\`：

| 路径 | 内容 |
| --- | --- |
| `plans-index.json`、`plans/*.json` | 计划索引与每个计划 |
| `routine-reminders.json` | 日常提醒 |
| `routine-reminder-state.json` | 调度器上次检查时间、稍后提醒 |
| `focus-session-state.json` | 计时进度（下次启动继续） |
| `study-sessions.json` | 学习记录、每日目标 |
| `long-term-goals.json` | 长期目标 |
| `life-guard.json`、`life-guard-state.json` | 生命保障设置与计时状态 |
| `splash-settings.json`、`mini-timer.json`、`ui-settings.json`、`settings.json` | 开屏、悬浮窗、界面与其它设置 |
| `backups/` | 自动备份与导入前备份 |
| `audio-cache/` | 视频音轨缓存、手机音频缓存 |
| `logs/studytimer.log` | 运行日志 |

设置菜单里有「打开数据文件夹」「打开日志文件夹」。数据损坏时程序会用默认值继续运行，并把原文件另存为 `.corrupt-时间戳`。

## PolarBell（手机端）

手机端是独立仓库/工程（Android，`network/SocketServer.java`），通过 **ADB forward `tcp:8988` + TCP** 与电脑通信，播放由 Android `MediaPlayer` 完成。

- 手机 App 未运行时电脑照常工作，只是手机音频改为电脑播放
- 支持分块 + 断点续传上传（单文件上限 512MB），旧版 App 自动回退到 32MB 的单次上传
- 手机音频库支持逻辑文件夹；电脑端可管理手机端音频库、试听、调音量
- 手机端也有独立计划（前台服务 + WakeLock，熄屏照常计时）

## 项目结构

```
src/main/java/com/studytimer/
├── Main.java              # 启动、装配、关闭
├── Launcher.java          # exe 入口（非模块方式启动）
├── PolarBell*.java        # 手机端通信
├── AdbForwardManager.java # ADB 端口转发
├── audio/                 # VLC 播放、音频协调、文件夹播放、视频音轨提取
├── model/                 # 计划、阶段、学习记录
├── routine/               # 日常提醒（model / service / ui）
├── service/               # 计时引擎、各类存储、日志、单实例、看门狗
└── ui/                    # 界面：主视图、计时环、托盘、对话框、全屏窗口
src/main/resources/com/studytimer/ui/  # theme.css、reminder.css、图标
packaging/                 # 图标生成与 exe 打包脚本
```

## 已知限制

- 仅支持 Windows
- 运行依赖本机安装的 VLC
- 未安装 WiX 时只能生成 app-image，没有 msi 安装包
- 生命保障密码只存哈希，忘记无法找回

## 许可证

本仓库暂未指定开源许可证。在未添加 `LICENSE` 之前，默认保留所有权利；如需他人复用，建议补充 MIT 或 Apache-2.0。
