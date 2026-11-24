package com.studytimer.ui;

import com.studytimer.service.AppLog;
import com.studytimer.service.WindowsStartupManager;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Control;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.SystemTray;
import java.awt.TrayIcon;

/**
 * 系统托盘：托盘图标与右键菜单（打开窗口 / 登录启动 / 日志 / 退出）。
 *
 * 托盘本身不依赖业务对象：恢复窗口、退出、打开日志与状态提示都通过
 * {@link Host} 回调交给 Main，菜单项行为保持与原实现一致。
 */
public class TrayController {

    /** 宿主提供的跨模块能力，避免在托盘里复制业务逻辑。 */
    public interface Host {
        void restoreMainWindow();

        void exitApplication();

        /** 托盘菜单的“退出程序”，可能需要先通过生命保障的障碍。 */
        default void requestExitFromTray() {
            exitApplication();
        }

        void openLogFolder();

        void notifySuccess(String message);

        void notifyInfo(String message);

        void notifyError(String message);

        String errorText(Throwable error);
    }

    private final Host host;

    private SystemTray systemTray;
    private TrayIcon trayIcon;
    // 只有托盘图标确实添加成功后，关闭主窗口才隐藏到后台。
    private boolean trayAvailable;

    // 专门作为系统托盘菜单的宿主窗口
    private Stage trayOwnerStage;

    // 系统托盘右键菜单
    private Popup trayMenuPopup;

    // Windows 登录自动启动管理
    private final WindowsStartupManager windowsStartupManager =
            new WindowsStartupManager();

    public TrayController(Host host) {
        this.host = host;
    }

    /** 关闭主窗口时是否只隐藏到托盘。 */
    public boolean isAvailable() {
        return trayAvailable;
    }

    /** 开机自启动时的托盘气泡提示。 */
    public void showStartupBubble() {
        if (trayIcon != null) {
            trayIcon.displayMessage(
                    "StudyTimer",
                    "已在后台运行。双击托盘图标可打开窗口。",
                    TrayIcon.MessageType.INFO
            );
        }
    }

    /** 供启动项自检使用的状态查询与修复入口。 */
    public WindowsStartupManager.StartupStatus startupStatus() {
        return windowsStartupManager.status();
    }

    /** 开启或关闭登录启动（设置菜单与托盘菜单共用）。 */
    public void setStartupEnabled(boolean enable) throws java.io.IOException {
        if (enable) {
            windowsStartupManager.enable();
        } else {
            windowsStartupManager.disable();
        }
    }

    public WindowsStartupManager.StartupStatus repairStartup() throws java.io.IOException {
        windowsStartupManager.repair();
        return windowsStartupManager.status();
    }

    /**
     * 初始化系统托盘
     */
    public void setupSystemTray(Stage primaryStage) {

        if (!SystemTray.isSupported()) {
            System.out.println("当前环境不支持系统托盘；关闭主窗口时将退出程序");
            trayAvailable = false;
            return;
        }

        boolean trayIconAdded = false;
        try {
            systemTray = SystemTray.getSystemTray();
            createTrayOwnerStage();

            // 创建不带 AWT 原生菜单的托盘图标
            trayIcon = new TrayIcon(
                    createTrayImage(),
                    "StudyTimer"
            );

            trayIcon.setImageAutoSize(true);

            // 双击恢复主窗口
            trayIcon.addActionListener(event ->
                    Platform.runLater(host::restoreMainWindow)
            );

            // 右键显示 JavaFX 菜单
            trayIcon.addMouseListener(new java.awt.event.MouseAdapter() {

                @Override
                public void mouseReleased(java.awt.event.MouseEvent event) {
                    if (event.isPopupTrigger()
                            || event.getButton() == java.awt.event.MouseEvent.BUTTON3) {

                        double x = event.getXOnScreen();
                        double y = event.getYOnScreen();

                        Platform.runLater(() -> showTrayMenu(x, y));
                    }
                }
            });

            systemTray.add(trayIcon);
            trayIconAdded = true;
            trayAvailable = true;
            // 主窗口隐藏到托盘后不能让 JavaFX 因"没有可见窗口"而自动退出
            Platform.setImplicitExit(false);
            System.out.println("StudyTimer 系统托盘初始化成功");

        } catch (java.awt.AWTException | RuntimeException e) {
            System.err.println("系统托盘初始化失败：" + e.getMessage());
            trayAvailable = false;

            // 初始化可能在添加托盘图标之前或之后失败，统一清理部分资源。
            if (trayIconAdded && systemTray != null && trayIcon != null) {
                systemTray.remove(trayIcon);
            }
            trayIcon = null;
            systemTray = null;

            if (trayOwnerStage != null) {
                trayOwnerStage.close();
                trayOwnerStage = null;
            }
        }
    }

