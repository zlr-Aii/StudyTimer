package com.studytimer.ui;

import com.studytimer.service.AppLog;
import com.studytimer.service.LifeGuardBarrier;
import com.studytimer.service.LifeGuardEngine;
import com.studytimer.service.LifeGuardStore;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * 生命保障的设置与障碍对话框。
 *
 * 开启：一键。关闭：设置 → 高级 → 危险区 → 确认 → 三个问题 + 密码 + 60 秒冷静期。
 * 修改工作/休息时长、托盘退出：一个问题（简化障碍）。修改问题和密码：完整障碍。
 */
public final class LifeGuardDialogs {

    /** 手机音频库：用在“阶段音乐”选择里。 */
    public interface PhoneAudioSource {
        void getPhoneAudioListAsync(
                java.util.function.Consumer<java.util.List<com.studytimer.PhoneAudio>> onSuccess,
                java.util.function.Consumer<Exception> onError);

        String getPhoneAudioName(String audioId);
    }

    private LifeGuardDialogs() {
    }

    // ==================== 设置 ====================

    public static void showSettings(Window owner, LifeGuardController controller) {
        showSettings(owner, controller, null);
    }

    public static void showSettings(Window owner, LifeGuardController controller, PhoneAudioSource phoneAudio) {
        Dialog<Void> dialog = newDialog(owner, "生命保障");
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);

        Label intro = new Label("没有主动运行 Focus Plan 时，按“工作 → 休息”循环提醒你离开电脑。"
                + "键鼠连续空闲达到休息时长，视为已经休息。");
        intro.setWrapText(true);
        intro.getStyleClass().add("hint-text");

        CheckBox enabledBox = new CheckBox("开启生命保障");
        Label stateLabel = new Label();
        stateLabel.getStyleClass().add("hint-text");
        Label durationLabel = new Label();
        Button editDurations = new Button("修改时长…");
        Button restScreen = new Button("休息画面与音乐…");
        Button advanced = new Button("高级…");

        Runnable refresh = () -> {
            LifeGuardStore.Settings s = controller.getSettings();
            enabledBox.setSelected(s.enabled);
            enabledBox.setDisable(s.enabled);
            stateLabel.setText(s.enabled ? "已开启（关闭入口在“高级”里）" : "未开启，勾选即可开启");
            durationLabel.setText("工作 " + s.workMinutes + " 分钟 → 休息 " + s.restMinutes + " 分钟");
        };
        refresh.run();

        enabledBox.setOnAction(e -> {
            if (!enabledBox.isSelected()) {
                refresh.run();
                return;
            }
            LifeGuardStore.Settings s = controller.getSettings();
            s.enabled = true;
            save(controller, s, dialog.getDialogPane().getScene().getWindow());
            refresh.run();
        });

        editDurations.setOnAction(e -> {
            Window w = dialog.getDialogPane().getScene().getWindow();
            LifeGuardStore.Settings s = controller.getSettings();
            if (s.enabled && !askSimple(w, s, "修改生命保障时长")) {
                return;
            }
            if (editDurations(w, s)) {
                save(controller, s, w);
                refresh.run();
            }
        });

        restScreen.setOnAction(e -> {
            Window w = dialog.getDialogPane().getScene().getWindow();
            LifeGuardStore.Settings s = controller.getSettings();
            if (editRestScreen(w, s, controller, phoneAudio)) {
                save(controller, s, w);
            }
        });

        advanced.setOnAction(e -> {
            showAdvanced(dialog.getDialogPane().getScene().getWindow(), controller);
            refresh.run();
        });

