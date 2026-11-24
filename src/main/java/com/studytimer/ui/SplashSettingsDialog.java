package com.studytimer.ui;

import com.studytimer.service.SplashSettingsStore;
import com.studytimer.service.SplashSettingsStore.Settings;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * 设置 → 开屏…：内容、时机、文字、按钮文字、播放位置、音量，以及点右按钮后的回应。
 */
public final class SplashSettingsDialog {

    private record Option(String value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private SplashSettingsDialog() {
    }

    /** onPreview：用当前（未保存的）设置预览开屏。 */
    public static void show(Window owner, SplashSettingsStore store, Consumer<Settings> onPreview) {
        Settings current = store.get();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("开屏设置");
        dialog.setHeaderText("启动时先显示一屏内容；左按钮直接进入，右按钮先显示一段回应再进入");
        if (owner != null) {
            dialog.initOwner(owner);
            if (owner.getScene() != null) {
                dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
            }
        }
        ButtonType previewType = new ButtonType("预览", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().setAll(previewType, ButtonType.OK, ButtonType.CANCEL);

        CheckBox enabled = new CheckBox("启用开屏");
        enabled.setSelected(current.enabled);

        // ---- 开屏 ----
        ComboBox<Option> media = mediaBox("仅文字（白色背景）");
        select(media, current.mediaType);
        TextField path = new TextField(current.mediaPath);
        HBox pathRow = fileRow(path, media, dialog);

        ComboBox<Option> timing = new ComboBox<>();
        timing.getItems().addAll(
                new Option(Settings.TIMING_ALWAYS, "每次启动"),
                new Option(Settings.TIMING_AUTOSTART, "仅开机自动启动时"));
        select(timing, current.timing);

        TextField message = new TextField(current.message);
        TextField yes = new TextField(current.yesText);
        TextField no = new TextField(current.noText);

        // ---- 右按钮的回应 ----
        TextArea noMessage = new TextArea(current.noMessage);
        noMessage.setWrapText(true);
        noMessage.setPrefRowCount(3);
        ComboBox<Option> noMedia = mediaBox("仅文字");
        select(noMedia, current.noMediaType);
        TextField noPath = new TextField(current.noMediaPath);
        HBox noPathRow = fileRow(noPath, noMedia, dialog);
        Spinner<Integer> noDuration = new Spinner<>(0, 600, current.noDurationSeconds, 5);
        noDuration.setEditable(true);
        noDuration.setPrefWidth(100);
        Label durationNote = new Label("秒后进入主界面（0 = 视频/音乐播完为止）");
        durationNote.getStyleClass().add("hint-text");
        HBox durationRow = new HBox(8, noDuration, durationNote);
        durationRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // ---- 声音（开屏和回应共用）----
        ComboBox<Option> output = new ComboBox<>();
        output.getItems().addAll(
                new Option(Settings.OUTPUT_LOCAL, "电脑"),
                new Option(Settings.OUTPUT_PHONE, "优先手机（连不上用电脑）"));
        select(output, current.output);

        Slider volume = new Slider(0, 100, current.volume);
        Label volumeLabel = new Label(current.volume + "%");
        volume.valueProperty().addListener((obs, old, v) -> volumeLabel.setText(v.intValue() + "%"));
        HBox volumeRow = new HBox(8, volume, volumeLabel);
        HBox.setHgrow(volume, Priority.ALWAYS);

        Runnable refresh = () -> {
            pathRow.setDisable(Settings.MEDIA_NONE.equals(media.getValue().value()));
            noPathRow.setDisable(Settings.MEDIA_NONE.equals(noMedia.getValue().value()));
            boolean playback = isPlayback(media) || isPlayback(noMedia);
            output.setDisable(!playback);
            volumeRow.setDisable(!playback);
        };
        media.valueProperty().addListener((obs, old, v) -> refresh.run());
        noMedia.valueProperty().addListener((obs, old, v) -> refresh.run());
        refresh.run();

        Label note = new Label("开机启动时每天只显示一次；开机时会等手机最多 15 秒，连不上就用电脑播放。");
        note.getStyleClass().add("hint-text");
        note.setWrapText(true);

        Label responseTitle = new Label("点右按钮后");
        responseTitle.getStyleClass().add("section-title");
        Label soundTitle = new Label("声音");
        soundTitle.getStyleClass().add("section-title");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        int row = 0;
        grid.add(enabled, 0, row++, 2, 1);
        grid.addRow(row++, new Label("内容"), media);
        grid.addRow(row++, new Label("文件"), pathRow);
        grid.addRow(row++, new Label("显示时机"), timing);
        grid.addRow(row++, new Label("文字"), message);
        grid.addRow(row++, new Label("左按钮"), yes);
        grid.addRow(row++, new Label("右按钮"), no);
        grid.add(new Separator(), 0, row++, 2, 1);
        grid.add(responseTitle, 0, row++, 2, 1);
        grid.addRow(row++, new Label("回应文字"), noMessage);
        grid.addRow(row++, new Label("回应内容"), noMedia);
        grid.addRow(row++, new Label("文件"), noPathRow);
        grid.addRow(row++, new Label("停留"), durationRow);
        grid.add(new Separator(), 0, row++, 2, 1);
        grid.add(soundTitle, 0, row++, 2, 1);
        grid.addRow(row++, new Label("声音播放"), output);
        grid.addRow(row++, new Label("音量"), volumeRow);
        grid.add(note, 0, row, 2, 1);
        GridPane.setHgrow(pathRow, Priority.ALWAYS);
        GridPane.setHgrow(noPathRow, Priority.ALWAYS);
        grid.setPrefWidth(560);

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(560);
        dialog.getDialogPane().setContent(scroll);

        java.util.function.Supplier<Settings> collect = () -> {
            Settings s = store.get();
            s.enabled = enabled.isSelected();
            s.mediaType = media.getValue().value();
            s.mediaPath = trim(path.getText());
            s.timing = timing.getValue().value();
            s.message = message.getText();
            s.yesText = yes.getText();
            s.noText = no.getText();
            s.noMessage = noMessage.getText();
            s.noMediaType = noMedia.getValue().value();
            s.noMediaPath = trim(noPath.getText());
            Integer seconds = noDuration.getValue();
            s.noDurationSeconds = seconds == null ? 15 : seconds;
            s.output = output.getValue().value();
            s.volume = (int) Math.round(volume.getValue());
            return s.copy();
        };

        Button previewButton = (Button) dialog.getDialogPane().lookupButton(previewType);
        previewButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume();
            onPreview.accept(collect.get());
        });

        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            Settings s = collect.get();
            String missing = null;
            if (!Settings.MEDIA_NONE.equals(s.mediaType) && s.mediaPath.isBlank()) {
                missing = "开屏";
            } else if (!Settings.MEDIA_NONE.equals(s.noMediaType) && s.noMediaPath.isBlank()) {
                missing = "回应";
            }
            if (s.enabled && missing != null) {
                e.consume();
                new Alert(Alert.AlertType.WARNING,
                        "请选择" + missing + "文件，或把内容改为“仅文字”。").showAndWait();
                return;
            }
            try {
                store.save(s);
            } catch (IOException ex) {
                e.consume();
                new Alert(Alert.AlertType.ERROR, "保存开屏设置失败：" + ex.getMessage()).showAndWait();
            }
        });

        dialog.show();
    }

    private static String trim(String text) {
        return text == null ? "" : text.trim();
    }

    private static boolean isPlayback(ComboBox<Option> box) {
        String v = box.getValue().value();
        return Settings.MEDIA_VIDEO.equals(v) || Settings.MEDIA_AUDIO.equals(v);
    }

    private static ComboBox<Option> mediaBox(String noneLabel) {
        ComboBox<Option> box = new ComboBox<>();
        box.getItems().addAll(
                new Option(Settings.MEDIA_NONE, noneLabel),
                new Option(Settings.MEDIA_IMAGE, "图片"),
                new Option(Settings.MEDIA_VIDEO, "视频（MP4）"),
                new Option(Settings.MEDIA_AUDIO, "音乐 / 语音（MP3、FLAC）"));
        return box;
    }

    private static HBox fileRow(TextField path, ComboBox<Option> media, Dialog<?> dialog) {
        path.setPromptText("选择文件");
        HBox.setHgrow(path, Priority.ALWAYS);
        Button browse = new Button("浏览…");
        browse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择文件");
            switch (media.getValue().value()) {
                case Settings.MEDIA_IMAGE -> chooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("图片", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
                case Settings.MEDIA_VIDEO -> chooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("MP4 视频", "*.mp4", "*.m4v"));
                case Settings.MEDIA_AUDIO -> chooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("音乐", "*.mp3", "*.flac"));
                default -> {
                }
            }
            File file = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (file != null) {
                path.setText(file.getAbsolutePath());
            }
        });
        return new HBox(8, path, browse);
    }

    private static void select(ComboBox<Option> box, String value) {
        for (Option option : box.getItems()) {
            if (option.value().equals(value)) {
                box.setValue(option);
                return;
            }
        }
        box.setValue(box.getItems().get(0));
    }
}
