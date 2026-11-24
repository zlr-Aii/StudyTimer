package com.studytimer.ui;

import com.studytimer.PhoneAudio;
import com.studytimer.PolarBellConnectionState;
import com.studytimer.model.TimerStage;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import com.studytimer.audio.PhoneFolderPlaylist;

import java.util.List;
import java.util.function.Consumer;

/**
 * Focus Timer 的计时与阶段表格 UI。
 *
 * 计时引擎、计划脏标记、状态保存、手机连接与提示入口都通过 {@link Host}
 * 交给 Main；本类只持有界面节点并负责把界面更新到屏幕。
 */
public class FocusTimerPane {

    /** 宿主提供的跨模块能力，避免复制计时/保存等业务逻辑。 */
    public interface Host {
        boolean isTimerRunning();

        void toggleTimer();

        void skipTimer();

        void resetTimer();

        void saveFocusSessionState();

        void setPlanDirty(boolean dirty);

        void showMessage(String message);

        void refreshTimerStages();

        String getPhoneAudioName(String audioId);

        boolean isPhoneConnected();

        void addPhoneStateListener(Consumer<PolarBellConnectionState> listener);

        void getPhoneAudioListAsync(
                Consumer<List<PhoneAudio>> onSuccess,
                Consumer<Exception> onError);
    }

    private final ObservableList<TimerStage> stages;
    private final Host host;

    private Label timeLabel;
    private Label currentStageLabel;
    private TimerRing timerRing;
    /** 正在计时的阶段下标，用于在阶段表格中高亮。 */
    private int currentStageIndex = 0;
    private Button startPauseButton;
    private Button phoneAudioButton;
    private TableView<TimerStage> stageTable;

    private HBox timerStrip;
    private HBox stageToolbar;
    private Label stageHint;

    public FocusTimerPane(ObservableList<TimerStage> stages, Host host) {
        this.stages = stages;
        this.host = host;
        buildControls();
    }

    public HBox getTimerStrip() {
        return timerStrip;
    }

    public TableView<TimerStage> getStageTable() {
        return stageTable;
    }

    public HBox getStageToolbar() {
        return stageToolbar;
    }

    public Label getStageHint() {
        return stageHint;
    }

    private Label timerHint;

    /** 显示或隐藏操作提示文字（“双击名称…”“圆环表示…”）。 */
    public void setHintsVisible(boolean visible) {
        for (Label hint : new Label[]{stageHint, timerHint}) {
            if (hint != null) {
                hint.setVisible(visible);
                hint.setManaged(visible);
            }
        }
    }

