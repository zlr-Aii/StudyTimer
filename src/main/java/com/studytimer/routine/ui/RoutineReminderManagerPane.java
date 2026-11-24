package com.studytimer.routine.ui;

import com.studytimer.routine.model.RoutineReminder;
import com.studytimer.routine.service.RoutineReminderController;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * JavaFX management panel for recurring routine reminders.
 * All persistence operations are delegated to RoutineReminderController.
 */
public final class RoutineReminderManagerPane extends VBox {

    private static final Map<String, String> DAY_LABELS = Map.of(
            "MONDAY", "周一", "TUESDAY", "周二", "WEDNESDAY", "周三",
            "THURSDAY", "周四", "FRIDAY", "周五",
            "SATURDAY", "周六", "SUNDAY", "周日"
    );

    private static final Map<String, String> AUDIO_OUTPUT_LABELS = Map.of(
            "AUTO", "自动（优先手机，不可用时电脑播放）",
            "LOCAL", "仅电脑",
            "PHONE", "优先手机（不可用时电脑播放）"
    );

    private final RoutineReminderController controller;
    private final Consumer<Throwable> errorHandler;
    private final ListView<RoutineReminder> table = new ListView<>();

    private static final List<String> WEEK = List.of("MONDAY", "TUESDAY", "WEDNESDAY",
            "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");