    /**
     * 显示 JavaFX 托盘菜单：出现在鼠标上方（类似 Windows 原生托盘菜单），
     * 点击菜单以外任何地方即关闭。
     */
    private void showTrayMenu(double screenX, double screenY) {

        if (trayMenuPopup != null && trayMenuPopup.isShowing()) {
            // 再次右键托盘图标：关闭菜单
            trayMenuPopup.hide();
            return;
        }

        try {
            openTrayMenu(screenX, screenY);
        } catch (RuntimeException e) {
            // 原生窗口状态异常时整体重建，保证下次右键仍可用
            AppLog.warn("托盘菜单显示失败，已重建：" + host.errorText(e));
            resetTrayMenu();
            try {
                openTrayMenu(screenX, screenY);
            } catch (RuntimeException again) {
                AppLog.error("托盘菜单重建后仍无法显示", again);
                resetTrayMenu();
            }
        }
    }

    private void resetTrayMenu() {
        Popup popup = trayMenuPopup;
        trayMenuPopup = null;
        if (popup != null) {
            try {
                popup.hide();
            } catch (RuntimeException ignored) {
            }
        }
        if (trayOwnerStage != null) {
            try {
                trayOwnerStage.close();
            } catch (RuntimeException ignored) {
            }
            trayOwnerStage = null;
        }
    }

    private void openTrayMenu(double screenX, double screenY) {
        if (trayOwnerStage == null) {
            createTrayOwnerStage();
        }

        {
            Popup popup = new Popup();
            trayMenuPopup = popup;
            popup.setAutoHide(true);
            popup.setAutoFix(true);
            popup.setHideOnEscape(true);
            popup.setOnHidden(e -> Platform.runLater(() -> {
                // 延后执行，避免在 Popup 显示/隐藏过程中途关闭宿主窗口
                if (trayMenuPopup == popup) {
                    trayMenuPopup = null;
                }
                if (trayMenuPopup == null && trayOwnerStage != null) {
                    trayOwnerStage.hide();
                }
            }));

            Button openButton = new Button("打开窗口");
            CheckBox startupCheckBox =
                    new CheckBox("登录 Windows 时启动");

            WindowsStartupManager.StartupStatus startupStatus =
                    windowsStartupManager.status();

            // 只有脚本真的指向当前工程时才算"已启用"
            startupCheckBox.setSelected(startupStatus.isEnabled());

            if (startupStatus.isBroken()) {
                startupCheckBox.setText("登录启动（已失效，点击修复）");
            }
            startupCheckBox.setTooltip(new Tooltip(startupStatus.detail()));

            startupCheckBox.setOnAction(e -> {

                boolean enable = startupCheckBox.isSelected();

                try {

                    if (enable) {

                        windowsStartupManager.enable();
                        startupCheckBox.setText("登录 Windows 时启动");
                        startupCheckBox.setTooltip(new Tooltip(
                                windowsStartupManager.status().detail()));
                        host.notifySuccess("已启用登录自动启动，下次开机将静默进入托盘");

                    } else {

                        windowsStartupManager.disable();
                        startupCheckBox.setText("登录 Windows 时启动");
                        startupCheckBox.setTooltip(new Tooltip("未设置登录启动"));
                        host.notifyInfo("已关闭登录自动启动");
                    }

                } catch (Exception ex) {

                    // 操作失败时恢复原来的勾选状态
                    startupCheckBox.setSelected(!enable);

                    AppLog.error("设置登录自动启动失败", ex);
                    host.notifyError("设置登录自动启动失败：" + host.errorText(ex));
                }
            });

            Button exitButton = new Button("退出程序");
            Button logButton = new Button("打开日志文件夹");

            for (Control item :
                    new Control[]{openButton, startupCheckBox, logButton, exitButton}) {
                item.getStyleClass().add("tray-menu-item");
                item.setMaxWidth(Double.MAX_VALUE);
                item.setFocusTraversable(false);
            }

            logButton.setOnAction(e -> {
                popup.hide();
                host.openLogFolder();
            });

            openButton.setOnAction(e -> {
                popup.hide();
                host.restoreMainWindow();
            });

            exitButton.setOnAction(e -> {
                popup.hide();
                host.requestExitFromTray();
            });

            Separator separator = new Separator();
            separator.getStyleClass().add("tray-menu-separator");

            VBox menu = new VBox(
                    2,
                    openButton,
                    startupCheckBox,
                    logButton,
                    separator,
                    exitButton
            );
            menu.getStyleClass().add("tray-menu");
            menu.setPrefWidth(210);

            var themeUrl = TrayController.class.getResource("/com/studytimer/ui/theme.css");
            if (themeUrl != null) {
                menu.getStylesheets().add(themeUrl.toExternalForm());
            }

            popup.getContent().add(menu);
        }
        Popup popup = trayMenuPopup;

        // 宿主放在鼠标处并获得焦点；它一失焦（点击别处）菜单就关闭
        trayOwnerStage.setX(screenX);
        trayOwnerStage.setY(screenY);
        trayOwnerStage.show();
        trayOwnerStage.toFront();
        trayOwnerStage.requestFocus();

        // 先显示再按实际尺寸定位：菜单右下角对准鼠标，即出现在鼠标左上方
        popup.show(trayOwnerStage, screenX, screenY);
        if (!popup.isShowing()) {
            return;
        }
        double width = popup.getWidth();
        double height = popup.getHeight();

        javafx.geometry.Rectangle2D bounds = javafx.stage.Screen
                .getScreensForRectangle(screenX, screenY, 1, 1)
                .stream().findFirst().orElse(javafx.stage.Screen.getPrimary())
                .getVisualBounds();

        double x = Math.max(bounds.getMinX(), Math.min(screenX - width, bounds.getMaxX() - width));
        double y = screenY - height;
        if (y < bounds.getMinY()) {
            y = screenY;
        }
        popup.setX(x);
        popup.setY(y);
    }

