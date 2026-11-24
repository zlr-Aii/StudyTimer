package com.studytimer.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 统一的状态消息中心。
 *
 * 取代过去"把红字永久写在标题栏"的做法：
 * - 消息分级，颜色不同；
 * - 普通消息自动消失，错误消息保留到用户处理或出现新消息；
 * - 相同消息短时间内去重，避免手机未连接时每 5 秒刷一次；
 * - 可带一个动作按钮（重试 / 查看日志 / 修复）；
 * - 悬停可查看最近消息历史。
 *
 * 该类可以从任意线程调用，内部会切回 JavaFX 线程。
 */
public final class StatusCenter {

    public enum Severity {
        INFO("message-info"),
        SUCCESS("message-success"),
        WARNING("message-warning"),
        ERROR("message-error");

        private final String styleClass;

        Severity(String styleClass) {
            this.styleClass = styleClass;
        }

        public String styleClass() {
            return styleClass;
        }
    }

    private static final java.util.List<String> SEVERITY_CLASSES = java.util.Arrays.stream(Severity.values())
            .map(Severity::styleClass)
            .toList();

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Duration DEDUPE_WINDOW = Duration.seconds(3);

    private final Label label;
    private final Button actionButton;
    private final PauseTransition clearTimer = new PauseTransition();

    private final Deque<String> history = new ArrayDeque<>();
    private final Tooltip historyTooltip = new Tooltip();

    private Severity currentSeverity;
    private String currentMessage = "";
    private Runnable currentAction;
    private long lastShownAt;

    public StatusCenter(Label label, Button actionButton) {
        this.label = Objects.requireNonNull(label, "label");
        this.actionButton = actionButton;

        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setTooltip(historyTooltip);

        clearTimer.setOnFinished(event -> clear());

        if (this.actionButton != null) {
            this.actionButton.setVisible(false);
            this.actionButton.setManaged(false);
            this.actionButton.setOnAction(event -> runCurrentAction());
        }
    }

    public void info(String message) {
        show(Severity.INFO, message, null);
    }

    public void success(String message) {
        show(Severity.SUCCESS, message, null);
    }

    public void warning(String message) {
        show(Severity.WARNING, message, null);
    }

    public void warning(String message, Runnable action) {
        show(Severity.WARNING, message, action);
    }

    public void error(String message) {
        show(Severity.ERROR, message, null);
    }

    public void error(String message, Runnable action) {
        show(Severity.ERROR, message, action);
    }

    /** 清除当前消息。 */
    public void clear() {
        runOnFxThread(() -> {
            clearTimer.stop();
            currentSeverity = null;
            currentMessage = "";
            currentAction = null;
            label.setText("");
            label.setVisible(false);
            label.setManaged(false);
            if (actionButton != null) {
                actionButton.setVisible(false);
                actionButton.setManaged(false);
            }
        });
    }

    public void show(Severity severity, String message, Runnable action) {
        Objects.requireNonNull(severity, "severity");
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            return;
        }

        runOnFxThread(() -> {
            long now = System.currentTimeMillis();
            boolean duplicate = severity == currentSeverity
                    && text.equals(currentMessage)
                    && now - lastShownAt < (long) DEDUPE_WINDOW.toMillis();

            if (duplicate) {
                // 重复消息不重新计时，避免"永远不消失"的实现噪音。
                return;
            }

            currentSeverity = severity;
            currentMessage = text;
            currentAction = action;
            lastShownAt = now;

            label.setText(text);
            label.getStyleClass().removeAll(SEVERITY_CLASSES);
            if (!label.getStyleClass().contains("status-message")) {
                label.getStyleClass().add("status-message");
            }
            label.getStyleClass().add(severity.styleClass());
            label.setVisible(true);
            label.setManaged(true);

            if (actionButton != null) {
                boolean hasAction = action != null;
                actionButton.setVisible(hasAction);
                actionButton.setManaged(hasAction);
                if (hasAction) {
                    actionButton.setText("处理");
                }
            }

            appendHistory(severity, text);

            clearTimer.stop();
            switch (severity) {
                case INFO -> scheduleClear(Duration.seconds(6));
                case SUCCESS -> scheduleClear(Duration.seconds(6));
                case WARNING -> scheduleClear(Duration.seconds(12));
                case ERROR -> {
                    // 错误保留，等待新消息或用户处理。
                }
            }
        });
    }

    private void scheduleClear(Duration delay) {
        clearTimer.setDuration(delay);
        clearTimer.playFromStart();
    }

    private void runCurrentAction() {
        Runnable action = currentAction;
        if (action == null) {
            return;
        }
        currentAction = null;
        // 先清掉当前消息，再执行动作；动作自身通常会给出新的结果提示，
        // 否则旧的"失效"提示会一直挂在那里。
        clear();
        try {
            action.run();
        } catch (RuntimeException error) {
            error("操作失败：" + (error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage()));
        }
    }

    private void appendHistory(Severity severity, String text) {
        history.addLast(LocalTime.now().format(CLOCK) + "  " + text);
        while (history.size() > 10) {
            history.removeFirst();
        }
        historyTooltip.setText(String.join(System.lineSeparator(), history));
    }

    private void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        try {
            Platform.runLater(action);
        } catch (IllegalStateException ignored) {
            // JavaFX 尚未启动或已经退出：消息无处显示，直接丢弃。
        }
    }

    /**
     * 把异常挂到状态栏，并写入日志。
     */
    public void reportError(String message, Throwable error, Consumer<Throwable> logger) {
        if (logger != null) {
            logger.accept(error);
        }
        String detail = error == null || error.getMessage() == null
                ? "" : "：" + error.getMessage();
        error(message + detail);
    }
}