    public RoutineReminderManagerPane(
            RoutineReminderController controller,
            Consumer<Throwable> errorHandler) {
        this.controller = controller;
        this.errorHandler = errorHandler == null
                ? Throwable::printStackTrace : errorHandler;

        setSpacing(12);
        getStyleClass().add("reminder-pane");

        table.getStyleClass().add("reminder-list");
        table.setCellFactory(list -> new ReminderCell());
        table.setPlaceholder(new Label("还没有提醒，点击“新建”开始添加"));
        table.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null) {
                editSelectedReminder();
            }
        });
        VBox.setVgrow(table, Priority.ALWAYS);

        Button addButton = new Button("＋ 新建");
        addButton.getStyleClass().add("accent-button");
        Button editButton = new Button("编辑");
        Button toggleButton = new Button("启用 / 禁用");
        Button deleteButton = new Button("删除");
        deleteButton.getStyleClass().add("danger-button");

        addButton.setOnAction(event -> addReminder());
        editButton.setOnAction(event -> editSelectedReminder());
        toggleButton.setOnAction(event -> toggleSelectedReminder());
        deleteButton.setOnAction(event -> deleteSelectedReminder());

        FlowPane actions = new FlowPane(8, 8, addButton, editButton, toggleButton, deleteButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(table, actions);

        refresh();
    }

    /** 两行卡片：时间 + 名称 + 状态；第二行为重复规则和媒体。 */
    private static final class ReminderCell extends ListCell<RoutineReminder> {
        @Override
        protected void updateItem(RoutineReminder reminder, boolean empty) {
            super.updateItem(reminder, empty);
            getStyleClass().remove("reminder-disabled");
            if (empty || reminder == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }

            Label time = new Label(reminder.getTime());
            time.getStyleClass().add("reminder-time");
            time.setMinWidth(Region.USE_PREF_SIZE);

            Label name = new Label(reminder.getName());
            name.getStyleClass().add("reminder-name");
            name.setMinWidth(0);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label state = new Label(stateLabel(reminder));
            state.getStyleClass().addAll("status-badge",
                    reminder.isEnabled() && !(reminder.isOneTime() && reminder.isCompleted())
                            ? "status-success" : "status-neutral");
            state.setMinWidth(Region.USE_PREF_SIZE);

            HBox top = new HBox(10, time, name, spacer, state);
            top.setAlignment(Pos.CENTER_LEFT);

            Label detail = new Label(detailLabel(reminder));
            detail.getStyleClass().add("hint-text");
            detail.setMinWidth(0);

            VBox box = new VBox(4, top, detail);
            box.setPadding(new Insets(4, 2, 4, 2));
            box.setMinWidth(0);

            // 卡片跟随列表宽度，过长的文件名用省略号，不再出现横向滚动条。
            setPrefWidth(0);
            setTooltip(new javafx.scene.control.Tooltip(
                    reminder.getTime() + "  " + reminder.getName() + "\n" + detailLabel(reminder)));

            if (!reminder.isEnabled()) {
                getStyleClass().add("reminder-disabled");
            }
            setText(null);
            setGraphic(box);
        }
    }

    private static String stateLabel(RoutineReminder reminder) {
        if (reminder.isOneTime() && reminder.isCompleted()) {
            return "已完成";
        }
        return reminder.isEnabled() ? "启用" : "停用";
    }

    private static String detailLabel(RoutineReminder reminder) {
        StringBuilder text = new StringBuilder(repeatLabel(reminder));
        String type = reminder.getMediaType();
        if (type != null && !"NONE".equals(type)) {
            text.append(" · ").append(mediaLabel(type));
            String path = reminder.getMediaPath();
            if (path != null && !path.isBlank()) {
                text.append(" · ").append(new File(path).getName());
            }
            if ("VIDEO".equals(type) && "LOCAL".equals(reminder.getAudioOutput())) {
                text.append(" · 电脑出声");
            } else if ("VIDEO".equals(type)) {
                text.append(" · 手机出声");
            }
        }
        return text.toString();
    }

    private static String repeatLabel(RoutineReminder reminder) {
        if (reminder.isOneTime()) {
            return reminder.getOnceDate() + " 一次";
        }
        Set<String> days = reminder.getRepeatDays();
        if (days.containsAll(WEEK)) {
            return "每天";
        }
        if (days.size() == 5 && days.containsAll(WEEK.subList(0, 5))) {
            return "工作日";
        }
        if (days.size() == 2 && days.containsAll(WEEK.subList(5, 7))) {
            return "周末";
        }
        List<String> labels = WEEK.stream().filter(days::contains).map(DAY_LABELS::get).toList();
        return labels.isEmpty() ? "未设置日期" : String.join(" ", labels);
    }

    public void refresh() {
        try {
            List<RoutineReminder> reminders = new java.util.ArrayList<>(controller.getReminders());
            reminders.sort(java.util.Comparator.comparing(
                    r -> r.getTime() == null ? "" : r.getTime()));
            table.setItems(FXCollections.observableArrayList(reminders));
        } catch (RuntimeException error) {
            reportError(error);
            showError("读取提醒列表失败：" + error.getMessage());
        }
    }

    private void addReminder() {
        ReminderEditor editor = new ReminderEditor(getScene() == null
                ? null : getScene().getWindow(), null);
        editor.showAndWait().ifPresent(reminder -> {
            try {
                controller.add(reminder);
                refresh();
            } catch (IOException | RuntimeException error) {
                reportError(error);
                showError("保存提醒失败：" + error.getMessage());
            }
        });
    }

    private void editSelectedReminder() {
        RoutineReminder selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showInfo("请先选择一条提醒。");
            return;
        }
        ReminderEditor editor = new ReminderEditor(
                getScene() == null ? null : getScene().getWindow(), selected);
        editor.showAndWait().ifPresent(reminder -> {
            try {
                controller.update(reminder);
                refresh();
            } catch (IOException | RuntimeException error) {
                reportError(error);
                showError("更新提醒失败：" + error.getMessage());
            }
        });
    }

    private void toggleSelectedReminder() {
        RoutineReminder selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showInfo("请先选择一条提醒。");
            return;
        }
        try {
            controller.setEnabled(selected.getId(), !selected.isEnabled());
            refresh();
        } catch (IOException | RuntimeException error) {
            reportError(error);
            showError("修改提醒状态失败：" + error.getMessage());
        }
    }

    private void deleteSelectedReminder() {
        RoutineReminder selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showInfo("请先选择一条提醒。");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除提醒");
        confirm.setHeaderText("确定删除提醒“" + selected.getName() + "”吗？");
        confirm.setContentText("删除后无法从提醒列表恢复。");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        try {
            controller.delete(selected.getId());
            refresh();
        } catch (IOException | RuntimeException error) {
            reportError(error);
            showError("删除提醒失败：" + error.getMessage());
        }
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private void reportError(Throwable error) {
        try {
            errorHandler.accept(error);
        } catch (RuntimeException ignored) {
            // Error reporting must not interrupt UI recovery.
        }
    }

    private static String mediaLabel(String type) {
        return switch (type == null ? "NONE" : type) {
            case "IMAGE" -> "图片";
            case "VIDEO" -> "视频";
            case "AUDIO" -> "音乐";
            case "VOICE" -> "语音";
            default -> "无";
        };
    }

    private static final class ReminderEditor {

        private final Dialog<RoutineReminder> dialog = new Dialog<>();
        private final TextField nameField = new TextField();
        private final TextField timeField = new TextField();
        private final TextField onceDateField = new TextField();
        private final TextArea messageArea = new TextArea();
        private final ComboBox<String> mediaTypeBox = new ComboBox<>();
        private final TextField mediaPathField = new TextField();
        private final ComboBox<String> audioOutputBox = new ComboBox<>();
        private final javafx.scene.control.Slider volumeSlider =
                new javafx.scene.control.Slider(0, 100, 100);
        private final Label volumeLabel = new Label("100%");
        private final Map<String, CheckBox> dayChecks = new java.util.LinkedHashMap<>();
        private final CheckBox enabledCheck = new CheckBox("启用此提醒");
        private final RoutineReminder original;

        private ReminderEditor(Window owner, RoutineReminder original) {
            this.original = original == null ? null : original.copy();
            dialog.setTitle(original == null ? "新建提醒" : "编辑提醒");
            dialog.setHeaderText("设置触发时间、重复日期和提醒内容");
            if (owner != null) {
                dialog.initOwner(owner);
                if (owner.getScene() != null) {
                    dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
                }
            }

            ButtonType saveType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().setAll(saveType, ButtonType.CANCEL);

            GridPane grid = new GridPane();
            grid.setHgap(12);
            grid.setVgap(10);
            grid.setPadding(new Insets(12));

            nameField.setPromptText("例如：喝水、休息、日语学习");
            timeField.setPromptText("HH:mm，例如 08:30");
            onceDateField.setPromptText("留空表示每周重复；填写 yyyy-MM-dd 表示一次性提醒");
            messageArea.setPromptText("可选：显示在全屏提醒窗口中的文字");
            messageArea.setPrefRowCount(3);

            mediaTypeBox.setItems(FXCollections.observableArrayList(
                    "NONE", "IMAGE", "VIDEO", "AUDIO", "VOICE"));
            mediaTypeBox.setValue("NONE");

            audioOutputBox.setItems(FXCollections.observableArrayList(
                    "AUTO", "LOCAL", "PHONE"));
            audioOutputBox.setValue("AUTO");
            audioOutputBox.setConverter(new javafx.util.StringConverter<>() {
                @Override
                public String toString(String code) {
                    if (code == null) return "";
                    return AUDIO_OUTPUT_LABELS.getOrDefault(code, code);
                }

                @Override
                public String fromString(String label) {
                    if (label == null) return "AUTO";
                    for (Map.Entry<String, String> entry
                            : AUDIO_OUTPUT_LABELS.entrySet()) {
                        if (entry.getValue().equals(label)) {
                            return entry.getKey();
                        }
                    }
                    return "AUTO";
                }
            });

            mediaPathField.setPromptText("选择媒体文件");
            mediaPathField.setEditable(false);
            Button browseButton = new Button("选择文件");
            browseButton.setOnAction(event -> chooseMediaFile());

            HBox mediaPathRow = new HBox(8, mediaPathField, browseButton);
            mediaPathRow.setAlignment(Pos.CENTER_LEFT);
            mediaPathField.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(mediaPathField, javafx.scene.layout.Priority.ALWAYS);

            HBox dayRow = new HBox(8);
            dayRow.setAlignment(Pos.CENTER_LEFT);
            for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY",
                    "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
                CheckBox check = new CheckBox(DAY_LABELS.get(day));
                dayChecks.put(day, check);
                dayRow.getChildren().add(check);
            }

            int row = 0;
            addRow(grid, row++, "提醒名称", nameField);
            addRow(grid, row++, "触发时间", timeField);
            addRow(grid, row++, "指定日期（可选）", onceDateField);
            addRow(grid, row++, "每周重复日期", dayRow);
            addRow(grid, row++, "提醒文字", messageArea);
            addRow(grid, row++, "媒体类型", mediaTypeBox);
            addRow(grid, row++, "媒体文件", mediaPathRow);
            addRow(grid, row++, "视频声音", audioOutputBox);
            volumeSlider.setMajorTickUnit(25);
            volumeSlider.setBlockIncrement(5);
            volumeSlider.valueProperty().addListener((obs, old, value) ->
                    volumeLabel.setText(Math.round(value.doubleValue()) + "%"));
            volumeLabel.setMinWidth(44);
            HBox volumeRow = new HBox(8, volumeSlider, volumeLabel);
            volumeRow.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(volumeSlider, javafx.scene.layout.Priority.ALWAYS);
            addRow(grid, row++, "音量", volumeRow);
            Label mediaHint = new Label("AUDIO：音乐/环境音；VOICE：人声/录音（目前均播放 MP3/FLAC）。\n"
                    + "VIDEO：建议 MP4/M4V，H.264 + AAC，1080p/30 FPS；高分辨率或高码率视频可能卡顿。\n"
                    + "视频声音：自动/优先手机会通过 PolarBell 把视频原声发送到手机播放，"
                    + "电脑只显示静音画面；手机未连接、App 不支持、文件超过上限或未能按时开始播放时，"
                    + "改由电脑播放，并在主界面状态栏提示原因。\n"
                    + "音量：同时作用于电脑和手机播放（手机按其媒体音量的百分比）。\n"
                    + "注意：MP4 只是容器，实际兼容性还取决于内部编码。");
            mediaHint.setWrapText(true);
            mediaHint.getStyleClass().add("hint-label");
            addRow(grid, row++, "格式建议", mediaHint);
            addRow(grid, row, "状态", enabledCheck);

            dialog.getDialogPane().setContent(grid);
            dialog.getDialogPane().setPrefWidth(650);

            if (original == null) {
                nameField.setText("新提醒");
                timeField.setText("08:00");
                for (CheckBox check : dayChecks.values()) {
                    check.setSelected(true);
                }
                enabledCheck.setSelected(true);
            } else {
                nameField.setText(original.getName());
                timeField.setText(original.getTime());
                onceDateField.setText(original.getOnceDate());
                messageArea.setText(original.getMessage());
                mediaTypeBox.setValue(original.getMediaType());
                mediaPathField.setText(original.getMediaPath());
                audioOutputBox.setValue(original.getAudioOutput());
                volumeSlider.setValue(original.getVolume());
                enabledCheck.setSelected(original.isEnabled());
                original.getRepeatDays().forEach(day -> {
                    CheckBox check = dayChecks.get(day);
                    if (check != null) check.setSelected(true);
                });
            }

            dialog.setResultConverter(button -> {
                if (button != saveType) return null;
                Set<String> days = new LinkedHashSet<>();
                dayChecks.forEach((day, check) -> {
                    if (check.isSelected()) days.add(day);
                });
                RoutineReminder result = new RoutineReminder(
                        original == null ? UUID.randomUUID().toString() : original.getId(),
                        nameField.getText().trim(),
                        timeField.getText().trim(),
                        days,
                        enabledCheck.isSelected(),
                        messageArea.getText(),
                        mediaTypeBox.getValue(),
                        mediaPathField.getText().trim()
                );
                result.setAudioOutput(audioOutputBox.getValue());
                result.setVolume((int) Math.round(volumeSlider.getValue()));
                result.setOnceDate(onceDateField.getText().trim());
                if (original != null
                        && original.isOneTime()
                        && original.getOnceDate().equals(result.getOnceDate())) {
                    result.setCompleted(original.isCompleted());
                }
                return result;
            });

            dialog.getDialogPane().lookupButton(saveType).addEventFilter(
                    javafx.event.ActionEvent.ACTION, event -> {
                        try {
                            buildCandidate().validate();
                        } catch (RuntimeException error) {
                            event.consume();
                            Alert alert = new Alert(Alert.AlertType.WARNING,
                                    error.getMessage(), ButtonType.OK);
                            alert.initOwner(dialog.getDialogPane().getScene().getWindow());
                            alert.setHeaderText("请检查提醒设置");
                            alert.showAndWait();
                        }
                    });
        }

        private static void addRow(GridPane grid, int row, String label, javafx.scene.Node node) {
            grid.add(new Label(label), 0, row);
            grid.add(node, 1, row);
            GridPane.setHgrow(node, javafx.scene.layout.Priority.ALWAYS);
        }

        private RoutineReminder buildCandidate() {
            Set<String> days = new LinkedHashSet<>();
            dayChecks.forEach((day, check) -> {
                if (check.isSelected()) days.add(day);
            });
            RoutineReminder result = new RoutineReminder(
                    original == null ? UUID.randomUUID().toString() : original.getId(),
                    nameField.getText().trim(),
                    timeField.getText().trim(),
                    days,
                    enabledCheck.isSelected(),
                    messageArea.getText(),
                    mediaTypeBox.getValue(),
                    mediaPathField.getText().trim()
            );
            result.setAudioOutput(audioOutputBox.getValue());
            result.setVolume((int) Math.round(volumeSlider.getValue()));
            result.setOnceDate(onceDateField.getText().trim());
            if (original != null
                    && original.isOneTime()
                    && original.getOnceDate().equals(result.getOnceDate())) {
                result.setCompleted(original.isCompleted());
            }
            return result;
        }

        private void chooseMediaFile() {
            String type = mediaTypeBox.getValue();
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择提醒媒体文件");
            switch (type) {
                case "IMAGE" -> chooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("图片", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
                case "VIDEO" -> chooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("推荐视频格式（MP4 / M4V）", "*.mp4", "*.m4v"));
                case "AUDIO", "VOICE" -> chooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("音频", "*.mp3", "*.flac"));
                default -> chooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("所有文件", "*.*"));
            }
            File selected = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (selected != null) {
                mediaPathField.setText(selected.getAbsolutePath());
            }
        }

        private java.util.Optional<RoutineReminder> showAndWait() {
            return dialog.showAndWait();
        }
    }
}
