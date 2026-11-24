package com.studytimer.ui;

import com.studytimer.service.MiniTimerSettingsStore;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.List;

/**
 * 迷你悬浮计时窗：主窗口最小化或隐藏到托盘时置顶显示剩余时间和阶段，可暂停/继续、跳过。
 * 拖动移动位置；双击回到主窗口。只在 JavaFX 线程调用。
 */
public final class MiniTimerWindow {

    private final MiniTimerSettingsStore store;
    private final Runnable onRestore;
    private final List<String> stylesheets;

    private final Label timeLabel = new Label("--:--");
    private final Label stageLabel = new Label("");
    private final Button toggleButton = new Button();
    private final Button skipButton = new Button("\uE893");

    private Stage owner;
    private Stage stage;
    private double dragOffsetX;
    private double dragOffsetY;
    private boolean dragged;

    public MiniTimerWindow(MiniTimerSettingsStore store, List<String> stylesheets,
                           Runnable onToggle, Runnable onSkip, Runnable onRestore) {
        this.store = store;
        this.stylesheets = stylesheets;
        this.onRestore = onRestore;

        timeLabel.getStyleClass().add("mini-timer-time");
        stageLabel.getStyleClass().add("mini-timer-stage");
        stageLabel.setMaxWidth(150);

        toggleButton.getStyleClass().addAll("mini-timer-button", "mini-timer-primary");
        skipButton.getStyleClass().add("mini-timer-button");
        toggleButton.setOnAction(e -> onToggle.run());
        skipButton.setOnAction(e -> onSkip.run());
        skipButton.setTooltip(new Tooltip("跳过当前阶段"));
        setRunning(false);
    }

    private Stage ensureStage() {
        if (stage != null) {
            return stage;
        }
        // 透明的 UTILITY 宿主：让悬浮窗不占任务栏，且不随主窗口最小化而隐藏
        owner = new Stage(StageStyle.UTILITY);
        owner.setOpacity(0);
        owner.setWidth(1);
        owner.setHeight(1);
        owner.setX(-10000);
        owner.setY(-10000);
        owner.show();

        VBox text = new VBox(0, timeLabel, stageLabel);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox root = new HBox(8, text, toggleButton, skipButton);
        root.setAlignment(Pos.CENTER_LEFT);
        root.getStyleClass().add("mini-timer");
        Tooltip.install(root, new Tooltip("双击回到主窗口，拖动可移动"));

        root.setOnMousePressed(e -> {
            dragOffsetX = e.getScreenX() - stage.getX();
            dragOffsetY = e.getScreenY() - stage.getY();
            dragged = false;
        });
        root.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
            dragged = true;
        });
        root.setOnMouseReleased(e -> {
            if (dragged) {
                MiniTimerSettingsStore.Settings s = store.get();
                s.x = stage.getX();
                s.y = stage.getY();
                store.save(s);
            }
        });
        root.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !dragged) {
                onRestore.run();
            }
        });

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().addAll(stylesheets);

        stage = new Stage(StageStyle.TRANSPARENT);
        stage.initOwner(owner);
        stage.setTitle("StudyTimer");
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.setScene(scene);
        return stage;
    }

    public boolean isShowing() {
        return stage != null && stage.isShowing();
    }

    public void show() {
        Stage s = ensureStage();
        if (s.isShowing()) {
            return;
        }
        s.show();
        placeWindow(s);
    }

    public void hide() {
        if (stage != null) {
            stage.hide();
        }
    }

    private void placeWindow(Stage s) {
        MiniTimerSettingsStore.Settings settings = store.get();
        double w = s.getWidth();
        double h = s.getHeight();
        if (settings.x != null && settings.y != null) {
            // 保存的位置仍在某块屏幕上才使用（拔掉副屏后回到默认位置）
            List<Screen> screens = Screen.getScreensForRectangle(settings.x, settings.y, Math.max(w, 1), Math.max(h, 1));
            if (!screens.isEmpty()) {
                Rectangle2D b = screens.get(0).getVisualBounds();
                s.setX(clamp(settings.x, b.getMinX(), b.getMaxX() - w));
                s.setY(clamp(settings.y, b.getMinY(), b.getMaxY() - h));
                return;
            }
        }
        Rectangle2D b = Screen.getPrimary().getVisualBounds();
        s.setX(b.getMaxX() - w - 24);
        s.setY(b.getMaxY() - h - 24);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(v, Math.max(min, max)));
    }

    public void update(String time, String stageName, boolean running) {
        timeLabel.setText(time == null ? "--:--" : time);
        stageLabel.setText(stageName == null ? "" : stageName);
        setRunning(running);
    }

    public void setRunning(boolean running) {
        // Segoe Fluent Icons：E769 暂停，E768 播放
        toggleButton.setText(running ? "\uE769" : "\uE768");
        toggleButton.setTooltip(new Tooltip(running ? "暂停" : "继续"));
    }

    public void dispose() {
        if (stage != null) {
            stage.close();
        }
        if (owner != null) {
            owner.close();
        }
    }
}
