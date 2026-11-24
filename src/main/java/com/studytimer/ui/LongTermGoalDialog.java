package com.studytimer.ui;

import com.studytimer.model.PlanInfo;
import com.studytimer.service.LongTermGoalStore;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

/** 新建 / 编辑长期目标的对话框。 */
public final class LongTermGoalDialog {

    /** 对话框结果：保存（goal）或删除（deleted）。 */
    public record Result(LongTermGoalStore.Goal goal, boolean deleted) {
    }

    private static final String[] WEEKDAY_NAMES = {"一", "二", "三", "四", "五", "六", "日"};

    private LongTermGoalDialog() {
    }

    /**
     * @param existing     要编辑的目标；null 表示新建
     * @param plans        可选的 Focus Plan
     * @param focusMinutes 计划 id → 每遍专注分钟
     */
    public static Optional<Result> show(Window owner, LongTermGoalStore.Goal existing, List<PlanInfo> plans,
                                        ToIntFunction<String> focusMinutes) {
        boolean creating = existing == null;
        LongTermGoalStore.Goal goal = creating ? new LongTermGoalStore.Goal() : existing.copy();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(creating ? "新建长期目标" : "编辑长期目标");
        dialog.setHeaderText(null);
        if (owner != null && owner.isShowing()) {
            dialog.initOwner(owner);
            if (owner.getScene() != null) {
                dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
            }
        }
        ButtonType deleteType = new ButtonType("删除目标", ButtonBar.ButtonData.LEFT);
        if (creating) {
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        } else {
            dialog.getDialogPane().getButtonTypes().setAll(deleteType, ButtonType.OK, ButtonType.CANCEL);
            dialog.getDialogPane().lookupButton(deleteType).getStyleClass().add("danger-button");
        }

        TextField name = new TextField(goal.name);
        name.setPromptText("例如：考研数学");
        TextArea motivation = new TextArea(goal.motivation);
        motivation.setPromptText("为什么要做这件事？会显示在目标卡片上");
        motivation.setPrefRowCount(3);
        motivation.setWrapText(true);

        Spinner<Integer> hours = new Spinner<>(1, 100_000, goal.totalHours, 10);
        hours.setEditable(true);

        ComboBox<PlanInfo> plan = new ComboBox<>(FXCollections.observableArrayList(plans));
        plan.setMaxWidth(Double.MAX_VALUE);
        plan.setCellFactory(list -> planCell());
        plan.setButtonCell(planCell());
        for (PlanInfo p : plans) {
            if (p.getId().equals(goal.planId)) {
                plan.getSelectionModel().select(p);
            }
        }
        Label planInfo = new Label();
        planInfo.getStyleClass().add("hint-text");
        Runnable updatePlanInfo = () -> {
            PlanInfo p = plan.getValue();
            if (p == null) {
                planInfo.setText("请选择一个 Focus Plan，进度按这个计划的专注时间累计");
                return;
            }
            int minutes = focusMinutes.applyAsInt(p.getId());
            planInfo.setText(minutes > 0
                    ? "这个计划每遍专注 " + minutes + " 分钟（休息阶段不算）"
                    : "这个计划没有专注阶段，无法累计进度");
        };
        plan.valueProperty().addListener((obs, o, n) -> updatePlanInfo.run());
        updatePlanInfo.run();

        List<CheckBox> days = new ArrayList<>();
        HBox dayBox = new HBox(8);
        for (int i = 0; i < 7; i++) {
            CheckBox box = new CheckBox(WEEKDAY_NAMES[i]);
            box.setSelected(goal.weekdays.contains(i + 1));
            days.add(box);
            dayBox.getChildren().add(box);
        }

        Spinner<Integer> times = new Spinner<>(1, 20, goal.timesPerDay, 1);
        times.setEditable(true);

        DatePicker start = new DatePicker(goal.startLocalDate() == null ? LocalDate.now() : goal.startLocalDate());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        int row = 0;
        grid.addRow(row++, new Label("名称"), name);
        grid.addRow(row++, new Label("动机"), motivation);
        grid.addRow(row++, new Label("总小时数"), hours);
        grid.addRow(row++, new Label("对应计划"), plan);
        grid.add(planInfo, 1, row++);
        grid.addRow(row++, new Label("每周"), dayBox);
        grid.addRow(row++, new Label("每天几遍"), times);
        grid.addRow(row++, new Label("开始日期"), start);

        Label error = new Label();
        error.getStyleClass().add("error-text");
        VBox content = new VBox(10, grid, error);
        content.setPrefWidth(520);
        dialog.getDialogPane().setContent(content);

        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            String problem = null;
            if (name.getText().isBlank()) {
                problem = "请填写名称";
            } else if (plan.getValue() == null) {
                problem = "请选择对应的 Focus Plan";
            } else if (days.stream().noneMatch(CheckBox::isSelected)) {
                problem = "每周至少选一天";
            } else if (start.getValue() == null) {
                problem = "请选择开始日期";
            }
            if (problem != null) {
                error.setText(problem);
                ev.consume();
            }
        });
        javafx.application.Platform.runLater(name::requestFocus);

        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result == deleteType) {
            return confirmDelete(owner, goal) ? Optional.of(new Result(goal, true)) : Optional.empty();
        }
        if (result != ButtonType.OK) {
            return Optional.empty();
        }
        goal.name = name.getText().trim();
        goal.motivation = motivation.getText().trim();
        goal.totalHours = hours.getValue();
        goal.planId = plan.getValue().getId();
        goal.weekdays = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            if (days.get(i).isSelected()) {
                goal.weekdays.add(i + 1);
            }
        }
        goal.timesPerDay = times.getValue();
        goal.startDate = start.getValue().toString();
        return Optional.of(new Result(goal, false));
    }

    private static boolean confirmDelete(Window owner, LongTermGoalStore.Goal goal) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "删除长期目标“" + goal.name + "”？学习记录不会被删除。",
                ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText(null);
        if (owner != null && owner.isShowing()) {
            alert.initOwner(owner);
        }
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private static ListCell<PlanInfo> planCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(PlanInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
            }
        };
    }
}
