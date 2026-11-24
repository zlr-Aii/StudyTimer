package com.studytimer.ui;

import com.studytimer.service.LifeGuardStore;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;

/**
 * 生命保障的全屏休息窗口：不能关闭或跳过，只能推迟（次数由调用方控制）。
 */
public final class LifeGuardRestWindow {

    private Stage stage;
    private Label countdown;
    private Label hint;
    private Button postponeButton;
    private boolean closingByCode;

    /** hint 为休息画面中间的提示文字（来自生命保障设置）。 */
    public void show(int restMinutes, String hintText, int postponesLeft, Runnable onPostpone) {
        if (stage == null) {
            build(onPostpone);
        }
        Label title = (Label) ((VBox) stage.getScene().getRoot()).getChildren().get(0);
        title.setText("生命保障：休息 " + restMinutes + " 分钟");
        setHint(hintText);
        setPostponesLeft(postponesLeft);
        if (!stage.isShowing()) {
            stage.show();
            stage.setFullScreen(true);
        }
        stage.toFront();
    }

    /** 更新中间提示文字。 */
    public void setHint(String hintText) {
        if (hint == null) {
            return;
        }
        String text = hintText == null || hintText.isBlank()
                ? LifeGuardStore.DEFAULT_REST_HINT : hintText.strip();
        hint.setText(text);
        hint.setVisible(!text.isEmpty());
        hint.setManaged(!text.isEmpty());
    }

    public void update(long remainingMs) {
        if (countdown == null) {
            return;
        }
        long seconds = (remainingMs + 999) / 1000;
        countdown.setText(String.format("%02d:%02d", seconds / 60, seconds % 60));
    }

    public void setPostponesLeft(int left) {
        if (postponeButton == null) {
            return;
        }
        postponeButton.setDisable(left <= 0);
        postponeButton.setText(left > 0 ? "推迟 5 分钟（还剩 " + left + " 次）" : "本轮已不能推迟");
    }

    public boolean isShowing() {
        return stage != null && stage.isShowing();
    }

    public void close() {
        if (stage != null) {
            closingByCode = true;
            stage.close();
            closingByCode = false;
            stage = null;
        }
    }

    private void build(Runnable onPostpone) {
        stage = new Stage();
        stage.setTitle("生命保障 - 休息");
        stage.setAlwaysOnTop(true);
        stage.setFullScreenExitHint("");
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);

        Label title = new Label();
        title.getStyleClass().add("lifeguard-title");
        countdown = new Label("--:--");
        countdown.getStyleClass().add("lifeguard-countdown");
        hint = new Label(LifeGuardStore.DEFAULT_REST_HINT);
        hint.getStyleClass().add("lifeguard-hint");
        hint.setWrapText(true);
        hint.setTextAlignment(TextAlignment.CENTER);
        hint.setAlignment(Pos.CENTER);
        hint.setMaxWidth(900);

        postponeButton = new Button();
        postponeButton.getStyleClass().add("lifeguard-button");
        postponeButton.setOnAction(e -> onPostpone.run());

        VBox root = new VBox(26, title, countdown, hint, postponeButton);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(40));
        root.getStyleClass().add("lifeguard-fullscreen");

        // 场景尺寸交给全屏窗口决定：按屏幕像素建场景在缩放显示器上会被拉伸而显模糊
        Scene scene = new Scene(root, 1024, 768);
        var css = LifeGuardRestWindow.class.getResource("/com/studytimer/ui/reminder.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            if (!closingByCode) {
                e.consume();
            }
        });
        stage.fullScreenProperty().addListener((obs, old, full) -> {
            if (!full && stage != null && stage.isShowing() && !closingByCode) {
                stage.setFullScreen(true);
            }
        });
    }
}
