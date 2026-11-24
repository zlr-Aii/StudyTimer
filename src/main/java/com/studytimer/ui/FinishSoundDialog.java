package com.studytimer.ui;

import com.studytimer.PhoneAudio;
import com.studytimer.PolarBellConnectionState;
import com.studytimer.PolarBellService;
import com.studytimer.service.UiSettingsStore;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 设置 → 完成提示音：手机内置提示音或手机音频库中的音频，以及最多播放多久。 */
public final class FinishSoundDialog {

    private static final String BUILTIN = "";

    private record Choice(String audioId, String label) {
    }

    private record Length(int seconds, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private FinishSoundDialog() {
    }

    public static void show(Window owner, PolarBellService polarBell, UiSettingsStore.Settings current,
                            Consumer<UiSettingsStore.Settings> onSave) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("完成提示音");
        dialog.setHeaderText(null);
        if (owner != null && owner.isShowing()) {
            dialog.initOwner(owner);
            if (owner.getScene() != null) {
                dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
            }
        }
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);

        List<Choice> initial = new ArrayList<>();
        initial.add(new Choice(BUILTIN, "手机内置提示音"));
        if (!current.finishAudioId.isBlank()) {
            String name = polarBell.getAudioName(current.finishAudioId);
            initial.add(new Choice(current.finishAudioId, name == null || name.isBlank()
                    ? "（当前选择的手机音频）" : name));
        }
        ComboBox<Choice> sound = new ComboBox<>(FXCollections.observableArrayList(initial));
        sound.setMaxWidth(Double.MAX_VALUE);
        sound.setCellFactory(list -> cell());
        sound.setButtonCell(cell());
        sound.getSelectionModel().select(current.finishAudioId.isBlank() ? 0 : 1);

        List<Length> lengths = List.of(new Length(5, "最多 5 秒"), new Length(10, "最多 10 秒"),
                new Length(15, "最多 15 秒"), new Length(30, "最多 30 秒"), new Length(60, "最多 60 秒"),
                new Length(0, "播完为止"));
        ComboBox<Length> length = new ComboBox<>(FXCollections.observableArrayList(lengths));
        length.getSelectionModel().select(lengths.stream()
                .filter(l -> l.seconds() == current.finishSeconds).findFirst().orElse(lengths.get(2)));

        Slider volume = new Slider(0, 100, current.finishVolume);
        volume.setShowTickLabels(true);
        volume.setMajorTickUnit(25);
        Label volumeText = new Label(current.finishVolume + "%");
        volume.valueProperty().addListener((obs, o, n) -> volumeText.setText(Math.round(n.doubleValue()) + "%"));

        Label status = new Label();
        status.getStyleClass().add("hint-text");
        status.setWrapText(true);
        Label hint = new Label("计划全部结束时在手机上播放。内置提示音是整首歌，“播完为止”时也只播 15 秒。"
                + "选手机音频库中的音频，旧版手机 App 也能用。");
        hint.getStyleClass().add("hint-text");
        hint.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("声音"), sound);
        grid.addRow(1, new Label("播放多久"), length);
        grid.addRow(2, new Label("音量"), new VBox(4, volume, volumeText));
        VBox content = new VBox(10, grid, hint, status);
        content.setPrefWidth(460);
        dialog.getDialogPane().setContent(content);

        if (polarBell.getConnectionState() == PolarBellConnectionState.CONNECTED) {
            status.setText("正在读取手机音频库…");
            polarBell.getAudioListAsync(audios -> Platform.runLater(() -> {
                Choice selected = sound.getValue();
                List<Choice> all = new ArrayList<>();
                all.add(initial.get(0));
                for (PhoneAudio audio : audios) {
                    String folder = audio.getFolder();
                    all.add(new Choice(audio.getAudioId(),
                            (folder == null || folder.isEmpty() ? "" : folder + " / ") + audio.getName()));
                }
                sound.getItems().setAll(all);
                Choice keep = all.stream()
                        .filter(c -> selected != null && c.audioId().equals(selected.audioId()))
                        .findFirst().orElse(null);
                if (keep == null && selected != null && !selected.audioId().isBlank()) {
                    status.setText("之前选的音频已不在手机音频库中，请重新选择");
                    sound.getSelectionModel().select(0);
                } else {
                    status.setText("");
                    sound.getSelectionModel().select(keep == null ? all.get(0) : keep);
                }
            }), error -> Platform.runLater(() -> status.setText("读取手机音频库失败：" + error.getMessage())));
        } else {
            status.setText("手机未连接，只能选择内置提示音或保留当前选择");
        }

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        UiSettingsStore.Settings next = current.copy();
        next.finishAudioId = sound.getValue() == null ? BUILTIN : sound.getValue().audioId();
        next.finishSeconds = length.getValue() == null ? 15 : length.getValue().seconds();
        next.finishVolume = (int) Math.round(volume.getValue());
        onSave.accept(next);
    }

    private static ListCell<Choice> cell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Choice item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
            }
        };
    }
}
