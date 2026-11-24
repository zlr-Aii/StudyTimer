package com.studytimer.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 主界面：顶部栏 + 左侧 Focus Plan / 右侧日常提醒两个可各自收起的面板。
 * 只负责布局，不包含业务逻辑。
 */
public final class MainView extends BorderPane {

    private static final double REMINDER_PREF_WIDTH = 420;
    private static final double REMINDER_MIN_WIDTH = 360;
    private static final double FOCUS_MIN_WIDTH = 480;

    private final CollapsiblePanel focusPanel;
    private final CollapsiblePanel reminderPanel;
    private final HBox header;
    private final HBox body;
    private final VBox center;

    /**
     * @param headerTools 紧挨在 StudyTimer 标题右侧的设置类按钮
     * @param statusArea  顶部栏右侧的状态区
     * @param windowControls 顶部栏最右侧的最小化/最大化/关闭按钮（可为 null）
     */
    public MainView(Node[] headerTools, Node statusArea, Node windowControls,
                    Node focusContent, Node reminderContent) {
        getStyleClass().add("app-root");

        Label logo = new Label("StudyTimer");
        logo.getStyleClass().add("app-logo");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header = new HBox(8);
        header.getStyleClass().add("app-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 0, 16));
        header.setMinHeight(44);
        header.getChildren().add(logo);
        header.getChildren().addAll(headerTools);
        header.getChildren().addAll(spacer, statusArea);
        if (windowControls != null) {
            HBox.setMargin(windowControls, new Insets(0, 0, 0, 12));
            header.getChildren().add(windowControls);
        }
        setTop(header);

        focusPanel = new CollapsiblePanel("Focus Plan", focusContent, true);
        reminderPanel = new CollapsiblePanel("Routine Reminder", reminderContent, false);

        body = new HBox(12, focusPanel, reminderPanel);
        body.setFillHeight(true);
        VBox.setVgrow(body, Priority.ALWAYS);
        center = new VBox(12, body);
        center.setPadding(new Insets(8, 12, 12, 12));
        setCenter(center);

        focusPanel.expandedProperty().addListener((obs, o, n) -> applyWidths());
        reminderPanel.expandedProperty().addListener((obs, o, n) -> applyWidths());
        applyWidths();
    }

    /** 在 Focus Plan / Routine Reminder 上方放一条通栏（长期目标）。 */
    public void setTopStrip(Node strip) {
        center.getChildren().setAll(strip, body);
    }

    /** 收起的一侧整块隐藏（不显示窄栏），由另一侧右上角按钮或设置菜单重新展开。 */
    public void setReminderShown(boolean shown) {
        reminderPanel.setExpanded(shown);
    }

    public boolean isReminderShown() {
        return reminderPanel.isExpanded();
    }

    /** 顶部栏，同时充当无边框窗口的标题栏（拖动区域）。 */
    public HBox getHeader() {
        return header;
    }

    public CollapsiblePanel getFocusPanel() {
        return focusPanel;
    }

    public CollapsiblePanel getReminderPanel() {
        return reminderPanel;
    }

    /** 收起的面板变成窄栏，展开的面板分配剩余宽度。 */
    private void applyWidths() {
        boolean focusOpen = focusPanel.isExpanded();
        boolean reminderOpen = reminderPanel.isExpanded();

        if (!focusOpen && !reminderOpen) {
            // 不允许两边同时收起
            focusPanel.setExpanded(true);
            return;
        }

        // 收起的一侧整块隐藏；另一侧右上角按钮改为“展开另一侧”
        focusPanel.setVisible(focusOpen);
        focusPanel.setManaged(focusOpen);
        reminderPanel.setVisible(reminderOpen);
        reminderPanel.setManaged(reminderOpen);

        if (reminderOpen) {
            focusPanel.setCornerButton("«", "收起 Focus Plan", () -> focusPanel.setExpanded(false));
        } else {
            focusPanel.setCornerButton("«", "展开 Routine Reminder", () -> reminderPanel.setExpanded(true));
        }
        if (focusOpen) {
            reminderPanel.setCornerButton("»", "收起 Routine Reminder", () -> reminderPanel.setExpanded(false));
        } else {
            reminderPanel.setCornerButton("»", "展开 Focus Plan", () -> focusPanel.setExpanded(true));
        }

        if (focusOpen) {
            fill(focusPanel, FOCUS_MIN_WIDTH);
        }

        if (!reminderOpen) {
            // 隐藏
        } else if (focusOpen) {
            // 两侧都展开：都从固定基准宽度起步，窗口多出的宽度两边平分。
            fill(reminderPanel, REMINDER_MIN_WIDTH);
            reminderPanel.setPrefWidth(REMINDER_PREF_WIDTH);
            focusPanel.setPrefWidth(FOCUS_MIN_WIDTH);
        } else {
            fill(reminderPanel, REMINDER_MIN_WIDTH);
        }
    }

    private static void fill(Region region, double minWidth) {
        HBox.setHgrow(region, Priority.ALWAYS);
        region.setMinWidth(minWidth);
        region.setPrefWidth(Region.USE_COMPUTED_SIZE);
        region.setMaxWidth(Double.MAX_VALUE);
    }

    private static void fixed(Region region, double width) {
        HBox.setHgrow(region, Priority.NEVER);
        region.setMinWidth(width);
        region.setPrefWidth(width);
        region.setMaxWidth(width);
    }

    /** 把一个已有按钮（含其禁用状态和事件）映射成菜单项，避免重复业务逻辑。 */
    public static MenuItem menuItem(Button source) {
        return menuItem(source.getText(), source);
    }

    public static MenuItem menuItem(String text, Button source) {
        MenuItem item = new MenuItem(text);
        item.disableProperty().bind(source.disableProperty());
        item.setOnAction(e -> source.fire());
        return item;
    }
}