    /**
     * 绘制一个简单的托盘图标
     */
    private java.awt.Image createTrayImage() {

        try (java.io.InputStream in = TrayController.class.getResourceAsStream("icon-32.png")) {
            if (in != null) {
                return javax.imageio.ImageIO.read(in);
            }
        } catch (java.io.IOException e) {
            System.err.println("读取托盘图标失败：" + e.getMessage());
        }

        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(
                        32, 32,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB
                );

        java.awt.Graphics2D g = image.createGraphics();

        g.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
        );

        // 蓝色圆形背景
        g.setColor(new java.awt.Color(45, 120, 210));
        g.fillOval(2, 2, 28, 28);

        // 白色 S 字母
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 21));
        g.drawString("S", 9, 24);

        g.dispose();

        return image;
    }

    private void createTrayOwnerStage() {
        trayOwnerStage = new Stage();

        // UTILITY 窗口不会出现在任务栏；完全透明、1x1，只作为托盘菜单 Popup 的宿主
        trayOwnerStage.initStyle(StageStyle.UTILITY);
        trayOwnerStage.setOpacity(0);
        trayOwnerStage.setWidth(1);
        trayOwnerStage.setHeight(1);

        trayOwnerStage.setScene(
                new javafx.scene.Scene(
                        new javafx.scene.Group(),
                        javafx.scene.paint.Color.TRANSPARENT
                )
        );

        // 宿主失去焦点（点击了菜单以外的任何地方）时关闭菜单
        Stage owner = trayOwnerStage;
        owner.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (focused) {
                return;
            }
            Platform.runLater(() -> {
                Popup popup = trayMenuPopup;
                if (popup != null && popup.isShowing() && !owner.isFocused()) {
                    popup.hide();
                }
            });
        });
    }

    /** 退出前清理托盘资源。 */
    public void shutdown() {
        if (systemTray != null && trayIcon != null) {
            systemTray.remove(trayIcon);
        }
        trayAvailable = false;

        if (trayMenuPopup != null) {
            trayMenuPopup.hide();
        }

        if (trayOwnerStage != null) {
            trayOwnerStage.close();
        }
    }
}