    /** 构建计时条、阶段表格、编辑工具栏与音频按钮。 */
    private void buildControls() {
        currentStageLabel = new Label("准备开始");
        currentStageLabel.getStyleClass().add("stage-chip");

        timeLabel = new Label("45:00");
        timeLabel.getStyleClass().add("timer-display");

        timerRing = new TimerRing(timeLabel, 82);

        stageTable = createStageTable();
        stageTable.setItems(stages);
        stageTable.setPrefHeight(250);

        Button addButton = new Button("＋ 添加阶段");
        Button removeButton = new Button("删除");
        Button upButton = new Button("上移");
        Button downButton = new Button("下移");
        addButton.getStyleClass().add("accent-button");
        removeButton.getStyleClass().add("danger-button");

        addButton.setOnAction(e -> addStage());
        removeButton.setOnAction(e -> removeStage());
        upButton.setOnAction(e -> moveStage(-1));
        downButton.setOnAction(e -> moveStage(1));

        Button chooseMusicButton = new Button("设置阶段音乐");
        Button clearMusicButton = new Button("清除阶段音乐");
        Button leadTimeButton = new Button("设置提前播放时间");
        Button choosePhoneAudioButton = new Button("设置手机音频");
        Button clearPhoneAudioButton = new Button("清除手机音频");
        Button phoneVolumeButton = new Button("手机音量");
        phoneVolumeButton.setOnAction(e -> setStagePhoneVolume());
        phoneAudioButton = choosePhoneAudioButton;

        // 根据手机连接状态控制音频选择按钮
        setPhoneConnected(host.isPhoneConnected());

        // 监听手机连接状态变化
        host.addPhoneStateListener(state -> Platform.runLater(() ->
                setPhoneConnected(state == PolarBellConnectionState.CONNECTED)
        ));

        chooseMusicButton.setOnAction(e -> chooseStageMusic());
        clearMusicButton.setOnAction(e -> clearStageMusic());
        leadTimeButton.setOnAction(e -> setStageAudioLeadTime());
        choosePhoneAudioButton.setOnAction(e -> {
            if (!host.isPhoneConnected()) {
                host.showMessage("手机尚未连接，请检查 USB 和 ADB 转发。");
                return;
            }

            if (host.isTimerRunning()) {
                host.showMessage("请先暂停计时，再配置手机音频。");
                return;
            }

            TimerStage selected =
                    stageTable.getSelectionModel().getSelectedItem();

            if (selected == null) {
                host.showMessage("请先选择一个时间阶段。");
                return;
            }

            choosePhoneAudio(selected);
        });

        clearPhoneAudioButton.setOnAction(e -> {
            if (host.isTimerRunning()) {
                host.showMessage("请先暂停计时，再配置手机音频。");
                return;
            }

            TimerStage selected =
                    stageTable.getSelectionModel().getSelectedItem();

            if (selected == null) {
                host.showMessage("请先选择一个时间阶段。");
                return;
            }

            selected.setPhoneAudioId(null);
            stageTable.refresh();
            host.setPlanDirty(true);

            host.showMessage("已清除该阶段的手机音频。");
        });

        startPauseButton = new Button("开始");
        startPauseButton.setDefaultButton(true);
        startPauseButton.getStyleClass().add("primary-button");
        startPauseButton.setPrefWidth(120);
        startPauseButton.setOnAction(e -> host.toggleTimer());

        Button skipButton = new Button("跳过");
        skipButton.setOnAction(e -> {
            host.skipTimer();
            refreshButtonText();
            host.saveFocusSessionState();
        });

        Button resetButton = new Button("重置");
        resetButton.setOnAction(e -> {
            host.resetTimer();
            refreshButtonText();
            host.saveFocusSessionState();
        });

        HBox controls = new HBox(10, startPauseButton, skipButton, resetButton);
        controls.setAlignment(Pos.CENTER_LEFT);

        timerHint = new Label("圆环表示当前阶段已用时间");
        timerHint.getStyleClass().add("hint-text");

        VBox timerInfo = new VBox(12, currentStageLabel, controls, timerHint);
        timerInfo.setAlignment(Pos.CENTER_LEFT);

        timerStrip = new HBox(28, timerRing, timerInfo);
        timerStrip.setAlignment(Pos.CENTER_LEFT);
        timerStrip.setPadding(new Insets(16, 20, 16, 20));
        timerStrip.getStyleClass().add("timer-strip");

        stageTable.setPrefHeight(Region.USE_COMPUTED_SIZE);
        stageTable.setMinHeight(180);
        stageTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(stageTable, Priority.ALWAYS);

        MenuButton audioMenu = new MenuButton("阶段音频");
        audioMenu.getItems().addAll(
                MainView.menuItem("设置电脑音乐…", chooseMusicButton),
                MainView.menuItem("清除电脑音乐", clearMusicButton),
                MainView.menuItem("提前播放时间…", leadTimeButton),
                new SeparatorMenuItem(),
                MainView.menuItem("设置手机音频…", choosePhoneAudioButton),
                MainView.menuItem("清除手机音频", clearPhoneAudioButton),
                MainView.menuItem("手机音量…", phoneVolumeButton)
        );

        Region stageToolbarSpacer = new Region();
        HBox.setHgrow(stageToolbarSpacer, Priority.ALWAYS);

        // 末尾的 spacer 之后由 Main 追加快照「保存计划」按钮
        stageToolbar = new HBox(8, addButton, upButton, downButton, audioMenu,
                removeButton, stageToolbarSpacer);
        stageToolbar.setAlignment(Pos.CENTER_LEFT);

        stageHint = new Label("双击名称、时长或提前播放可直接修改；选中阶段后可设置音频");
        stageHint.getStyleClass().add("hint-text");
    }

    private void setPhoneConnected(boolean connected) {
        if (phoneAudioButton != null) {
            phoneAudioButton.setDisable(!connected);
        }
    }

