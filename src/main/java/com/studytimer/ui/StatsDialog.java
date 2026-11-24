package com.studytimer.ui;

import com.studytimer.service.StudySessionStore;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 学习统计：今日专注、连续天数、7/30 天柱状图、每日目标设置、导出 CSV。
 */
public final class StatsDialog {

    private final StudySessionStore store;
    private final Runnable onGoalChanged;
    private final Dialog<Void> dialog = new Dialog<>();
    private final Label todayValue = new Label();
    private final Label streakValue = new Label();
    private final Label totalValue = new Label();
    private final CategoryAxis xAxis = new CategoryAxis();
    private final NumberAxis yAxis = new NumberAxis();
    private final BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
    private int days = 7;

    private StatsDialog(Window owner, StudySessionStore store, Runnable onGoalChanged) {
        this.store = store;
        this.onGoalChanged = onGoalChanged;

        dialog.setTitle("学习统计");
        dialog.setHeaderText(null);
        if (owner != null) {
            dialog.initOwner(owner);
            if (owner.getScene() != null) {
                dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
            }
        }
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);

        HBox cards = new HBox(12,
                card("今日专注", todayValue),
                card("连续学习", streakValue),
                card("区间合计", totalValue));

        ToggleGroup range = new ToggleGroup();
        ToggleButton week = new ToggleButton("近 7 天");
        ToggleButton month = new ToggleButton("近 30 天");
        week.setToggleGroup(range);
        month.setToggleGroup(range);
        week.setSelected(true);
        week.getStyleClass().add("range-toggle");
        month.getStyleClass().add("range-toggle");
        range.selectedToggleProperty().addListener((obs, old, value) -> {
            if (value == null) {
                old.setSelected(true);
                return;
            }
            days = value == month ? 30 : 7;
            refresh();
        });

        Label goalLabel = new Label("每日目标（分钟，0 = 不设）：");
        Spinner<Integer> goalSpinner = new Spinner<>(0, 1440, store.getDailyGoalMinutes(), 15);
        goalSpinner.setEditable(true);
        goalSpinner.setPrefWidth(100);
        goalSpinner.valueProperty().addListener((obs, old, value) -> {
            try {
                store.setDailyGoalMinutes(value == null ? 0 : value);
                refresh();
                onGoalChanged.run();
            } catch (IOException e) {
                showError("保存每日目标失败", e);
            }
        });

        Button exportButton = new Button("导出 CSV…");
        exportButton.setOnAction(e -> exportCsv());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, week, month, spacer, goalLabel, goalSpinner, exportButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setCategoryGap(6);
        chart.setPrefHeight(300);
        chart.getStyleClass().add("stats-chart");
        yAxis.setLabel("分钟");
        yAxis.setForceZeroInRange(true);

        Label hint = new Label("名称含“休息”的阶段不计入专注；不足 10 秒的阶段不记录。");
        hint.getStyleClass().add("hint-text");

        VBox content = new VBox(12, cards, toolbar, chart, hint);
        content.setPadding(new Insets(12));
        content.setPrefWidth(720);
        dialog.getDialogPane().setContent(content);

        refresh();
    }

    public static void show(Window owner, StudySessionStore store, Runnable onGoalChanged) {
        new StatsDialog(owner, store, onGoalChanged).dialog.show();
    }

    private static VBox card(String title, Label value) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("muted-text");
        value.getStyleClass().add("stats-value");
        VBox box = new VBox(4, titleLabel, value);
        box.getStyleClass().add("stats-card");
        HBox.setHgrow(box, Priority.ALWAYS);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private void refresh() {
        LocalDate today = LocalDate.now();
        long todaySeconds = store.focusSeconds(today);
        int goal = store.getDailyGoalMinutes();
        todayValue.setText(formatMinutes(todaySeconds)
                + (goal > 0 ? " / " + goal + " 分钟" : ""));
        streakValue.setText(store.streakDays(today) + " 天");

        Map<LocalDate, Long> daily = store.dailyFocus(today, days);
        DateTimeFormatter format = DateTimeFormatter.ofPattern("M/d");
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        long total = 0;
        for (Map.Entry<LocalDate, Long> entry : daily.entrySet()) {
            total += entry.getValue();
            series.getData().add(new XYChart.Data<>(
                    entry.getKey().format(format),
                    Math.round(entry.getValue() / 6.0) / 10.0));
        }
        totalValue.setText(formatMinutes(total));
        chart.getData().setAll(java.util.List.of(series));
    }

    private void exportCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出学习记录");
        chooser.setInitialFileName("studytimer-sessions-" + LocalDate.now() + ".csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV 文件", "*.csv"));
        File file = chooser.showSaveDialog(dialog.getOwner());
        if (file == null) {
            return;
        }
        try {
            store.exportCsv(file.toPath());
            Alert done = new Alert(Alert.AlertType.INFORMATION, "已导出到：\n" + file);
            done.initOwner(dialog.getDialogPane().getScene().getWindow());
            done.setHeaderText(null);
            done.showAndWait();
        } catch (IOException e) {
            showError("导出失败", e);
        }
    }

    private void showError(String title, Exception e) {
        Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage());
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    public static String formatMinutes(long seconds) {
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        return (minutes / 60) + " 小时 " + (minutes % 60) + " 分";
    }
}