        VBox box = new VBox(12, intro, enabledBox, stateLabel, durationLabel,
                new javafx.scene.layout.HBox(8, editDurations, restScreen, advanced));
        box.setPadding(new Insets(8));
        box.setPrefWidth(520);
        dialog.getDialogPane().setContent(box);
        dialog.showAndWait();
    }

    private static boolean editDurations(Window owner, LifeGuardStore.Settings s) {
        Dialog<ButtonType> dialog = newDialog(owner, "生命保障时长");
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        Spinner<Integer> work = new Spinner<>(LifeGuardEngine.MIN_WORK_MINUTES,
                LifeGuardEngine.MAX_WORK_MINUTES, s.workMinutes, 5);
        Spinner<Integer> rest = new Spinner<>(LifeGuardEngine.MIN_REST_MINUTES,
                LifeGuardEngine.MAX_REST_MINUTES, s.restMinutes, 1);
        work.setEditable(true);
        rest.setEditable(true);
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("工作（分钟）"), work);
        grid.addRow(1, new Label("休息（分钟）"), rest);
        Label limit = new Label("工作最长 " + LifeGuardEngine.MAX_WORK_MINUTES + " 分钟，休息最短 "
                + LifeGuardEngine.MIN_REST_MINUTES + " 分钟。");
        limit.getStyleClass().add("hint-text");
        dialog.getDialogPane().setContent(new VBox(12, grid, limit));
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return false;
        }
        s.workMinutes = LifeGuardEngine.clampWork(work.getValue());
        s.restMinutes = LifeGuardEngine.clampRest(rest.getValue());
        return true;
    }

    /** 休息画面文字 + 阶段转换音乐。 */
    private static boolean editRestScreen(Window owner, LifeGuardStore.Settings s,
                                          LifeGuardController controller, PhoneAudioSource phoneAudio) {
        Dialog<ButtonType> dialog = newDialog(owner, "生命保障 - 休息画面与音乐");
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);

        TextArea hint = new TextArea(s.restHint);
        hint.setWrapText(true);
        hint.setPrefRowCount(3);
        Label hintTip = new Label("这段文字显示在全屏休息窗口的正中间，可以留空（留空则用默认文字）。");
        hintTip.setWrapText(true);
        hintTip.getStyleClass().add("hint-text");

        TextField restPc = new TextField(s.restPcMusic);
        HBox restPcRow = pcMusicRow(owner, restPc);
        String[] restPhone = {s.restPhoneMusic};
        Label restPhoneLabel = new Label(describePhone(phoneAudio, restPhone[0]));
        HBox restPhoneRow = phoneMusicRow(owner, phoneAudio, restPhoneLabel,
                restPhone, id -> restPhone[0] = id);
        Spinner<Integer> restVolume = new Spinner<>(0, 100, s.restMusicVolume, 5);
        restVolume.setEditable(true);

        TextField workPc = new TextField(s.workPcMusic);
        HBox workPcRow = pcMusicRow(owner, workPc);
        String[] workPhone = {s.workPhoneMusic};
        Label workPhoneLabel = new Label(describePhone(phoneAudio, workPhone[0]));
        HBox workPhoneRow = phoneMusicRow(owner, phoneAudio, workPhoneLabel,
                workPhone, id -> workPhone[0] = id);
        Spinner<Integer> workVolume = new Spinner<>(0, 100, s.workMusicVolume, 5);
        workVolume.setEditable(true);
        Spinner<Integer> workSeconds = new Spinner<>(0, 600, s.workMusicSeconds, 5);
        workSeconds.setEditable(true);

        GridPane restGrid = musicGrid();
        restGrid.addRow(0, new Label("电脑"), restPcRow);
        restGrid.addRow(1, new Label("手机"), restPhoneRow);
        restGrid.addRow(2, new Label("音量"), restVolume);

        GridPane workGrid = musicGrid();
        workGrid.addRow(0, new Label("电脑"), workPcRow);
        workGrid.addRow(1, new Label("手机"), workPhoneRow);
        workGrid.addRow(2, new Label("音量"), workVolume);
        workGrid.addRow(3, new Label("播放"), new HBox(6, workSeconds, new Label("秒后停止（0 = 直到下一次切换）")));

        Button preview = new Button("试听休息音乐（15 秒）");
        preview.setOnAction(e -> controller.previewMusic(
                restPc.getText().trim(), restPhone[0], restVolume.getValue()));

        Label restTitle = new Label("进入休息（全屏）时播放");
        restTitle.getStyleClass().add("section-title");
        Label workTitle = new Label("休息结束、回到工作时播放");
        workTitle.getStyleClass().add("section-title");
        Label musicTip = new Label("电脑可以选一个音乐文件，也可以选整个文件夹（按文件名顺序依次播放并循环）；"
                + "手机可以选整个文件夹随机或顺序连续播放。两边都留空就安静休息。");
        musicTip.setWrapText(true);
        musicTip.getStyleClass().add("hint-text");

        VBox box = new VBox(10, new Label("提示文字"), hint, hintTip,
                new javafx.scene.control.Separator(), restTitle, restGrid, preview,
                new javafx.scene.control.Separator(), workTitle, workGrid,
                musicTip);
        box.setPadding(new Insets(8));
        box.setPrefWidth(600);
        dialog.getDialogPane().setContent(box);
        dialog.setOnHidden(e -> controller.stopPreview());

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return false;
        }
        String text = hint.getText() == null ? "" : hint.getText().replace("\r\n", "\n").strip();
        s.restHint = text.isEmpty() ? LifeGuardStore.DEFAULT_REST_HINT : text;
        s.restPcMusic = restPc.getText().trim();
        s.restPhoneMusic = restPhone[0];
        s.restMusicVolume = restVolume.getValue();
        s.workPcMusic = workPc.getText().trim();
        s.workPhoneMusic = workPhone[0];
        s.workMusicVolume = workVolume.getValue();
        s.workMusicSeconds = workSeconds.getValue();
        return true;
    }

    private static GridPane musicGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        javafx.scene.layout.ColumnConstraints grow = new javafx.scene.layout.ColumnConstraints();
        grow.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        grid.getColumnConstraints().addAll(new javafx.scene.layout.ColumnConstraints(), grow);
        return grid;
    }

    /** 电脑音乐：一个输入框 + 选文件 / 选文件夹 / 清空。 */
    private static HBox pcMusicRow(Window owner, TextField field) {
        field.setPromptText("留空 = 不播放（可以是文件或整个文件夹）");
        javafx.scene.layout.HBox.setHgrow(field, javafx.scene.layout.Priority.ALWAYS);

        Button pickFile = new Button("文件…");
        Button pickFolder = new Button("文件夹…");
        Button clear = new Button("清空");
        pickFile.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("选择音乐文件");
            chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                    "音频", "*.mp3", "*.flac", "*.m4a", "*.aac", "*.wav", "*.ogg", "*.wma"));
            java.io.File file = chooser.showOpenDialog(owner);
            if (file != null) {
                field.setText(file.getAbsolutePath());
            }
        });
        pickFolder.setOnAction(e -> {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("选择音乐文件夹");
            if (field.getText() != null && !field.getText().isBlank()) {
                java.io.File current = new java.io.File(field.getText().trim());
                java.io.File dir = current.isDirectory() ? current : current.getParentFile();
                if (dir != null && dir.isDirectory()) {
                    chooser.setInitialDirectory(dir);
                }
            }
            java.io.File dir = chooser.showDialog(owner);
            if (dir != null) {
                field.setText(dir.getAbsolutePath());
            }
        });
        clear.setOnAction(e -> field.clear());
        HBox row = new HBox(6, field, pickFile, pickFolder, clear);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }

    /** 手机音乐：显示当前选择 + 选择 / 清空。 */
    private static HBox phoneMusicRow(Window owner, PhoneAudioSource source, Label value,
                                      String[] current, java.util.function.Consumer<String> onPick) {
        value.getStyleClass().add("hint-text");
        javafx.scene.layout.HBox.setHgrow(value, javafx.scene.layout.Priority.ALWAYS);
        Button choose = new Button("选择…");
        Button clear = new Button("清空");
        choose.setOnAction(e -> choosePhoneMusic(owner, source, value, current, onPick));
        clear.setOnAction(e -> {
            onPick.accept("");
            current[0] = "";
            value.setText(describePhone(source, ""));
        });
        HBox row = new HBox(6, value, choose, clear);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }

    private record PhoneChoice(String id, String text) {
        @Override
        public String toString() {
            return text;
        }
    }

    private static void choosePhoneMusic(Window owner, PhoneAudioSource source, Label value,
                                         String[] current, java.util.function.Consumer<String> onPick) {
        if (source == null) {
            info(owner, "手机音频库不可用。请先连接 PolarBell，或在手机端直接播放音乐。");
            return;
        }
        source.getPhoneAudioListAsync(
                audios -> javafx.application.Platform.runLater(() -> {
                    List<PhoneChoice> choices = new ArrayList<>();
                    choices.add(new PhoneChoice("", "（不播放）"));
                    if (audios != null && !audios.isEmpty()) {
                        List<String> folderOrder = new ArrayList<>();
                        if (audios.stream().anyMatch(a -> a.getFolder().isEmpty())) {
                            folderOrder.add("");
                        }
                        for (com.studytimer.PhoneAudio audio : audios) {
                            if (!folderOrder.contains(audio.getFolder())) {
                                folderOrder.add(audio.getFolder());
                            }
                        }
                        for (String folder : folderOrder) {
                            for (com.studytimer.audio.PhoneFolderPlaylist.Mode mode
                                    : com.studytimer.audio.PhoneFolderPlaylist.Mode.values()) {
                                var selection = new com.studytimer.audio.PhoneFolderPlaylist.Selection(mode, folder);
                                choices.add(new PhoneChoice(selection.toId(), "▶ " + selection.label()));
                            }
                        }
                        for (String folder : folderOrder) {
                            for (com.studytimer.PhoneAudio audio : audios) {
                                if (audio.getFolder().equals(folder)) {
                                    choices.add(new PhoneChoice(audio.getAudioId(),
                                            (folder.isEmpty() ? "未分类" : folder) + " / " + audio.getName()));
                                }
                            }
                        }
                    }
                    PhoneChoice selected = choices.get(0);
                    for (PhoneChoice choice : choices) {
                        if (choice.id().equals(current[0])) {
                            selected = choice;
                        }
                    }
                    javafx.scene.control.ChoiceDialog<PhoneChoice> dialog =
                            new javafx.scene.control.ChoiceDialog<>(selected, choices);
                    dialog.setTitle("选择手机音乐");
                    dialog.setHeaderText("手机音乐\n“▶ 文件夹”会连续播放整个文件夹（随机或顺序）");
                    dialog.setContentText("手机音乐：");
                    if (owner != null && owner.isShowing()) {
                        dialog.initOwner(owner);
                    }
                    styleLike(dialog.getDialogPane(), owner);
                    dialog.showAndWait().ifPresent(picked -> {
                        onPick.accept(picked.id());
                        current[0] = picked.id();
                        value.setText(describePhone(source, picked.id()));
                    });
                }),
                error -> javafx.application.Platform.runLater(() ->
                        info(owner, "获取手机媒体库失败：" + (error == null ? "未知错误" : error.getMessage()))));
    }

    private static String describePhone(PhoneAudioSource source, String id) {
        if (id == null || id.isBlank()) {
            return "（不播放）";
        }
        var selection = com.studytimer.audio.PhoneFolderPlaylist.parse(id);
        if (selection.isPresent()) {
            return "▶ " + selection.get().label();
        }
        if (source != null) {
            String name = source.getPhoneAudioName(id);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return id;
    }

    private static void showAdvanced(Window owner, LifeGuardController controller) {
        Dialog<Void> dialog = newDialog(owner, "生命保障 - 高级");
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);

        Button questions = new Button("修改障碍问题与答案…");
        Button password = new Button("设置 / 修改密码…");
        Label pwState = new Label();
        pwState.getStyleClass().add("hint-text");
        Runnable refresh = () -> pwState.setText(controller.getSettings().hasPassword()
                ? "已设置密码（忘记后无法找回，只能手动删除数据文件夹里的 life-guard.json）"
                : "未设置密码");
        refresh.run();

        questions.setOnAction(e -> {
            Window w = dialog.getDialogPane().getScene().getWindow();
            LifeGuardStore.Settings s = controller.getSettings();
            if (s.enabled && !askFull(w, s, "修改障碍问题")) {
                return;
            }
            if (editQuestions(w, s)) {
                save(controller, s, w);
            }
        });
        password.setOnAction(e -> {
            Window w = dialog.getDialogPane().getScene().getWindow();
            LifeGuardStore.Settings s = controller.getSettings();
            if (s.enabled && !askFull(w, s, "修改密码")) {
                return;
            }
            if (editPassword(w, s)) {
                save(controller, s, w);
                refresh.run();
            }
        });

        Button disable = new Button("关闭生命保障…");
        disable.getStyleClass().add("danger-button");
        disable.setOnAction(e -> {
            Window w = dialog.getDialogPane().getScene().getWindow();
            LifeGuardStore.Settings s = controller.getSettings();
            if (!s.enabled) {
                info(w, "生命保障当前没有开启。");
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "生命保障是为了让你少坐一会儿、多活几年。\n确定要继续关闭流程吗？",
                    new ButtonType("继续", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
            confirm.initOwner(w);
            confirm.setHeaderText("关闭生命保障");
            styleLike(confirm.getDialogPane(), w);
            if (confirm.showAndWait().map(b -> b.getButtonData() != ButtonBar.ButtonData.OK_DONE).orElse(true)) {
                return;
            }
            if (!askFull(w, s, "关闭生命保障")) {
                return;
            }
            s.enabled = false;
            save(controller, s, w);
            AppLog.warn("生命保障已被关闭（通过完整障碍）");
            info(w, "生命保障已关闭。随时可以在“设置 → 生命保障”一键重新开启。");
        });
        Label dangerText = new Label("关闭需要回答三个问题、输入密码（如已设置），并等待 "
                + LifeGuardBarrier.COOLDOWN_SECONDS + " 秒。");
        dangerText.setWrapText(true);
        dangerText.getStyleClass().add("hint-text");
        TitledPane danger = new TitledPane("危险区", new VBox(10, dangerText, disable));
        danger.setExpanded(false);
        // 展开危险区时窗口跟着变高，避免内容被挡在窗口下方
        danger.expandedProperty().addListener((obs, o, open) -> javafx.application.Platform.runLater(() -> {
            Window w = dialog.getDialogPane().getScene().getWindow();
            if (w != null) {
                w.sizeToScene();
            }
        }));

        VBox box = new VBox(12, questions, password, pwState, danger);
        box.setPadding(new Insets(8));
        box.setPrefWidth(520);
        box.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        dialog.getDialogPane().setContent(box);
        dialog.showAndWait();
    }

    private static boolean editQuestions(Window owner, LifeGuardStore.Settings s) {
        Dialog<ButtonType> dialog = newDialog(owner, "障碍问题");
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        List<TextField> qs = new ArrayList<>();
        List<TextField> as = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LifeGuardStore.Question q = s.questions.get(i);
            TextField qf = new TextField(q.question);
            TextField af = new TextField(q.answer);
            af.setPromptText("留空 = 输入任意文字即可");
            qf.setPrefColumnCount(26);
            qs.add(qf);
            as.add(af);
            grid.addRow(i * 2, new Label("问题 " + (i + 1)), qf);
            grid.addRow(i * 2 + 1, new Label("答案"), af);
        }
        Label hint = new Label("问题 1 也用于修改时长和退出程序（简化障碍）。");
        hint.getStyleClass().add("hint-text");
        dialog.getDialogPane().setContent(new VBox(12, grid, hint));
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            String q = qs.get(i).getText().trim();
            if (!q.isEmpty()) {
                s.questions.set(i, new LifeGuardStore.Question(q, as.get(i).getText().trim()));
            }
        }
        return true;
    }

    private static boolean editPassword(Window owner, LifeGuardStore.Settings s) {
        Dialog<ButtonType> dialog = newDialog(owner, "生命保障密码");
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        PasswordField first = new PasswordField();
        PasswordField second = new PasswordField();
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("新密码"), first);
        grid.addRow(1, new Label("再输入一次"), second);
        Label hint = new Label("两栏都留空表示清除密码。可以请别人帮你设置。忘记密码无法找回。");
        hint.setWrapText(true);
        hint.getStyleClass().add("hint-text");
        Label error = new Label();
        error.getStyleClass().add("error-text");
        dialog.getDialogPane().setContent(new VBox(12, grid, hint, error));
        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            if (!first.getText().equals(second.getText())) {
                error.setText("两次输入的密码不一致");
                ev.consume();
            }
        });
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return false;
        }
        if (first.getText().isEmpty()) {
            s.passwordHash = null;
            s.passwordSalt = null;
        } else {
            String[] hashed = LifeGuardBarrier.hashNewPassword(first.getText());
            s.passwordHash = hashed[0];
            s.passwordSalt = hashed[1];
        }
        return true;
    }

    // ==================== 障碍 ====================

    /** 简化障碍：回答问题 1。 */
    public static boolean askSimple(Window owner, LifeGuardStore.Settings s, String action) {
        Dialog<ButtonType> dialog = newDialog(owner, action);
        dialog.setHeaderText("生命保障：" + action + "前，请先回答");
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        LifeGuardStore.Question q = s.questions.get(0);
        TextField field = new TextField();
        field.setPromptText(q.answer.isEmpty() ? "输入任意文字" : "输入你预设的答案");
        Label error = new Label();
        error.getStyleClass().add("error-text");
        Label question = new Label(q.question);
        question.setWrapText(true);
        VBox box = new VBox(10, question, field, error);
        box.setPrefWidth(420);
        dialog.getDialogPane().setContent(box);
        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            if (!LifeGuardBarrier.answerMatches(q.answer, field.getText())) {
                error.setText("答案不对");
                ev.consume();
            }
        });
        javafx.application.Platform.runLater(field::requestFocus);
        boolean passed = dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
        AppLog.info("生命保障简化障碍（" + action + "）：" + (passed ? "通过" : "放弃"));
        return passed;
    }

    /** 完整障碍：三个问题 + 密码（如已设置）+ 60 秒冷静期。 */
    public static boolean askFull(Window owner, LifeGuardStore.Settings s, String action) {
        Dialog<ButtonType> dialog = newDialog(owner, action);
        dialog.setHeaderText("生命保障：" + action);
        ButtonType confirmType = new ButtonType("确认" + action, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(confirmType, ButtonType.CANCEL);

        VBox box = new VBox(8);
        box.setPrefWidth(460);
        List<TextField> fields = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LifeGuardStore.Question q = s.questions.get(i);
            Label label = new Label((i + 1) + ". " + q.question);
            label.setWrapText(true);
            TextField field = new TextField();
            field.setPromptText(q.answer.isEmpty() ? "输入任意文字" : "输入你预设的答案");
            fields.add(field);
            box.getChildren().addAll(label, field);
        }
        PasswordField password = new PasswordField();
        if (s.hasPassword()) {
            box.getChildren().addAll(new Label("密码"), password);
        }
        Label countdown = new Label();
        countdown.getStyleClass().add("hint-text");
        Label error = new Label();
        error.getStyleClass().add("error-text");
        box.getChildren().addAll(countdown, error);
        dialog.getDialogPane().setContent(box);

        Button confirm = (Button) dialog.getDialogPane().lookupButton(confirmType);
        confirm.getStyleClass().add("danger-button");
        confirm.setDisable(true);
        int[] left = {LifeGuardBarrier.COOLDOWN_SECONDS};
        countdown.setText("冷静一下：" + left[0] + " 秒后才能确认");
        Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            left[0]--;
            if (left[0] > 0) {
                countdown.setText("冷静一下：" + left[0] + " 秒后才能确认");
            } else {
                countdown.setText("已经冷静了 " + LifeGuardBarrier.COOLDOWN_SECONDS + " 秒。");
                confirm.setDisable(false);
            }
        }));
        timer.setCycleCount(LifeGuardBarrier.COOLDOWN_SECONDS);
        timer.play();

        confirm.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            for (int i = 0; i < 3; i++) {
                if (!LifeGuardBarrier.answerMatches(s.questions.get(i).answer, fields.get(i).getText())) {
                    error.setText("第 " + (i + 1) + " 个问题的答案不对");
                    ev.consume();
                    return;
                }
            }
            if (!LifeGuardBarrier.passwordMatches(s, password.getText())) {
                error.setText("密码不对");
                ev.consume();
            }
        });
        boolean passed = dialog.showAndWait().orElse(ButtonType.CANCEL) == confirmType;
        timer.stop();
        AppLog.info("生命保障完整障碍（" + action + "）：" + (passed ? "通过" : "放弃"));
        return passed;
    }

    // ==================== 工具 ====================

    private static void save(LifeGuardController controller, LifeGuardStore.Settings s, Window owner) {
        try {
            controller.applySettings(s);
        } catch (java.io.IOException ex) {
            AppLog.error("保存生命保障设置失败", ex);
            Alert alert = new Alert(Alert.AlertType.ERROR, "保存失败：" + ex.getMessage(), ButtonType.OK);
            alert.initOwner(owner);
            alert.showAndWait();
        }
    }

    private static void info(Window owner, String text) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        alert.setHeaderText(null);
        alert.initOwner(owner);
        styleLike(alert.getDialogPane(), owner);
        alert.showAndWait();
    }

    private static <T> Dialog<T> newDialog(Window owner, String title) {
        Dialog<T> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setResizable(true);
        dialog.getDialogPane().setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        if (owner != null && owner.isShowing()) {
            dialog.initOwner(owner);
        }
        styleLike(dialog.getDialogPane(), owner);
        return dialog;
    }

    private static void styleLike(javafx.scene.control.DialogPane pane, Window owner) {
        if (owner != null && owner.getScene() != null) {
            pane.getStylesheets().addAll(owner.getScene().getStylesheets());
        } else {
            var css = LifeGuardDialogs.class.getResource("/com/studytimer/ui/theme.css");
            if (css != null) {
                pane.getStylesheets().add(css.toExternalForm());
            }
        }
    }
}