    private TableView<TimerStage> createStageTable() {
        TableView<TimerStage> table = new TableView<>();
        table.setEditable(true);

        TableColumn<TimerStage, String> nameColumn = new TableColumn<>("阶段名称");
        nameColumn.setCellValueFactory(data -> data.getValue().nameProperty());
        nameColumn.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        nameColumn.setOnEditCommit(event -> {
            event.getRowValue().setName(event.getNewValue());
            refreshTimerStages();
            host.setPlanDirty(true);
        });
        nameColumn.setPrefWidth(150);

        TableColumn<TimerStage, Integer> minutesColumn = new TableColumn<>("时长（分钟）");
        minutesColumn.setCellValueFactory(data -> data.getValue().minutesProperty().asObject());
        minutesColumn.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn(
                new javafx.util.converter.IntegerStringConverter()));
        minutesColumn.setOnEditCommit(event -> {
            Integer value = event.getNewValue();
            if (value == null || value <= 0) {
                value = 1;
            }
            event.getRowValue().setMinutes(value);
            refreshTimerStages();
            host.setPlanDirty(true);
        });
        minutesColumn.setPrefWidth(90);

        // 音乐文件列
        TableColumn<TimerStage, String> musicColumn =
                new TableColumn<>("音乐文件");

        musicColumn.setCellValueFactory(
                data -> data.getValue().audioFileProperty()
        );

