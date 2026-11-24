package com.studytimer;

import com.studytimer.service.AppLog;
import com.studytimer.service.SingleInstanceGuard;
import com.studytimer.service.WindowsStartupManager;
import com.studytimer.service.TimerEngine;
import com.studytimer.ui.FocusPlanPane;
import com.studytimer.ui.FocusTimerPane;
import com.studytimer.ui.MainView;
import com.studytimer.ui.StatusCenter;
import com.studytimer.ui.TrayController;
import com.studytimer.ui.TimerRing;
import com.studytimer.ui.WindowChrome;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;

import java.io.File;
import com.studytimer.model.PlanInfo;
import javafx.util.StringConverter;
import com.studytimer.service.FocusPlanController;
import com.studytimer.model.TimerStage;
import com.studytimer.service.FocusTimerController;
import com.studytimer.routine.service.RoutineReminderController;
import com.studytimer.routine.service.RoutineReminderScheduler;
import com.studytimer.routine.ui.RoutineReminderWindow;
import com.studytimer.routine.ui.RoutineReminderManagerPane;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.scene.text.Font;
import javafx.stage.StageStyle;

import com.studytimer.audio.VlcAudioService;
import com.studytimer.audio.AudioPlaybackCoordinator;

import java.awt.SystemTray;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;

import com.studytimer.service.FocusSessionStorage;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;

