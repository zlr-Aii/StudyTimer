
package com.studytimer.audio;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;

public class AudioTestApp extends Application {

    private VlcAudioService audioService;

    private Path selectedFile;
    private Label fileLabel;
    private Label statusLabel;

    @Override
    public void start(Stage stage) {

        fileLabel = new Label("尚未选择音频文件");
        statusLabel = new Label("正在初始化 VLC...");

        Button chooseButton = new Button("选择文件");
        Button playButton = new Button("播放");
        Button stopButton = new Button("停止");

        chooseButton.setOnAction(event -> chooseFile(stage));

        playButton.setOnAction(event -> playAudio());

        stopButton.setOnAction(event -> stopAudio());

        VBox root = new VBox(18);
        root.setStyle("-fx-padding: 25;");

        root.getChildren().addAll(
                new Label("StudyTimer 音频测试"),
                fileLabel,
                new HBox(10, chooseButton, playButton, stopButton),
                statusLabel
        );

        stage.setTitle("StudyTimer - Audio Test");
        stage.setScene(new Scene(root, 430, 200));
        stage.show();

        try {
            audioService = new VlcAudioService();
            statusLabel.setText("VLC 初始化成功，可以测试 MP3 / FLAC");
        } catch (RuntimeException | LinkageError ex) {
            statusLabel.setText("VLC 初始化失败：" + ex.getMessage());
            showError("VLC 初始化失败", ex);
        }
    }

    private void chooseFile(Stage stage) {

        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择音频文件");

        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        "音频文件", "*.mp3", "*.flac"
                )
        );

        File file = chooser.showOpenDialog(stage);

        if (file != null) {
            selectedFile = file.toPath();
            fileLabel.setText(file.getName());
            statusLabel.setText("已选择音频文件");
        }
    }

    private void playAudio() {

        if (audioService == null) {
            statusLabel.setText("VLC 尚未初始化成功");
            return;
        }

        if (selectedFile == null) {
            statusLabel.setText("请先选择音频文件");
            return;
        }

        try {
            audioService.play(selectedFile);
            statusLabel.setText("已发送播放请求：" +
                    selectedFile.getFileName());
        } catch (RuntimeException | LinkageError ex) {
            statusLabel.setText("播放失败：" + ex.getMessage());
            showError("播放失败", ex);
        }
    }

    private void stopAudio() {

        if (audioService != null) {
            audioService.stop();
            statusLabel.setText("已停止播放");
        }
    }

    private void showError(String title, Throwable ex) {

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(ex.toString());
        alert.showAndWait();
    }

    @Override
    public void stop() {

        if (audioService != null) {
            audioService.close();
            audioService = null;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
