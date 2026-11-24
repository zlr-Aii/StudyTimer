package com.studytimer.ui;

import com.studytimer.model.PlanInfo;
import com.studytimer.model.StudySession;
import com.studytimer.service.AppLog;
import com.studytimer.service.LongTermGoalProgress;
import com.studytimer.service.LongTermGoalStore;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * 主界面顶部的长期目标横条：可收起，展开时横向排列目标卡片，点击卡片在对话框中编辑。
 */
public final class LongTermGoalStrip extends VBox {

    private final LongTermGoalStore store;
    private final Supplier<List<StudySession>> records;
    private final Supplier<List<PlanInfo>> plans;
    private final ToIntFunction<String> focusMinutes;
    private final Consumer<String> notifier;

    private final BooleanProperty expanded = new SimpleBooleanProperty(true);
    private final Label summary = new Label();
    private final Button toggle = new Button();
    private final HBox cards = new HBox(10);
    private final ScrollPane scroll = new ScrollPane(cards);

    /**
     * @param focusMinutes 计划 id → 每遍专注分钟（计划不存在时 0）
     * @param notifier     状态栏提示（目标达成时）
     */
    public LongTermGoalStrip(LongTermGoalStore store, Supplier<List<StudySession>> records,
                             Supplier<List<PlanInfo>> plans, ToIntFunction<String> focusMinutes,
                             Consumer<String> notifier) {
        this.store = store;
        this.records = records;
        this.plans = plans;
        this.focusMinutes = focusMinutes;
        this.notifier = notifier;
        getStyleClass().addAll("surface-card", "goal-strip");

        // 收起/展开按钮放在顶部栏（getToggleButton），横条本身只放卡片
        toggle.getStyleClass().add("header-toggle");
        toggle.setOnAction(e -> expanded.set(!expanded.get()));

        cards.setPadding(new Insets(2, 2, 2, 2));
        scroll.setFitToHeight(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("goal-scroll");
        // 卡片始终完整显示：被挤压时让下方面板变矮，而不是裁掉卡片
        scroll.setMinHeight(Region.USE_PREF_SIZE);
        setMinHeight(Region.USE_PREF_SIZE);

        setPadding(new Insets(10, 14, 10, 14));
        getChildren().add(scroll);

        expanded.addListener((obs, o, n) -> applyExpanded());
        applyExpanded();

        // 每分钟刷新一次（跨天、计划修改后的每遍分钟）
        Timeline timeline = new Timeline(new KeyFrame(Duration.minutes(1), e -> refresh()));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    public BooleanProperty expandedProperty() {
        return expanded;
    }

    /** 放在顶部栏的收起/展开按钮。 */
    public Button getToggleButton() {
        return toggle;
    }

    /** 新建目标（设置菜单）。 */
    public void createGoal() {
        edit(null);
    }

    public void refresh() {
        LocalDate today = LocalDate.now();
        List<StudySession> all = records.get();
        cards.getChildren().clear();
        int active = 0;
        int behind = 0;
        for (LongTermGoalStore.Goal goal : store.goals()) {
            LongTermGoalProgress p = LongTermGoalProgress.compute(goal, all,
                    goal.planId == null ? 0 : focusMinutes.applyAsInt(goal.planId), today);
            if (goal.isCompleted() != p.reached()) {
                // 达到总小时数自动完成；改大总小时数后恢复为进行中
                goal.completedDate = p.reached() ? today.toString() : null;
                try {
                    store.put(goal);
                    if (p.reached()) {
                        AppLog.info("长期目标已完成：" + goal.name);
                        notifier.accept("长期目标“" + goal.name + "”已完成：" + goal.totalHours + " 小时");
                    }
                } catch (IOException e) {
                    AppLog.error("保存长期目标失败", e);
                }
            }
            if (!goal.isCompleted()) {
                active++;
                if (p.focusMinutesPerRun() > 0 && p.aheadSeconds() < -60) {
                    behind++;
                }
            }
            cards.getChildren().add(card(goal, p));
        }
        if (cards.getChildren().isEmpty()) {
            Label empty = new Label("还没有长期目标。在“设置 → 新建长期目标…”里选一个 Focus Plan，按学习记录自动累计进度。");
            empty.getStyleClass().add("hint-text");
            cards.getChildren().add(empty);
        }
        summary.setText(store.goals().isEmpty() ? "还没有长期目标"
                : active + " 个进行中" + (behind > 0 ? "，" + behind + " 个落后" : ""));
        applyExpanded();
    }

    private VBox card(LongTermGoalStore.Goal goal, LongTermGoalProgress p) {
        Label name = new Label(goal.name);
        name.getStyleClass().add("goal-name");
        Label status = new Label();
        status.getStyleClass().add("status-badge");
        if (goal.isCompleted()) {
            status.setText("已完成 ✓");
            status.getStyleClass().add("status-success");
        } else if (p.focusMinutesPerRun() <= 0) {
            status.setText("计划无效");
            status.getStyleClass().add("status-warning");
        } else {
            long ahead = p.aheadSeconds();
            if (ahead >= 0) {
                status.setText(ahead < 60 ? "正好" : "超前 " + hours(ahead));
                status.getStyleClass().add("status-success");
            } else {
                status.setText("落后 " + hours(-ahead));
                status.getStyleClass().add("status-warning");
            }
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, name, spacer, status);
        top.setAlignment(Pos.CENTER_LEFT);

        Label motivation = new Label(goal.motivation);
        motivation.getStyleClass().add("hint-text");
        motivation.setManaged(!goal.motivation.isEmpty());
        motivation.setVisible(!goal.motivation.isEmpty());

        ProgressBar bar = new ProgressBar(p.fraction());
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(10);

        Label amount = new Label(hours(p.doneSeconds()) + " / " + goal.totalHours + " 小时（"
                + Math.round(p.fraction() * 100) + "%）");
        amount.getStyleClass().add("goal-amount");

        Label detail = new Label(detailText(goal, p));
        detail.getStyleClass().add("hint-text");

        VBox box = new VBox(6, top, motivation, bar, amount, detail);
        box.getStyleClass().add("goal-card");
        if (goal.isCompleted()) {
            box.getStyleClass().add("goal-card-done");
        }
        box.setPrefWidth(300);
        box.setMinWidth(260);
        box.setPadding(new Insets(10, 12, 10, 12));
        Tooltip.install(box, new Tooltip(tooltipText(goal, p)));
        box.setOnMouseClicked(e -> edit(goal));
        return box;
    }

    private static String detailText(LongTermGoalStore.Goal goal, LongTermGoalProgress p) {
        if (goal.isCompleted()) {
            return "完成于 " + goal.completedDate;
        }
        if (p.focusMinutesPerRun() <= 0) {
            return "对应的计划不存在或没有专注阶段";
        }
        String today = p.todayScheduled()
                ? "今天 " + p.todayRuns() + " / " + p.timesPerDay() + " 遍"
                : "今天不安排";
        return today + " · 剩余 " + p.remainingRuns() + " 遍";
    }

    private static String tooltipText(LongTermGoalStore.Goal goal, LongTermGoalProgress p) {
        StringBuilder sb = new StringBuilder();
        sb.append("每遍专注 ").append(p.focusMinutesPerRun()).append(" 分钟，每天 ")
                .append(goal.timesPerDay).append(" 遍，每周 ").append(weekdays(goal)).append('\n');
        sb.append("开始于 ").append(goal.startDate).append("，到昨天应完成 ")
                .append(hours(p.expectedSeconds())).append(" 小时\n");
        if (p.estimatedFinish() != null && !goal.isCompleted()) {
            sb.append("按计划节奏预计 ")
                    .append(p.estimatedFinish().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                    .append(" 完成\n");
        }
        sb.append("点击编辑");
        return sb.toString();
    }

    private static String weekdays(LongTermGoalStore.Goal goal) {
        if (goal.weekdays.size() == 7) {
            return "每天";
        }
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        StringBuilder sb = new StringBuilder();
        for (int d : goal.weekdays) {
            sb.append(names[d - 1]);
        }
        return sb.toString();
    }

    private static String hours(long seconds) {
        double h = seconds / 3600.0;
        return h >= 100 ? String.valueOf(Math.round(h)) : String.format("%.1f", h);
    }

    private void edit(LongTermGoalStore.Goal goal) {
        var owner = getScene() == null ? null : getScene().getWindow();
        LongTermGoalDialog.show(owner, goal, plans.get(), focusMinutes).ifPresent(result -> {
            try {
                if (result.deleted()) {
                    store.delete(result.goal().id);
                    AppLog.info("删除长期目标：" + result.goal().name);
                } else {
                    LongTermGoalStore.Goal saved = result.goal();
                    store.put(saved);
                    AppLog.info("保存长期目标：" + saved.name);
                }
            } catch (IOException e) {
                AppLog.error("保存长期目标失败", e);
                notifier.accept("保存长期目标失败：" + e.getMessage());
            }
            expanded.set(true);
            refresh();
        });
    }

    private void applyExpanded() {
        boolean open = expanded.get();
        setVisible(open);
        setManaged(open);
        toggle.setText(open ? "长期目标 ︿" : "长期目标 ﹀");
        toggle.setTooltip(new Tooltip((open ? "收起长期目标" : "展开长期目标")
                + (summary.getText().isEmpty() ? "" : "（" + summary.getText() + "）")));
    }
}