public class Main extends Application implements FocusPlanPane.Host, FocusTimerPane.Host, TrayController.Host,
        com.studytimer.ui.LifeGuardDialogs.PhoneAudioSource {

    private final ObservableList<TimerStage> stages = FXCollections.observableArrayList();
    private FocusTimerController focusTimerController;
    private VlcAudioService audioService;
    private VlcAudioService reminderAudioService;
    private AudioPlaybackCoordinator audioPlaybackCoordinator;
    // Focus Timer 运行状态持久化
    private FocusSessionStorage focusSessionStorage;

    private ScheduledExecutorService focusSessionSaveScheduler;

    private RoutineReminderController routineReminderController;
    private RoutineReminderScheduler routineReminderScheduler;
    private RoutineReminderWindow routineReminderWindow;
    private RoutineReminderManagerPane routineReminderManagerPane;
    private final Queue<RoutineReminderScheduler.TriggeredReminder> pendingRoutineReminders = new ArrayDeque<>();
    private boolean routineReminderShowing;
    /** 启动时应用导入数据的结果，界面就绪后显示在状态栏。 */
    private String importNotice;
    /** 提醒显示期间计时切换到的阶段名；提醒全部关闭后在状态栏提示一次。 */
    private String stageChangedDuringReminder;

    private PolarBellService polarBellService = new PolarBellService();

    // ADB 自动端口转发管理
    private final AdbForwardManager adbForwardManager =
            new AdbForwardManager();


    // PolarBell 手机连接状态
    private Label polarBellStatusLabel;

    // 统一状态消息中心：所有非致命提示都走这里，避免红字永久驻留
    private StatusCenter statusCenter;
    private Button statusActionButton;
    private Label statusMessageLabel;

    // 开机自动启动时静默进入托盘
    private boolean autostartSilent;

    // 学习记录与每日目标
    private final com.studytimer.service.StudySessionStore studySessionStore =
            new com.studytimer.service.StudySessionStore(
                    com.studytimer.service.StudySessionStore.defaultDataDirectory());
    private Label goalBadge;

    // 开屏
    private final com.studytimer.service.SplashSettingsStore splashSettingsStore =
            new com.studytimer.service.SplashSettingsStore(
                    com.studytimer.service.StudySessionStore.defaultDataDirectory());
    private StackPane overlayHost;
    private com.studytimer.ui.SplashOverlay splashOverlay;

    // 迷你悬浮计时窗
    private final com.studytimer.service.MiniTimerSettingsStore miniTimerSettingsStore =
            new com.studytimer.service.MiniTimerSettingsStore(
                    com.studytimer.service.StudySessionStore.defaultDataDirectory());
    private final com.studytimer.service.UiSettingsStore uiSettingsStore =
            new com.studytimer.service.UiSettingsStore(
                    com.studytimer.service.StudySessionStore.defaultDataDirectory());
    private com.studytimer.ui.MiniTimerWindow miniTimerWindow;
    private javafx.animation.Timeline miniTimerSync;

    // 生命保障
    private com.studytimer.ui.LifeGuardController lifeGuard;

    // FX 线程看门狗（卡死时把线程堆栈写进日志目录）
    private com.studytimer.service.FxWatchdog fxWatchdog;

    // 长期目标
    private final com.studytimer.service.LongTermGoalStore longTermGoalStore =
            new com.studytimer.service.LongTermGoalStore(
                    com.studytimer.service.StudySessionStore.defaultDataDirectory());
    private com.studytimer.ui.LongTermGoalStrip goalStrip;

    private FocusTimerPane focusTimerPane;

    // 单实例与第二实例唤醒
    private static final SingleInstanceGuard singleInstanceGuard = new SingleInstanceGuard();
    private javafx.stage.Stage mainStage;

    public void restoreMainWindow() {
        javafx.stage.Stage stage = mainStage;
        if (stage == null) {
            return;
        }
        stage.show();
        stage.setIconified(false);
        stage.toFront();
        stage.requestFocus();
    }

    private FocusPlanPane focusPlanPane;

    // 系统托盘
    private TrayController trayController;

    @Override
    public void start(javafx.stage.Stage primaryStage) {
        this.mainStage = primaryStage;

        // 上次选择了"导入数据"：在读取任何数据之前替换。
        try {
            Path backup = new com.studytimer.service.DataTransferService().applyPendingImport();
            if (backup != null) {
                AppLog.info("已导入数据，导入前的数据备份在：" + backup);
                importNotice = "已导入数据。导入前的数据已备份到 " + backup.getFileName();
            }
        } catch (IOException | RuntimeException e) {
            AppLog.error("应用导入数据失败，继续使用现有数据", e);
            importNotice = "导入数据失败，已保留现有数据：" + describeError(e);
        }

        // 每天第一次启动时自动备份，保留 7 份。
        try {
            Path autoBackup = new com.studytimer.service.DataTransferService()
                    .createDailyBackup(java.time.LocalDate.now(), 7);
            if (autoBackup != null) {
                AppLog.info("已创建每日自动备份：" + autoBackup);
            }
        } catch (IOException | RuntimeException e) {
            AppLog.error("每日自动备份失败", e);
        }

        studySessionStore.load();
        splashSettingsStore.load();
        miniTimerSettingsStore.load();
        uiSettingsStore.load();

        // 初始化默认计划
        focusTimerPane = new FocusTimerPane(stages, this);
        focusTimerPane.loadDefaultStages();
        focusPlanPane = new FocusPlanPane(stages, this);

        // 初始化多计划存储服务

        try {

            // 初始化计划存储服务
            focusPlanPane.getFocusPlanController().initialize(List.copyOf(stages));

            // 加载上次使用的计划
            List<TimerStage> loadedStages =
                    focusPlanPane.getFocusPlanController().loadActivePlan();

            stages.setAll(loadedStages);

            System.out.println(
                    "当前计划 ID：" +
                            focusPlanPane.getFocusPlanController().getActivePlanId()
            );

        } catch (IOException | RuntimeException e) {

            AppLog.error("加载计划数据失败，程序退出", e);
            e.printStackTrace();

            Alert alert = new Alert(Alert.AlertType.ERROR);

            alert.setTitle("启动失败");
            alert.setHeaderText("StudyTimer 无法加载计划数据");
            alert.setContentText(
                    "计划初始化或读取失败：\n" +
                            e.getMessage() +
                            "\n\n程序将退出。请检查本地计划文件。"
            );

            alert.showAndWait();

            Platform.exit();
            return;
        }

        // 创建电脑本地音频服务与统一音频协调器
        audioService = new VlcAudioService();
        reminderAudioService = new VlcAudioService();
        audioPlaybackCoordinator = new AudioPlaybackCoordinator(
                audioService,
                reminderAudioService,
                polarBellService,
                this::handleAudioPlaybackError
        );
        applyFinishSound(uiSettingsStore.get());

        // Routine Reminder 初始化失败不影响 Focus Timer 主功能启动。
        try {
            routineReminderController = new RoutineReminderController();
            routineReminderController.initialize();
            routineReminderWindow = new RoutineReminderWindow(
                    audioPlaybackCoordinator,
                    error -> reportBackgroundError("Routine Reminder 媒体错误", error)
            );
            routineReminderManagerPane = new RoutineReminderManagerPane(
                    routineReminderController,
                    error -> reportBackgroundError("Routine Reminder 管理界面错误", error)
            );
            routineReminderWindow.setOnDismissed(this::showNextRoutineReminder);
            routineReminderWindow.setOnPhoneFallback(reason -> {
                if (statusCenter != null) {
                    statusCenter.warning("提醒声音未在手机播放，已改由电脑播放：" + reason);
                }
            });
            routineReminderScheduler = new RoutineReminderScheduler(
                    routineReminderController,
                    triggered -> Platform.runLater(() -> enqueueRoutineReminder(triggered)),
                    error -> AppLog.error("Routine Reminder 调度错误", error),
                    java.time.Clock.systemDefaultZone(),
                    1_000,
                    new com.studytimer.routine.service.RoutineReminderStateStore()
            );
            routineReminderWindow.setOnSnooze((triggered, minutes) -> {
                if (routineReminderScheduler == null) {
                    throw new IllegalStateException("Routine Reminder 调度器尚未初始化");
                }
                routineReminderScheduler.snooze(triggered, minutes);
                AppLog.info("Routine Reminder 已设置稍后提醒：" + minutes + " 分钟后再次显示");
            });
        } catch (IOException | RuntimeException e) {
            AppLog.error("Routine Reminder 初始化失败", e);
            System.err.println("Routine Reminder 初始化失败，专注计时器仍将继续运行：" + e.getMessage());
            routineReminderController = null;
            routineReminderScheduler = null;
            routineReminderWindow = null;
            routineReminderManagerPane = null;
        }

        // 创建专注计时控制器；底层计时机制由控制器持有
        focusTimerController = new FocusTimerController(
                focusTimerPane::updateClock,
                (stage, index, total) -> {
                    focusTimerPane.onStageChanged(stage, index, total);
                    Platform.runLater(() -> {
                        if (routineReminderShowing) {
                            stageChangedDuringReminder = stage.getName();
                        }
                    });
                },
                this::onFinished
        );

        // 将计时器音频事件交给统一音频协调器处理
        focusTimerController.setAudioListener(new FocusTimerController.AudioListener() {
            @Override
            public void onPlay(TimerStage stage) {
                audioPlaybackCoordinator.playStage(stage);
            }

            @Override
            public void onStop() {
                audioPlaybackCoordinator.stopAll();
            }
        });

        focusTimerController.setStageEndListener((stage, index, planned, elapsed, completed) -> {
            String stageName = stage.getName();
            Platform.runLater(() -> recordStudySession(stageName, planned, elapsed, completed));
        });

        focusTimerController.setStages(List.copyOf(stages));

// 启动 ADB 自动管理。
// 确认端口转发建立后，再连接 PolarBell。
        adbForwardManager.start(() -> {

            System.out.println(
                    "ADB 转发就绪，开始连接 PolarBell"
            );

            connectPolarBellAsync();
        });


        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");

        // 设备连接状态：中性信息，不当作错误
        polarBellStatusLabel = new Label("手机：未连接");
        polarBellStatusLabel.getStyleClass().addAll("status-badge", "status-neutral");

        // 统一状态消息区：普通提示自动消失，错误保留并提供"处理"入口
        statusMessageLabel = new Label();
        statusMessageLabel.setVisible(false);
        statusMessageLabel.setManaged(false);

        statusActionButton = new Button("处理");
        statusActionButton.setVisible(false);
        statusActionButton.setManaged(false);

        statusCenter = new StatusCenter(statusMessageLabel, statusActionButton);
        if (routineReminderController != null) {
            String notice = routineReminderController.takeRecoveryNotice();
            if (notice != null) {
                AppLog.warn(notice);
                statusCenter.warning(notice);
            }
        } else {
            statusCenter.error("Routine Reminder 加载失败，日常提醒已停用；详情见日志");
        }
        String sessionNotice = studySessionStore.takeRecoveryNotice();
        if (sessionNotice != null) {
            AppLog.warn(sessionNotice);
            statusCenter.warning(sessionNotice);
        }

        lifeGuard = new com.studytimer.ui.LifeGuardController(
                new com.studytimer.service.LifeGuardStore(
                        com.studytimer.service.StudySessionStore.defaultDataDirectory()),
                this::isTimerRunning,
                this::isActivePlanPaused,
                message -> statusCenter.info(message),
                audioPlaybackCoordinator);
        lifeGuard.getBadge().setOnMouseClicked(event -> openLifeGuardSettings());

        // 监听 PolarBell 连接状态
        polarBellService.addStateListener(state -> {
            Platform.runLater(() -> {

                polarBellStatusLabel.setText(
                        "手机：" + state.getDisplayName()
                );

                // 连接状态只是信息，不用红色，避免把"未连接"误当成故障
                String statusClass = switch (state) {
                    case CONNECTING -> "status-warning";
                    case CONNECTED -> "status-success";
                    case FAILED -> "status-error";
                    case DISCONNECTED, UNAVAILABLE, CLOSED -> "status-neutral";
                };

                polarBellStatusLabel.getStyleClass().removeAll(
                        "status-neutral", "status-warning", "status-success", "status-error"
                );
                polarBellStatusLabel.getStyleClass().add(statusClass);
            });
        });

// 同步一次当前状态，避免错过监听注册前已经发生的状态变化
        polarBellStatusLabel.setText(
                "手机：" + polarBellService.getConnectionState().getDisplayName()
        );

        focusPlanPane.buildControls();

        Button savePlanButton = new Button("保存计划");
        savePlanButton.setOnAction(e -> focusPlanPane.savePlan());

        // ==================== 主界面：左 Focus Plan + 右 日常提醒 ====================

        // 窗口置顶开关
        CheckBox alwaysOnTopCheckBox = new CheckBox("窗口置顶");

        alwaysOnTopCheckBox.setSelected(false);

        // 窗口透明度调节
        Slider opacitySlider = new Slider(0.3, 1.0, 1.0);

        // 显示当前透明度
        Label opacityLabel = new Label("100%");

        // 透明度变化时更新窗口
        opacitySlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            double opacity = newValue.doubleValue();

            primaryStage.setOpacity(opacity);
            opacityLabel.setText(Math.round(opacity * 100) + "%");
        });


        HBox opacityBox = new HBox(10);
        opacityBox.setAlignment(Pos.CENTER_LEFT);

        Label opacityTitle = new Label("窗口透明度：");

        opacitySlider.setPrefWidth(180);

        opacityBox.getChildren().addAll(
                opacityTitle,
                opacitySlider,
                opacityLabel
        );

        // 默认不置顶

        // 根据复选框状态设置窗口是否置顶
        alwaysOnTopCheckBox.setOnAction(event -> {
            primaryStage.setAlwaysOnTop(alwaysOnTopCheckBox.isSelected());
        });

        // ---- 顶部栏设置菜单：窗口置顶 / 透明度 ----
        VBox windowSettings = new VBox(10, alwaysOnTopCheckBox, opacityBox);
        windowSettings.setPadding(new Insets(6, 4, 6, 4));
        CustomMenuItem windowSettingsItem = new CustomMenuItem(windowSettings, false);
        MenuButton settingsMenu = new MenuButton("⚙ 设置");
        settingsMenu.getStyleClass().add("header-menu");
        settingsMenu.getItems().add(windowSettingsItem);

        // 登录启动：每次打开菜单时按真实状态刷新勾选。
        CheckMenuItem startupItem = new CheckMenuItem("登录 Windows 时启动");
        settingsMenu.setOnShowing(event -> {
            if (trayController != null) {
                startupItem.setSelected(trayController.startupStatus().isEnabled());
            }
        });
        startupItem.setOnAction(event -> {
            boolean enable = startupItem.isSelected();
            try {
                trayController.setStartupEnabled(enable);
                statusCenter.success(enable
                        ? "已启用登录自动启动"
                        : "已关闭登录自动启动");
            } catch (Exception e) {
                startupItem.setSelected(!enable);
                AppLog.error("设置登录自动启动失败", e);
                statusCenter.error("设置登录自动启动失败：" + describeError(e));
            }
        });

        MenuItem exportItem = new MenuItem("导出数据…");
        exportItem.setOnAction(event -> exportData());
        MenuItem importItem = new MenuItem("导入数据…");
        importItem.setOnAction(event -> importData());

        MenuItem phoneLibraryItem = new MenuItem("手机音频库…");
        phoneLibraryItem.setOnAction(event ->
                com.studytimer.ui.PhoneLibraryDialog.show(mainStage, polarBellService));

        MenuItem finishSoundItem = new MenuItem("完成提示音…");
        finishSoundItem.setOnAction(event -> com.studytimer.ui.FinishSoundDialog.show(
                mainStage, polarBellService, uiSettingsStore.get(), settings -> {
                    uiSettingsStore.save(settings);
                    applyFinishSound(settings);
                    statusCenter.success("完成提示音已保存");
                }));

        MenuItem newGoalItem = new MenuItem("新建长期目标…");
        newGoalItem.setOnAction(event -> {
            if (goalStrip != null) {
                goalStrip.createGoal();
            }
        });
        MenuItem statsItem = new MenuItem("学习统计…");
        statsItem.setOnAction(event -> openStats());
        MenuItem lifeGuardItem = new MenuItem("生命保障…");
        lifeGuardItem.setOnAction(event -> openLifeGuardSettings());
        MenuItem splashItem = new MenuItem("开屏…");
        splashItem.setOnAction(event -> com.studytimer.ui.SplashSettingsDialog.show(
                mainStage, splashSettingsStore, settings -> showSplash(settings, false)));

        CheckMenuItem miniTimerItem = new CheckMenuItem("最小化时显示悬浮计时窗");
        miniTimerItem.setSelected(miniTimerSettingsStore.get().enabled);
        miniTimerItem.setOnAction(event -> {
            var s = miniTimerSettingsStore.get();
            s.enabled = miniTimerItem.isSelected();
            miniTimerSettingsStore.save(s);
            syncMiniTimer();
        });

        MenuItem dataFolderItem = new MenuItem("打开数据文件夹");
        dataFolderItem.setOnAction(event -> openDataFolder());
        MenuItem logFolderItem = new MenuItem("打开日志文件夹");
        logFolderItem.setOnAction(event -> openLogFolder());
        MenuItem aboutItem = new MenuItem("关于 StudyTimer");
        aboutItem.setOnAction(event -> showAbout());

        CheckMenuItem hintsItem = new CheckMenuItem("显示操作提示");
        hintsItem.setSelected(uiSettingsStore.get().showHints);
        hintsItem.setOnAction(event -> {
            var s = uiSettingsStore.get();
            s.showHints = hintsItem.isSelected();
            uiSettingsStore.save(s);
            focusTimerPane.setHintsVisible(s.showHints);
        });
        CheckMenuItem reminderPanelItem = new CheckMenuItem("显示 Routine Reminder");

        settingsMenu.getItems().addAll(
                new SeparatorMenuItem(), hintsItem, reminderPanelItem,
                new SeparatorMenuItem(), newGoalItem, lifeGuardItem, statsItem, splashItem, miniTimerItem, startupItem, phoneLibraryItem, finishSoundItem,
                new SeparatorMenuItem(), exportItem, importItem, dataFolderItem, logFolderItem,
                new SeparatorMenuItem(), aboutItem);

        HBox headerStatus = new HBox(8, statusMessageLabel, statusActionButton, lifeGuard.getBadge(), polarBellStatusLabel);
        headerStatus.setAlignment(Pos.CENTER_RIGHT);

        // ---- Focus Plan：计时条（环形 + 当前阶段 + 控制按钮）----
        savePlanButton.getStyleClass().add("accent-button");

        HBox stageToolbar = focusTimerPane.getStageToolbar();
        stageToolbar.getChildren().add(savePlanButton);

        VBox focusContent = new VBox(12, focusPlanPane.getPlanRow(), focusPlanPane.getPlanDescriptionLabel(),
                focusTimerPane.getTimerStrip(), focusTimerPane.getStageTable(), stageToolbar,
                focusTimerPane.getStageHint());

        // ---- 日常提醒 ----
        javafx.scene.Node reminderContent;
        if (routineReminderManagerPane != null) {
            reminderContent = routineReminderManagerPane;
        } else {
            Label unavailable = new Label("Routine Reminder 模块初始化失败，请查看日志。");
            unavailable.getStyleClass().add("hint-text");
            unavailable.setWrapText(true);
            reminderContent = new VBox(unavailable);
        }

        // 去掉系统标题栏，StudyTimer / 设置 / 手机状态 / 窗口按钮合并到同一行
        primaryStage.initStyle(StageStyle.UNDECORATED);
        WindowChrome windowChrome = new WindowChrome(primaryStage);

        MainView mainView = new MainView(
                new javafx.scene.Node[]{settingsMenu},
                headerStatus,
                windowChrome.createControls(),
                focusContent,
                reminderContent
        );

        root.setCenter(mainView);

        // 长期目标：Focus Plan / Routine Reminder 上方的可收起横条
        longTermGoalStore.load();
        String goalNotice = longTermGoalStore.takeRecoveryNotice();
        if (goalNotice != null) {
            AppLog.warn(goalNotice);
            statusCenter.warning(goalNotice);
        }
        goalStrip = new com.studytimer.ui.LongTermGoalStrip(
                longTermGoalStore,
                studySessionStore::records,
                () -> focusPlanPane.getFocusPlanController().getPlans(),
                this::planFocusMinutes,
                message -> statusCenter.success(message));
        goalStrip.expandedProperty().set(uiSettingsStore.get().goalStripExpanded);
        goalStrip.expandedProperty().addListener((obs, o, open) -> {
            var s = uiSettingsStore.get();
            s.goalStripExpanded = open;
            uiSettingsStore.save(s);
        });
        goalStrip.refresh();
        mainView.setTopStrip(goalStrip);
        // 收起/展开按钮放在顶部栏“设置”右侧
        mainView.getHeader().getChildren().add(2, goalStrip.getToggleButton());

        var uiSettings = uiSettingsStore.get();
        focusTimerPane.setHintsVisible(uiSettings.showHints);
        mainView.setReminderShown(uiSettings.showReminderPanel);
        reminderPanelItem.setSelected(uiSettings.showReminderPanel);
        reminderPanelItem.setOnAction(event -> mainView.setReminderShown(reminderPanelItem.isSelected()));
        mainView.getReminderPanel().expandedProperty().addListener((obs, o, shown) -> {
            reminderPanelItem.setSelected(shown);
            var s = uiSettingsStore.get();
            if (s.showReminderPanel != shown) {
                s.showReminderPanel = shown;
                uiSettingsStore.save(s);
            }
        });

        // 外层 StackPane 用来盖开屏；窗口边框样式（含 :maximized）放在场景根节点上。
        overlayHost = new StackPane(root);
        overlayHost.getStyleClass().add("window-root");

        // ==================== 主界面结束 ====================

        // 初始高度要能完整显示长期目标横条，但不超过屏幕可用区域
        javafx.geometry.Rectangle2D visual = javafx.stage.Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(overlayHost,
                Math.min(1280, visual.getWidth() - 40),
                Math.min(920, visual.getHeight() - 20));
        var themeUrl = Main.class.getResource("/com/studytimer/ui/theme.css");
        if (themeUrl != null) {
            scene.getStylesheets().add(themeUrl.toExternalForm());
        } else {
            AppLog.warn("StudyTimer 主题样式未找到，使用 JavaFX 默认样式");
        }

        primaryStage.setTitle("StudyTimer");
        for (int size : new int[]{16, 32, 48, 256}) {
            java.io.InputStream icon = com.studytimer.ui.MainView.class
                    .getResourceAsStream("icon-" + size + ".png");
            if (icon != null) {
                primaryStage.getIcons().add(new javafx.scene.image.Image(icon));
            }
        }
        primaryStage.setScene(scene);
        windowChrome.installDrag(mainView.getHeader());
        windowChrome.installResize(scene);
        windowChrome.installKeyboardSnap(scene);

        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);

        // 点击右上角 ×：托盘可用时隐藏到后台；否则正常退出，避免无入口地驻留后台。
        primaryStage.setOnCloseRequest(event -> {
            event.consume();
            if (trayController.isAvailable()) {
                primaryStage.hide();
            } else {
                System.out.println("系统托盘不可用，关闭主窗口时退出 StudyTimer");
                Platform.exit();
            }
        });

        primaryStage.show();
        trayController = new TrayController(this);
        trayController.setupSystemTray(primaryStage);
        singleInstanceGuard.startWakeServer(this::restoreMainWindow);

        // 主窗口和 JavaFX 环境就绪后再启动提醒后台调度。
        if (routineReminderScheduler != null) {
            routineReminderScheduler.start();
        }

        if (!stages.isEmpty()) {
            focusTimerPane.updateClock(
                    stages.get(0).getMinutes() * 60L,
                    0,
                    stages.size()
            );
        } else {
            focusTimerPane.updateClock(0, 0, 0);
        }

        // 恢复上一次的 Focus Timer 状态
        initializeFocusSessionPersistence();