        // 不显示完整路径，只显示文件名
        musicColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String path, boolean empty) {
                super.updateItem(path, empty);

                if (empty || path == null || path.isBlank()) {
                    setText(empty ? null : "未设置");
                } else {
                    try {
                        setText(Path.of(path).getFileName().toString());
                    } catch (RuntimeException e) {
                        setText(path);
                    }
                }
            }
        });

        musicColumn.setPrefWidth(150);

        // 提前播放时间列
        TableColumn<TimerStage, Integer> leadColumn =
                new TableColumn<>("提前播放");

        leadColumn.setCellValueFactory(
                data -> data.getValue().audioLeadSecondsProperty().asObject()
        );

        leadColumn.setCellFactory(
                javafx.scene.control.cell.TextFieldTableCell.forTableColumn(
                        new javafx.util.converter.IntegerStringConverter()
                )
        );

        leadColumn.setOnEditCommit(event -> {
            Integer value = event.getNewValue();

            if (value == null || value < 1) {
                value = 1;
            }

            event.getRowValue().setAudioLeadSeconds(value);
            refreshTimerStages();
            host.setPlanDirty(true);
        });

        leadColumn.setPrefWidth(100);

        // 手机音频列
        TableColumn<TimerStage, String> phoneAudioColumn =
                new TableColumn<>("手机音频");

        phoneAudioColumn.setCellValueFactory(
                data -> data.getValue().phoneAudioIdProperty()
        );

        // 显示音频名称，而不是 audioId
        phoneAudioColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String audioId, boolean empty) {
                super.updateItem(audioId, empty);

                if (empty) {
                    setText(null);
                } else {
                    String text = host.getPhoneAudioName(audioId);
                    TimerStage stage = getTableRow() == null ? null : getTableRow().getItem();
                    if (audioId != null && stage != null && stage.getPhoneVolume() < 100) {
                        text += " · " + stage.getPhoneVolume() + "%";
                    }
                    setText(text);
                }
            }
        });

        phoneAudioColumn.setPrefWidth(180);

        // 顺序列
        TableColumn<TimerStage, Number> orderColumn =
                new TableColumn<>("顺序");

        orderColumn.setCellValueFactory(data ->
                new ReadOnlyIntegerWrapper(
                        table.getItems().indexOf(data.getValue()) + 1
                )
        );

        orderColumn.setPrefWidth(70);

        table.getColumns().addAll(
                nameColumn,
                minutesColumn,
                musicColumn,
                leadColumn,
                phoneAudioColumn,
                orderColumn
        );
        table.setPlaceholder(new Label("请添加时间阶段"));

        // 当前阶段高亮，已完成阶段变灰
        javafx.css.PseudoClass currentPseudo = javafx.css.PseudoClass.getPseudoClass("current-stage");
        javafx.css.PseudoClass donePseudo = javafx.css.PseudoClass.getPseudoClass("done-stage");
        table.setRowFactory(view -> new TableRow<>() {
            @Override
            protected void updateItem(TimerStage stage, boolean empty) {
                super.updateItem(stage, empty);
                int index = getIndex();
                boolean valid = !empty && stage != null;
                pseudoClassStateChanged(currentPseudo, valid && index == currentStageIndex);
                pseudoClassStateChanged(donePseudo, valid && index < currentStageIndex);
            }
        });
        return table;
    }

    /**
     * 为当前选中的阶段选择音乐文件
     */
    private void chooseStageMusic() {
        if (host.isTimerRunning()) {
            host.showMessage("请先暂停计时，再配置音乐。");
            return;
        }

        TimerStage selected = stageTable.getSelectionModel().getSelectedItem();

        if (selected == null) {
            host.showMessage("请先选择一个时间阶段。");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择阶段音乐");

        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        "音频文件",
                        "*.mp3",
                        "*.flac"
                )
        );

        File file = fileChooser.showOpenDialog(stageTable.getScene().getWindow());

        if (file == null) {
            return;
        }

        selected.setAudioFile(
                file.toPath().toAbsolutePath().normalize().toString()
        );

        refreshTimerStages();
        host.setPlanDirty(true);
        host.showMessage("已设置音乐：" + file.getName());
    }

    /**
     * 加载默认阶段计划
     */
    public void loadDefaultStages() {
        stages.setAll(
                new TimerStage("专注", 45),
                new TimerStage("休息", 10),
                new TimerStage("专注", 45),
                new TimerStage("休息", 25),
                new TimerStage("专注", 45),
                new TimerStage("休息", 10),
                new TimerStage("专注", 45),
                new TimerStage("休息", 10),
                new TimerStage("专注", 45)
        );
    }

    /**
     * 清除当前阶段的音乐
     */
    private void clearStageMusic() {
        if (host.isTimerRunning()) {
            host.showMessage("请先暂停计时，再配置音乐。");
            return;
        }

        TimerStage selected = stageTable.getSelectionModel().getSelectedItem();

        if (selected == null) {
            host.showMessage("请先选择一个时间阶段。");
            return;
        }

        selected.setAudioFile("");

        refreshTimerStages();
        host.showMessage("已清除该阶段的音乐。");
    }

    /**
     * 设置当前阶段提前多少秒播放音乐
     */
    private void setStageAudioLeadTime() {
        if (host.isTimerRunning()) {
            host.showMessage("请先暂停计时，再配置音乐。");
            return;
        }

        TimerStage selected = stageTable.getSelectionModel().getSelectedItem();

        if (selected == null) {
            host.showMessage("请先选择一个时间阶段。");
            return;
        }

        TextInputDialog dialog = new TextInputDialog(
                String.valueOf(selected.getAudioLeadSeconds())
        );

        dialog.setTitle("音乐播放时间");
        dialog.setHeaderText("设置音乐提前播放时间");
        dialog.setContentText("阶段结束前多少秒开始播放？");

        dialog.showAndWait().ifPresent(value -> {
            try {
                int seconds = Integer.parseInt(value.trim());

                if (seconds < 1) {
                    host.showMessage("提前播放时间必须大于等于 1 秒。");
                    return;
                }

                selected.setAudioLeadSeconds(seconds);

                refreshTimerStages();
                host.setPlanDirty(true);
                host.showMessage("已设置为提前 " + seconds + " 秒播放。");

            } catch (NumberFormatException e) {
                host.showMessage("请输入有效的整数秒数。");
            }
        });
    }

    /** 设置当前阶段的手机播放音量（0-100，按手机媒体音量的百分比）。 */
    private void setStagePhoneVolume() {
        if (host.isTimerRunning()) {
            host.showMessage("请先暂停计时，再配置手机音频。");
            return;
        }

        TimerStage selected = stageTable.getSelectionModel().getSelectedItem();

        if (selected == null) {
            host.showMessage("请先选择一个时间阶段。");
            return;
        }

        TextInputDialog dialog = new TextInputDialog(
                String.valueOf(selected.getPhoneVolume())
        );
        dialog.initOwner(stageTable.getScene().getWindow());
        dialog.setTitle("手机音量");
        dialog.setHeaderText("设置该阶段在手机上的播放音量");
        dialog.setContentText("音量（0-100，按手机媒体音量的百分比）：");

        dialog.showAndWait().ifPresent(value -> {
            try {
                int volume = Integer.parseInt(value.trim());
                if (volume < 0 || volume > 100) {
                    host.showMessage("音量必须在 0 到 100 之间。");
                    return;
                }
                selected.setPhoneVolume(volume);
                stageTable.refresh();
                host.setPlanDirty(true);
                host.showMessage("已设置该阶段的手机音量为 " + volume + "%。");
            } catch (NumberFormatException e) {
                host.showMessage("请输入 0 到 100 之间的整数。");
            }
        });
    }

    private void addStage() {
        if (host.isTimerRunning()) {
            host.showMessage("请先暂停计时，再编辑时间安排。");
            return;
        }
        stages.add(new TimerStage("新阶段", 10));
        refreshTimerStages();
        host.setPlanDirty(true);
        stageTable.getSelectionModel().selectLast();
        stageTable.edit(stages.size() - 1, stageTable.getColumns().get(0));
    }

    private void removeStage() {
        if (host.isTimerRunning()) {
            host.showMessage("请先暂停计时，再编辑时间安排。");
            return;
        }
        TimerStage selected = stageTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            stages.remove(selected);
            refreshTimerStages();
            host.setPlanDirty(true);
            updateClock(stages.isEmpty() ? 0 : stages.get(0).getMinutes() * 60L, 0, stages.size());
        }
    }

    private void moveStage(int direction) {
        if (host.isTimerRunning()) {
            host.showMessage("请先暂停计时，再编辑时间安排。");
            return;
        }
        int index = stageTable.getSelectionModel().getSelectedIndex();
        int target = index + direction;
        if (index < 0 || target < 0 || target >= stages.size()) return;

        TimerStage item = stages.remove(index);
        stages.add(target, item);
        stageTable.getSelectionModel().select(target);
        refreshTimerStages();
        host.setPlanDirty(true);
        if (!stages.isEmpty()) {
            updateClock(stages.get(0).getMinutes() * 60L, 0, stages.size());
        }
    }

    private void setCurrentStageIndex(int index) {
        if (index != currentStageIndex) {
            currentStageIndex = index;
            if (stageTable != null) {
                stageTable.refresh();
            }
        }
    }

    /** 开始/暂停按钮文案随计时状态变化。 */
    public void refreshButtonText() {
        startPauseButton.setText(host.isTimerRunning() ? "暂停" : "开始 / 继续");
    }

    /** 重置为「准备开始」，供计划切换等外部操作调用。 */
    public void resetStageLabel() {
        currentStageLabel.setText("准备开始");
    }

    /** 由 Focus Session 恢复等外部操作设置阶段标签文案。 */
    public void setCurrentStageText(String text) {
        currentStageLabel.setText(text);
    }

    public String getTimeText() {
        return timeLabel.getText();
    }

    public String getCurrentStageText() {
        return currentStageLabel.getText();
    }

    public void updateClock(long remainingSeconds, int currentIndex, int total) {
        Platform.runLater(() -> {
            long minutes = remainingSeconds / 60;
            long seconds = remainingSeconds % 60;
            timeLabel.setText(String.format("%02d:%02d", minutes, seconds));
            if (currentIndex >= 0 && currentIndex < stages.size()) {
                long stageSeconds = stages.get(currentIndex).getMinutes() * 60L;
                timerRing.setProgress(stageSeconds <= 0 ? 0
                        : 1.0 - (double) remainingSeconds / stageSeconds);
                setCurrentStageIndex(currentIndex);
            }
        });
    }

    public void onStageChanged(TimerStage stage, int index, int total) {
        Platform.runLater(() -> {
            currentStageLabel.setText(stage.getName());
            setCurrentStageIndex(index);
            java.awt.Toolkit.getDefaultToolkit().beep();
        });
    }

    /** 全部完成时的界面更新（音频提示由 Main 触发）。 */
    public void showFinished() {
        Platform.runLater(() -> {
            currentStageLabel.setText("计划已完成");
            timeLabel.setText("00:00");
            timerRing.setProgress(1);
            setCurrentStageIndex(stages.size());
            startPauseButton.setText("已完成");

            // 暂时保留电脑原有提示音，方便测试时作为备用
            java.awt.Toolkit.getDefaultToolkit().beep();
            java.awt.Toolkit.getDefaultToolkit().beep();
        });
    }

    private void choosePhoneAudio(TimerStage stage) {

        host.getPhoneAudioListAsync(
                audios -> Platform.runLater(() -> {

                    if (audios == null || audios.isEmpty()) {
                        host.showMessage("手机媒体库为空，请先在手机端导入音频。");
                        return;
                    }

                    // 刷新阶段表格中的手机音频名称
                    if (stageTable != null) {
                        stageTable.refresh();
                    }

                    // 按文件夹分组：未分类在前，其余按手机上的顺序；名称前标出文件夹
                    List<String> folderOrder = new java.util.ArrayList<>();
                    if (audios.stream().anyMatch(a -> a.getFolder().isEmpty())) {
                        folderOrder.add("");
                    }
                    for (PhoneAudio audio : audios) {
                        if (!folderOrder.contains(audio.getFolder())) {
                            folderOrder.add(audio.getFolder());
                        }
                    }
                    List<PhoneAudioChoice> choices = new java.util.ArrayList<>();
                    PhoneAudioChoice current = null;
                    // 先列出“整个文件夹 随机/顺序”，再列出单个音频
                    for (String folder : folderOrder) {
                        for (PhoneFolderPlaylist.Mode mode : PhoneFolderPlaylist.Mode.values()) {
                            PhoneFolderPlaylist.Selection selection =
                                    new PhoneFolderPlaylist.Selection(mode, folder);
                            PhoneAudioChoice choice = new PhoneAudioChoice(
                                    selection.toId(), "▶ " + selection.label());
                            choices.add(choice);
                            if (choice.id().equals(stage.getPhoneAudioId())) {
                                current = choice;
                            }
                        }
                    }
                    for (String folder : folderOrder) {
                        for (PhoneAudio audio : audios) {
                            if (audio.getFolder().equals(folder)) {
                                String folderName = folder.isEmpty() ? "未分类" : folder;
                                PhoneAudioChoice choice = new PhoneAudioChoice(
                                        audio.getAudioId(), folderName + " / " + audio.getName());
                                choices.add(choice);
                                if (audio.getAudioId().equals(stage.getPhoneAudioId())) {
                                    current = choice;
                                }
                            }
                        }
                    }
                    if (current == null) {
                        // 默认选第一个单曲
                        current = choices.get(folderOrder.size() * PhoneFolderPlaylist.Mode.values().length);
                    }

                    ChoiceDialog<PhoneAudioChoice> dialog =
                            new ChoiceDialog<>(current, choices);

                    dialog.setTitle("选择手机音频");
                    dialog.setHeaderText("请选择该阶段播放的手机音频\n"
                            + "“▶ 文件夹”每次阶段开始时从该文件夹随机或按顺序选一首");
                    dialog.setContentText("手机音频：");

                    dialog.initOwner(stageTable.getScene().getWindow());

                    dialog.showAndWait().ifPresent(selected -> {

                        stage.setPhoneAudioId(selected.id());

                        stageTable.refresh();
                        host.setPlanDirty(true);

                        System.out.println("已设置手机音频：" + selected + " (" + selected.id() + ")");
                    });
                }),

                error -> Platform.runLater(() ->
                        host.showMessage("获取手机媒体库失败：" + error.getMessage())
                )
        );
    }

    private void refreshPhoneAudioNamesAsync() {

        host.getPhoneAudioListAsync(
                audios -> Platform.runLater(() -> {

                    if (stageTable != null) {
                        stageTable.refresh();
                    }

                    System.out.println(
                            "手机媒体库名称缓存已刷新，共 "
                                    + audios.size() + " 条"
                    );
                }),

                error -> System.err.println(
                        "刷新手机媒体库失败：" + error.getMessage()
                )
        );
    }

    /** 选择框里的一项：显示“文件夹 / 名称”。 */
    private record PhoneAudioChoice(String id, String text) {
        @Override
        public String toString() {
            return text;
        }
    }

    /** 将当前阶段列表同步到计时引擎。 */
    public void refreshTimerStages() {
        host.refreshTimerStages();
    }
}