// 启动定时保存
        startFocusSessionAutoSave();

        setupMiniTimer(scene);

        String lifeGuardNotice = lifeGuard.start();
        if (lifeGuardNotice != null) {
            statusCenter.warning(lifeGuardNotice);
        }

        // 开机自启动时显示主窗口并提到最前
        applyAutostartBehaviour(primaryStage);

        fxWatchdog = new com.studytimer.service.FxWatchdog();
        fxWatchdog.start();

        boolean autostartLaunch = isAutostartLaunch();
        java.time.LocalDate today = java.time.LocalDate.now();
        if (splashSettingsStore.shouldShow(autostartLaunch, today)) {
            splashSettingsStore.markShown(autostartLaunch, today);
            showSplash(splashSettingsStore.get(), autostartLaunch);
        }

        // 自检登录启动项：目录被移动后旧脚本会失效，这里主动提示并可一键修复
        checkStartupItemHealth();

        if (importNotice != null) {
            if (importNotice.startsWith("导入数据失败")) {
                statusCenter.error(importNotice);
            } else {
                statusCenter.success(importNotice);
            }
            importNotice = null;
        }
    }

    /**
     * 判断本次是不是由"登录自动启动"拉起。
     */
    private boolean isAutostartLaunch() {
        String env = System.getenv(WindowsStartupManager.AUTOSTART_ENV);
        if (env != null && !env.isBlank() && !"0".equals(env.trim())) {
            return true;
        }
        try {
            return getParameters().getRaw().contains("--autostart");
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void applyAutostartBehaviour(javafx.stage.Stage primaryStage) {
        autostartSilent = isAutostartLaunch();
        AppLog.info("启动完成，自动启动模式=" + autostartSilent);

        if (!autostartSilent) {
            return;
        }

        // 登录启动时也显示主界面，并提到最前，避免被开机时打开的其他窗口挡住。
        Platform.runLater(() -> {
            primaryStage.setIconified(false);
            primaryStage.show();
            primaryStage.toFront();
            primaryStage.requestFocus();
        });
    }

    private void checkStartupItemHealth() {
        WindowsStartupManager.StartupStatus status = trayController.startupStatus();

        if (status.isBroken()) {
            AppLog.warn("登录启动项已失效：" + status.detail());
            statusCenter.warning(
                    "登录启动项已失效（" + status.detail() + "）",
                    this::repairStartupItem
            );
            return;
        }

        if (status.isEnabled()) {
            AppLog.info("登录启动项正常：" + status.detail());
        }
    }

    private void repairStartupItem() {
        try {
            WindowsStartupManager.StartupStatus status = trayController.repairStartup();
            statusCenter.success("登录启动项已修复：" + status.detail());
            AppLog.info("登录启动项已修复");
        } catch (Exception e) {
            AppLog.error("修复登录启动项失败", e);
            statusCenter.error("修复登录启动项失败：" + describeError(e));
        }
    }

    /** 打开 %APPDATA%\StudyTimer（计划、提醒、状态文件所在位置），方便备份。 */
    private void openDataFolder() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            statusCenter.error("无法定位数据目录（%APPDATA%\\StudyTimer）");
            return;
        }
        Path directory = Path.of(appData, "StudyTimer");
        try {
            java.nio.file.Files.createDirectories(directory);
            java.awt.Desktop.getDesktop().open(directory.toFile());
        } catch (Exception e) {
            AppLog.warn("打开数据目录失败：" + describeError(e));
            statusCenter.info("数据目录：" + directory);
        }
    }

    private void exportData() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("导出 StudyTimer 数据");
        chooser.setInitialFileName("StudyTimer-数据-"
                + java.time.LocalDate.now() + ".zip");
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("ZIP 数据包", "*.zip"));
        java.io.File file = chooser.showSaveDialog(mainStage);
        if (file == null) {
            return;
        }
        try {
            int count = new com.studytimer.service.DataTransferService().exportTo(file.toPath());
            statusCenter.success("已导出 " + count + " 个数据文件：" + file.getName());
            AppLog.info("数据已导出：" + file);
        } catch (Exception e) {
            AppLog.error("导出数据失败", e);
            statusCenter.error("导出数据失败：" + describeError(e));
        }
    }

    private void importData() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("导入 StudyTimer 数据");
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("ZIP 数据包", "*.zip"));
        java.io.File file = chooser.showOpenDialog(mainStage);
        if (file == null) {
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "导入会替换当前所有计划、提醒和设置。\n"
                        + "当前数据会先自动备份到数据文件夹的 backups 目录。\n\n"
                        + "导入需要重启 StudyTimer，现在退出程序吗？",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("导入数据");
        confirm.setHeaderText("导入 " + file.getName());
        confirm.initOwner(mainStage);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        try {
            new com.studytimer.service.DataTransferService().stageImport(file.toPath());
            AppLog.info("已暂存导入数据，退出后下次启动时应用：" + file);
            Alert done = new Alert(Alert.AlertType.INFORMATION,
                    "数据已准备好，程序将退出。请重新打开 StudyTimer 完成导入。",
                    ButtonType.OK);
            done.setTitle("导入数据");
            done.setHeaderText(null);
            done.initOwner(mainStage);
            done.showAndWait();
            exitApplication();
        } catch (Exception e) {
            AppLog.error("导入数据失败", e);
            statusCenter.error("导入数据失败：" + describeError(e));
        }
    }

    private void showAbout() {
        String ffmpeg = audioPlaybackCoordinator != null
                && audioPlaybackCoordinator.isVideoAudioExtractionAvailable()
                ? "已找到（视频只发送音轨）" : "未找到（视频将整个上传）";
        String phone = polarBellService == null ? "未知"
                : String.join(", ", polarBellService.getCapabilities());
        showDialog(Alert.AlertType.INFORMATION, "关于 StudyTimer",
                "StudyTimer 1.0\n\n"
                        + "Java：" + System.getProperty("java.version") + "\n"
                        + "ffmpeg：" + ffmpeg + "\n"
                        + "手机端能力：" + (phone.isBlank() ? "未连接" : phone));
    }

    /** 打开日志目录，方便用户反馈问题时直接附上日志。 */
    public void openLogFolder() {
        Path directory = AppLog.directory();
        if (directory == null) {
            if (statusCenter != null) {
                statusCenter.error("无法定位日志目录（%APPDATA%\\StudyTimer\\logs）");
            }
            return;
        }

        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(directory.toFile());
                return;
            }
        } catch (Exception e) {
            AppLog.warn("打开日志目录失败：" + describeError(e));
        }

        if (statusCenter != null) {
            statusCenter.info("日志目录：" + directory);
        }
    }


    @Override
    public void toggleTimer() {

        if (stages.isEmpty()) {
            showMessage("请至少添加一个时间阶段。");
            return;
        }

        focusTimerController.toggle();

        refreshButtonText();

        saveFocusSessionState();
    }
    @Override
    public void refreshButtonText() {
        focusTimerPane.refreshButtonText();
    }

    @Override
    public void updateClock(long remainingSeconds, int currentIndex, int total) {
        focusTimerPane.updateClock(remainingSeconds, currentIndex, total);
    }

    private void recordStudySession(String stageName, long planned, long elapsed, boolean completed) {
        var plan = focusPlanPane.getFocusPlanController().getActivePlanInfo();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        var session = new com.studytimer.model.StudySession(
                now.toLocalDate().toString(),
                now.withNano(0).toString(),
                plan == null ? null : plan.getId(),
                plan == null ? "" : plan.getName(),
                stageName,
                planned,
                elapsed,
                completed,
                com.studytimer.service.StudySessionStore.isFocusStage(stageName));
        try {
            if (!studySessionStore.add(session)) {
                return;
            }
            if (studySessionStore.markGoalReachedIfNew(now.toLocalDate())) {
                statusCenter.success("今日专注目标已达成："
                        + studySessionStore.getDailyGoalMinutes() + " 分钟");
                AppLog.info("今日专注目标已达成");
            }
        } catch (IOException e) {
            AppLog.error("保存学习记录失败", e);
            statusCenter.warning("保存学习记录失败：" + describeError(e));
        }
        refreshGoalBadge();
        if (goalStrip != null) {
            goalStrip.refresh();
        }
    }

    private void applyFinishSound(com.studytimer.service.UiSettingsStore.Settings settings) {
        audioPlaybackCoordinator.setFinishSound(
                settings.finishAudioId, settings.finishSeconds, settings.finishVolume);
    }

    /** 计划每遍的专注分钟（休息阶段不算）；计划不存在或读取失败时为 0。 */
    private int planFocusMinutes(String planId) {
        try {
            int total = 0;
            for (TimerStage stage : focusPlanPane.getFocusPlanController().loadPlan(planId)) {
                if (com.studytimer.service.StudySessionStore.isFocusStage(stage.getName())) {
                    total += Math.max(0, stage.getMinutes());
                }
            }
            return total;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private void refreshGoalBadge() {
        if (goalBadge == null) {
            return;
        }
        long seconds = studySessionStore.focusSeconds(java.time.LocalDate.now());
        int goal = studySessionStore.getDailyGoalMinutes();
        long minutes = seconds / 60;
        goalBadge.setText(goal > 0
                ? "今日 " + minutes + " / " + goal + " 分钟"
                : "今日 " + minutes + " 分钟");
        boolean reached = goal > 0 && minutes >= goal;
        goalBadge.getStyleClass().removeAll("status-neutral", "status-success");
        goalBadge.getStyleClass().add(reached ? "status-success" : "status-neutral");
    }

    private void showSplash(com.studytimer.service.SplashSettingsStore.Settings settings, boolean autostart) {
        if (overlayHost == null) {
            return;
        }
        if (splashOverlay != null) {
            splashOverlay.close(null);
        }
        splashOverlay = new com.studytimer.ui.SplashOverlay(settings, autostart, audioPlaybackCoordinator,
                reason -> statusCenter.warning("开屏声音未在手机播放，已改由电脑播放：" + reason));
        overlayHost.getChildren().add(splashOverlay);
        splashOverlay.start();
    }

    private void setupMiniTimer(Scene scene) {
        miniTimerWindow = new com.studytimer.ui.MiniTimerWindow(
                miniTimerSettingsStore,
                scene.getStylesheets(),
                () -> {
                    toggleTimer();
                    syncMiniTimer();
                },
                () -> {
                    skipTimer();
                    refreshButtonText();
                    saveFocusSessionState();
                    syncMiniTimer();
                },
                this::restoreMainWindow);
        mainStage.iconifiedProperty().addListener((obs, old, v) -> syncMiniTimer());
        mainStage.showingProperty().addListener((obs, old, v) -> syncMiniTimer());
        // 界面标签由计时线程经 runLater 更新，这里定时同步到悬浮窗
        miniTimerSync = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(500), e -> syncMiniTimer()));
        miniTimerSync.setCycleCount(javafx.animation.Animation.INDEFINITE);
        miniTimerSync.play();
    }

    /** 主窗口最小化/隐藏且计时进行中（或已在显示时暂停）时显示悬浮窗。 */
    private void syncMiniTimer() {
        if (miniTimerWindow == null || mainStage == null) {
            return;
        }
        boolean mainHidden = !mainStage.isShowing() || mainStage.isIconified();
        boolean running = isTimerRunning();
        String stageText = focusTimerPane.getCurrentStageText();
        boolean finished = "计划已完成".equals(stageText);
        boolean show = miniTimerSettingsStore.get().enabled && mainHidden && !finished
                && (running || miniTimerWindow.isShowing());
        if (!show) {
            miniTimerWindow.hide();
            return;
        }
        miniTimerWindow.update(focusTimerPane.getTimeText(), stageText, running);
        miniTimerWindow.show();
    }

    private void openStats() {
        com.studytimer.ui.StatsDialog.show(mainStage, studySessionStore, this::refreshGoalBadge);
        refreshGoalBadge();
    }

    private void onFinished() {

        // 让手机播放提示音，不阻塞计时引擎
        audioPlaybackCoordinator.playFinishSound();

        focusTimerPane.showFinished();
    }

    /**
     * 异步连接 PolarBell 手机端
     */
    private void connectPolarBellAsync() {

        polarBellService.connectAsync(
                success -> System.out.println("PolarBell 连接检查成功"),
                error -> System.err.println(
                        "PolarBell 连接失败：" + error.getMessage()
                )
        );
    }

    /**
     * 统一处理音频协调器报告的异常。
     *
     * 区分三类情况：
     * 1. 手机端 App 没运行（Connection refused）：属于正常状态，用中性提示，自动消失；
     * 2. 电脑端播放失败：状态栏错误，可重试；
     * 3. 其它手机故障：状态栏警告。
     *
     * 所有情况都会写入日志文件。
     */
    private void handleAudioPlaybackError(String message, Exception error) {
        String label = message == null ? "音频播放失败" : message;
        String detail = describeError(error);
        AppLog.warn(label + "：" + detail);

        StatusCenter center = statusCenter;
        if (center == null) {
            return;
        }

        if (isPhoneUnavailable(error)) {
            // 每 5 秒的健康检查会反复触发，StatusCenter 内部会去重。
            center.info("手机端 PolarBell 未运行，已跳过手机播放");
            return;
        }

        if (label.startsWith("电脑音乐播放失败")) {
            center.error(label + "：" + detail);
            return;
        }

        center.warning(label + "：" + detail);
    }

    /** 提取最适合展示给用户的异常描述。 */
    private static String describeError(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        Throwable current = error;
        int guard = 0;
        while (current.getCause() != null && guard++ < 8) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    /**
     * 后台组件报错：写日志，并在状态栏提示。
     * UI 尚未构建完成时只写日志，不影响启动。
     */
    private void reportBackgroundError(String message, Throwable error) {
        AppLog.error(message, error);
        StatusCenter center = statusCenter;
        if (center != null) {
            center.warning(message + "：" + describeError(error));
        }
    }

    /**
     * 手机端 App 未运行时，端口没有监听，表现为 Connection refused。
     * 这不是程序缺陷，不需要用红色错误吓用户。
     */
    private static boolean isPhoneUnavailable(Throwable error) {
        Throwable current = error;
        int guard = 0;
        while (current != null && guard++ < 8) {
            if (current instanceof java.net.ConnectException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("Connection refused")
                    || message.contains("getsockopt"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 轻量提示入口。
     *
     * 历史上这里是模态弹窗，保存/切换/导出每次都要点"确定"，
     * 而且在全屏提醒期间弹窗会被盖住，看起来像卡死。
     * 现在改为状态栏提示并按语义分级：
     * - 失败/错误 → 红色；
     * - 操作前置条件（"请先暂停…"）→ 橙色；
     * - 其余（成功、已设置…）→ 中性，自动消失。
     */
    public void showMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (statusCenter == null) {
            showDialog(Alert.AlertType.INFORMATION, "提示", message);
            return;
        }

        if (message.contains("失败") || message.contains("错误")
                || message.contains("无法")) {
            statusCenter.error(message);
        } else if (message.startsWith("请") || message.contains("尚未")
                || message.contains("为空") || message.contains("至少")) {
            statusCenter.warning(message);
        } else {
            statusCenter.info(message);
        }
    }


    /**
     * 获取手机音频名称，用于历史版本差异展示
     */
    public String getPhoneAudioName(String audioId) {
        return polarBellService.getAudioName(audioId);
    }

    @Override
    public java.util.List<String> getHiddenPlanCategories() {
        return uiSettingsStore.get().hiddenPlanCategories;
    }

    @Override
    public void setHiddenPlanCategories(java.util.List<String> categories) {
        var s = uiSettingsStore.get();
        s.hiddenPlanCategories = new java.util.ArrayList<>(categories);
        uiSettingsStore.save(s);
    }

    /**
     * 将当前阶段列表同步到计时引擎
     */
    public void refreshTimerStages() {
        focusTimerController.setStages(List.copyOf(stages));
    }


    /**
     * 初始化计时状态持久化，并恢复上一次的状态。
     */
    private void initializeFocusSessionPersistence() {

        focusSessionStorage = new FocusSessionStorage();

        try {

            FocusSessionStorage.State saved =
                    focusSessionStorage.load();

            if (saved == null) {
                System.out.println("没有发现历史计时状态，使用默认状态");
                return;
            }

            // 检查状态文件版本
            if (saved.getSchemaVersion() != 1) {
                System.err.println("计时状态文件版本不兼容，忽略恢复");
                return;
            }

            // 确保状态属于当前计划
            String activePlanId =
                    focusPlanPane.getFocusPlanController().getActivePlanId();

            if (activePlanId == null ||
                    !activePlanId.equals(saved.getPlanId())) {

                System.out.println("历史计时状态不属于当前计划，忽略恢复");
                return;
            }

            // 恢复计时状态
            TimerEngine.Snapshot snapshot =
                    new TimerEngine.Snapshot(
                            saved.getCurrentIndex(),
                            saved.getRemainingSeconds(),
                            saved.isStarted(),
                            saved.isFinished()
                    );

            focusTimerController.restorePaused(snapshot);

            // 更新阶段名称
            if (saved.isFinished()) {

                focusTimerPane.setCurrentStageText("计划已完成");

            } else if (saved.isStarted()) {

                focusTimerPane.setCurrentStageText(
                        stages.get(saved.getCurrentIndex()).getName()
                );

            } else {

                focusTimerPane.resetStageLabel();
            }

            refreshButtonText();

            System.out.println(
                    "Focus Timer 状态恢复成功，剩余时间："
                            + saved.getRemainingSeconds()
                            + " 秒"
            );

        } catch (IOException | RuntimeException e) {

            // 恢复失败不能阻止整个程序启动
            System.err.println(
                    "Focus Timer 状态恢复失败，使用默认状态："
                            + e.getMessage()
            );
        }
    }


    /**
     * 启动后台定时保存。
     *
     * 每 5 秒在 JavaFX 线程中读取快照并保存。
     */
    private void startFocusSessionAutoSave() {

        if (focusSessionSaveScheduler != null) {
            focusSessionSaveScheduler.shutdownNow();
        }

        focusSessionSaveScheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {

                    Thread thread = new Thread(
                            r,
                            "focus-session-auto-save"
                    );

                    thread.setDaemon(true);
                    return thread;
                });

        focusSessionSaveScheduler.scheduleAtFixedRate(
                () -> {

                    try {

                        Platform.runLater(this::saveFocusSessionState);

                    } catch (IllegalStateException e) {

                        System.err.println(
                                "提交计时状态保存任务失败：" + e.getMessage()
                        );
                    }

                },
                5,
                5,
                TimeUnit.SECONDS
        );
    }


    /**
     * 保存当前 Focus Timer 状态。
     */
    @Override
    public void saveFocusSessionState() {

        if (focusSessionStorage == null ||
                focusTimerController == null ||
                focusPlanPane == null ||
                focusPlanPane.getFocusPlanController() == null) {
            return;
        }

        try {

            String planId =
                    focusPlanPane.getFocusPlanController().getActivePlanId();

            if (planId == null || planId.isBlank()) {
                return;
            }

            TimerEngine.Snapshot snapshot =
                    focusTimerController.createSnapshot();

            FocusSessionStorage.State state =
                    new FocusSessionStorage.State(
                            1,
                            planId,
                            snapshot.currentIndex(),
                            snapshot.remainingSeconds(),
                            snapshot.started(),
                            snapshot.finished(),
                            System.currentTimeMillis()
                    );

            focusSessionStorage.save(state);

        } catch (IOException | RuntimeException e) {

            System.err.println(
                    "保存 Focus Timer 状态失败：" + e.getMessage()
            );
        }
    }


    /**
     * 停止后台保存任务。
     */
    private void stopFocusSessionAutoSave() {

        if (focusSessionSaveScheduler != null) {

            focusSessionSaveScheduler.shutdownNow();

            focusSessionSaveScheduler = null;
        }
    }


    /** 前置条件类提示：不打断操作，橙色提示自动消失。 */
    public void showWarning(String message) {
        if (statusCenter != null) {
            statusCenter.warning(message);
            return;
        }
        showDialog(Alert.AlertType.WARNING, "提示", message);
    }

    /**
     * 错误提示。
     *
     * 写日志，并在状态栏以红色显示；不再用模态弹窗挡住用户，
     * 避免与全屏提醒窗口形成"程序卡死"的观感。
     * 真正致命的错误（例如启动时无法读取数据）仍使用弹窗。
     */
    public void showError(String message) {
        AppLog.warn("界面错误提示：" + message);
        if (statusCenter != null) {
            statusCenter.error(message);
            return;
        }
        showDialog(Alert.AlertType.ERROR, "错误", message);
    }

    public void showInformation(String message) {
        if (statusCenter != null) {
            statusCenter.success(message);
            return;
        }
        showDialog(Alert.AlertType.INFORMATION, "操作成功", message);
    }

    /**
     * 统一弹窗入口。
     *
     * 关键修复：提醒窗口是全屏且置顶的，此时再弹模态对话框会被盖住，
     * 用户看到的是"程序卡死"。因此提醒显示期间一律降级为状态栏消息。
     */
    private void showDialog(Alert.AlertType type, String title, String message) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showDialog(type, title, message));
            return;
        }

        if (routineReminderShowing && statusCenter != null) {
            if (type == Alert.AlertType.ERROR) {
                statusCenter.error(message);
            } else if (type == Alert.AlertType.WARNING) {
                statusCenter.warning(message);
            } else {
                statusCenter.info(message);
            }
            return;
        }

        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        if (mainStage != null && mainStage.isShowing()) {
            alert.initOwner(mainStage);
        }
        // Dialog 没有直接的 setAlwaysOnTop；窗口创建后再置顶，
        // 避免被"窗口置顶"的主界面或提醒窗口盖住。
        alert.setOnShown(event -> {
            if (alert.getDialogPane().getScene() != null
                    && alert.getDialogPane().getScene().getWindow()
                    instanceof javafx.stage.Stage dialogStage) {
                dialogStage.setAlwaysOnTop(true);
            }
        });
        alert.showAndWait();
    }

    private void enqueueRoutineReminder(RoutineReminderScheduler.TriggeredReminder triggered) {
        if (routineReminderWindow == null) {
            return;
        }
        // 同一个提醒已在队列中时不重复排队（例如补发与稍后提醒同时到期）。
        String id = triggered.reminder().getId();
        boolean queued = pendingRoutineReminders.stream()
                .anyMatch(item -> item.reminder().getId().equals(id));
        if (!queued) {
            pendingRoutineReminders.offer(triggered);
        }
        if (routineReminderShowing) {
            routineReminderWindow.setPendingCount(pendingRoutineReminders.size());
        }
        showNextRoutineReminder();
    }

    private void showNextRoutineReminder() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::showNextRoutineReminder);
            return;
        }
        if (routineReminderShowing || routineReminderWindow == null) {
            return;
        }
        RoutineReminderScheduler.TriggeredReminder next = pendingRoutineReminders.poll();
        if (next == null) {
            if (stageChangedDuringReminder != null && statusCenter != null) {
                statusCenter.info("提醒期间计时已切换到：" + stageChangedDuringReminder);
            }
            stageChangedDuringReminder = null;
            return;
        }
        routineReminderShowing = true;
        routineReminderWindow.setOnDismissed(() -> {
            routineReminderShowing = false;
            showNextRoutineReminder();
        });
        try {
            routineReminderWindow.show(next);
            routineReminderWindow.setPendingCount(pendingRoutineReminders.size());
        } catch (RuntimeException error) {
            routineReminderShowing = false;
            AppLog.error("显示 Routine Reminder 失败", error);
            showNextRoutineReminder();
        }
    }

    @Override
    public void stop() {

        // 先停止定时保存，再保存最后一次准确状态。
        stopFocusSessionAutoSave();
        saveFocusSessionState();

        if (miniTimerSync != null) {
            miniTimerSync.stop();
        }
        if (miniTimerWindow != null) {
            miniTimerWindow.dispose();
        }
        if (lifeGuard != null) {
            lifeGuard.shutdown();
        }
        if (fxWatchdog != null) {
            fxWatchdog.close();
        }

        // 0. 先停止提醒调度，再关闭可能正在显示的提醒窗口。
        if (routineReminderScheduler != null) {
            routineReminderScheduler.close();
        }
        pendingRoutineReminders.clear();
        routineReminderShowing = false;
        if (routineReminderWindow != null) {
            routineReminderWindow.close();
        }

        // 1. 停止 ADB 后台检测，避免退出期间继续检测或重建转发
        if (adbForwardManager != null) {
            adbForwardManager.close();
        }

        // 2. 停止计时引擎
        // 如果停止过程触发 onStop()，手机停止播放任务会先进入通信队列
        if (focusTimerController != null) {
            focusTimerController.shutdown();
        }

        // 3. 关闭 PolarBell 服务
        // closeAsync() 会将客户端关闭任务排在此前已提交的通信任务之后
        if (polarBellService != null) {
            polarBellService.closeAsync();
        }

        // 4. 关闭电脑端音频服务
        if (reminderAudioService != null) {
            reminderAudioService.close();
        }
        if (audioService != null) {
            audioService.close();
        }

        // 5. 移除系统托盘图标
        if (trayController != null) {
            trayController.shutdown();
        }

        // 6. 停止唤醒服务并释放单实例锁。
        singleInstanceGuard.stop();
    }

    public static void main(String[] args) {

        installGlobalExceptionHandler();
        AppLog.info("StudyTimer 进程启动");

        if (!singleInstanceGuard.acquire()) {
            if (singleInstanceGuard.anotherInstanceDetected()) {
                singleInstanceGuard.signalExisting();
            }
            return;
        }

        try {
            launch(args);
        } finally {
            // 防止 JavaFX 启动阶段异常导致锁长期占用。
            singleInstanceGuard.stop();
            AppLog.info("StudyTimer 进程退出");
        }
    }

    /**
     * 全局未捕获异常兜底。
     *
     * 后台线程与 JavaFX 线程的异常都会走到这里：
     * 先落盘，再尽量在状态栏提示，绝不让异常静默消失。
     */
    private static void installGlobalExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            AppLog.error("未捕获异常（线程 " + thread.getName() + "）", error);
        });
    }

    // ==================== FocusPlanPane.Host ====================

    @Override
    public boolean isTimerRunning() {
        return focusTimerController != null && focusTimerController.isRunning();
    }

    @Override
    public void resetTimer() {
        focusTimerController.reset();
    }

    @Override
    public void resetCurrentStageLabel() {
        focusTimerPane.resetStageLabel();
    }

    @Override
    public String errorText(Throwable error) {
        return describeError(error);
    }

    // ==================== FocusTimerPane.Host ====================

    @Override
    public void skipTimer() {
        focusTimerController.skip();
    }

    @Override
    public void setPlanDirty(boolean dirty) {
        if (focusPlanPane != null) {
            focusPlanPane.setPlanDirty(dirty);
        }
    }

    @Override
    public boolean isPhoneConnected() {
        return polarBellService.getConnectionState()
                == PolarBellConnectionState.CONNECTED;
    }

    @Override
    public void addPhoneStateListener(Consumer<PolarBellConnectionState> listener) {
        polarBellService.addStateListener(listener);
    }

    @Override
    public void getPhoneAudioListAsync(
            Consumer<java.util.List<PhoneAudio>> onSuccess,
            Consumer<Exception> onError) {
        polarBellService.getAudioListAsync(onSuccess, onError);
    }

    // ==================== TrayController.Host ====================

    @Override
    public void exitApplication() {
        Platform.exit();
    }

    /** 托盘“退出程序”：生命保障开启时先过简化障碍。 */
    @Override
    public void requestExitFromTray() {
        if (lifeGuard != null) {
            var settings = lifeGuard.getSettings();
            if (settings.enabled && !com.studytimer.ui.LifeGuardDialogs.askSimple(
                    mainStage != null && mainStage.isShowing() ? mainStage : null, settings, "退出 StudyTimer")) {
                return;
            }
        }
        exitApplication();
    }

    private void openLifeGuardSettings() {
        if (lifeGuard != null) {
            com.studytimer.ui.LifeGuardDialogs.showSettings(mainStage, lifeGuard, this);
        }
    }

    /** 主动计划已开始、未结束，但当前处于暂停。 */
    private boolean isActivePlanPaused() {
        if (focusTimerController == null || focusTimerController.isRunning()) {
            return false;
        }
        TimerEngine.Snapshot s = focusTimerController.createSnapshot();
        return s.started() && !s.finished();
    }

    @Override
    public void notifySuccess(String message) {
        statusCenter.success(message);
    }

    @Override
    public void notifyInfo(String message) {
        statusCenter.info(message);
    }

    @Override
    public void notifyError(String message) {
        statusCenter.error(message);
    }
}
