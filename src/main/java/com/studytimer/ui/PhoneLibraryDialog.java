package com.studytimer.ui;

import com.studytimer.PhoneAudio;
import com.studytimer.PolarBellConnectionState;
import com.studytimer.PolarBellService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 在电脑端管理手机 PolarBell 的音频库：左边文件夹，右边音频；上传、重命名、移动、删除、试听。
 * 需要手机端能力 LIBRARY_MANAGE（管理）和 LIB_FOLDERS（文件夹）；旧版 App 只能浏览和试听。
 */
public final class PhoneLibraryDialog {

    /** 左侧列表项：ALL / UNCATEGORIZED / 具体文件夹。 */
    private record FolderEntry(String folder, String label) {
        static final String ALL = "\u0000all";

        boolean isAll() {
            return ALL.equals(folder);
        }

        boolean isRealFolder() {
            return !isAll() && !folder.isEmpty();
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final PolarBellService polarBell;
    private final Dialog<Void> dialog = new Dialog<>();
    private final ListView<FolderEntry> folderList = new ListView<>();
    private final ListView<PhoneAudio> list = new ListView<>();
    private final Label statusLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button uploadButton = new Button("＋ 上传音频…");
    private final Button renameButton = new Button("重命名");
    private final Button moveButton = new Button("移动到…");
    private final Button deleteButton = new Button("删除");
    private final Button newFolderButton = new Button("＋ 文件夹");
    private final Button renameFolderButton = new Button("改名");
    private final Button deleteFolderButton = new Button("删除");
    private List<PhoneAudio> allAudios = List.of();
    private boolean busy;
    private final PcPreviewPlayer pcPlayer;

    private PhoneLibraryDialog(Window owner, PolarBellService polarBell) {
        this.polarBell = polarBell;

        dialog.setTitle("手机音频库");
        dialog.setHeaderText("管理 PolarBell 手机上的音频，阶段的“手机音频”从这里选择");
        if (owner != null) {
            dialog.initOwner(owner);
            if (owner.getScene() != null) {
                dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
            }
        }
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);

        folderList.setPrefWidth(170);
        folderList.setPrefHeight(340);
        folderList.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> {
            showAudios();
            updateButtons();
        });

        list.setPlaceholder(new Label("这里还没有音频"));
        list.setPrefHeight(340);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(PhoneAudio audio, boolean empty) {
                super.updateItem(audio, empty);
                if (empty || audio == null) {
                    setText(null);
                } else {
                    String folder = isAllSelected() && !audio.getFolder().isEmpty()
                            ? "  [" + audio.getFolder() + "]" : "";
                    setText(audio.getName() + folder + "    " + formatDuration(audio.getDuration())
                            + " · " + formatSize(audio.getFileSize()));
                }
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> updateButtons());

        uploadButton.getStyleClass().add("accent-button");
        deleteButton.getStyleClass().add("danger-button");
        deleteFolderButton.getStyleClass().add("danger-button");
        uploadButton.setOnAction(e -> upload());
        renameButton.setOnAction(e -> rename());
        moveButton.setOnAction(e -> moveAudio());
        deleteButton.setOnAction(e -> delete());
        newFolderButton.setOnAction(e -> createFolder());
        renameFolderButton.setOnAction(e -> renameFolder());
        deleteFolderButton.setOnAction(e -> deleteFolder());

        HBox folderToolbar = new HBox(6, newFolderButton, renameFolderButton, deleteFolderButton);
        folderToolbar.setAlignment(Pos.CENTER_LEFT);
        VBox folderColumn = new VBox(8, folderList, folderToolbar);

        HBox toolbar = new HBox(8, uploadButton, renameButton, moveButton, deleteButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        VBox audioColumn = new VBox(8, list, toolbar);
        HBox.setHgrow(audioColumn, Priority.ALWAYS);

        HBox columns = new HBox(12, folderColumn, audioColumn);

        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        statusLabel.getStyleClass().add("hint-text");
        statusLabel.setWrapText(true);

        pcPlayer = new PcPreviewPlayer(polarBell, previewCacheDirectory(),
                new PcPreviewPlayer.Source() {
                    @Override
                    public PhoneAudio selected() {
                        return list.getSelectionModel().getSelectedItem();
                    }

                    @Override
                    public List<PhoneAudio> visible() {
                        return List.copyOf(list.getItems());
                    }
                },
                statusLabel::setText);
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
                pcPlayer.playSelected();
            }
        });
        dialog.setOnHidden(e -> pcPlayer.close());

        VBox content = new VBox(10, columns, pcPlayer.getView(), progressBar, statusLabel);
        content.setPadding(new Insets(12));
        content.setPrefWidth(820);
        dialog.getDialogPane().setContent(content);

        updateButtons();
        reload();
    }

    public static void show(Window owner, PolarBellService polarBell) {
        new PhoneLibraryDialog(owner, polarBell).dialog.show();
    }

    private boolean canManage() {
        return polarBell.isLibraryManageSupported();
    }

    private boolean canFolders() {
        return polarBell.isLibraryFoldersSupported();
    }

    private boolean isAllSelected() {
        FolderEntry entry = folderList.getSelectionModel().getSelectedItem();
        return entry == null || entry.isAll();
    }

    /** 当前选中的文件夹："" = 未分类；选“全部”时也按未分类处理（上传位置）。 */
    private String selectedFolder() {
        FolderEntry entry = folderList.getSelectionModel().getSelectedItem();
        return entry == null || entry.isAll() ? "" : entry.folder();
    }

    private void updateButtons() {
        boolean connected = polarBell.getConnectionState() == PolarBellConnectionState.CONNECTED;
        boolean selected = list.getSelectionModel().getSelectedItem() != null;
        FolderEntry folder = folderList.getSelectionModel().getSelectedItem();
        boolean realFolder = folder != null && folder.isRealFolder();
        boolean manage = !busy && connected && canManage();
        uploadButton.setDisable(!manage);
        renameButton.setDisable(!manage || !selected);
        deleteButton.setDisable(!manage || !selected);
        moveButton.setDisable(!manage || !canFolders() || !selected);
        newFolderButton.setDisable(!manage || !canFolders());
        renameFolderButton.setDisable(!manage || !canFolders() || !realFolder);
        deleteFolderButton.setDisable(!manage || !canFolders() || !realFolder);
        if (pcPlayer != null) {
            pcPlayer.updateButtons();
        }
    }

    /** 电脑试听的缓存：%APPDATA%\StudyTimer\audio-cache\phone（按 audioId 命名）。 */
    private static java.nio.file.Path previewCacheDirectory() {
        String appData = System.getenv("APPDATA");
        java.nio.file.Path base = appData == null || appData.isBlank()
                ? java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "StudyTimer")
                : java.nio.file.Path.of(appData, "StudyTimer");
        return base.resolve("audio-cache").resolve("phone");
    }

    private void setBusy(boolean value) {
        busy = value;
        progressBar.setVisible(value);
        progressBar.setManaged(value);
        updateButtons();
    }

    private void reload() {
        if (polarBell.getConnectionState() != PolarBellConnectionState.CONNECTED) {
            statusLabel.setText("手机未连接，请检查 USB、ADB 转发和手机端 PolarBell。");
            return;
        }
        statusLabel.setText("正在读取手机音频库…");
        polarBell.getAudioListAsync(
                audios -> {
                    List<String> folders = polarBell.getFolders();
                    Platform.runLater(() -> showLibrary(audios, folders));
                },
                error -> Platform.runLater(() -> {
                    statusLabel.setText("读取手机音频库失败：" + error.getMessage());
                    updateButtons();
                }));
    }

    private void showLibrary(List<PhoneAudio> audios, List<String> folders) {
        allAudios = List.copyOf(audios);
        FolderEntry previous = folderList.getSelectionModel().getSelectedItem();
        String previousFolder = previous == null ? FolderEntry.ALL : previous.folder();

        List<FolderEntry> entries = new ArrayList<>();
        entries.add(new FolderEntry(FolderEntry.ALL, "全部（" + audios.size() + "）"));
        entries.add(new FolderEntry("", "未分类（" + count("") + "）"));
        for (String folder : folders) {
            entries.add(new FolderEntry(folder, folder + "（" + count(folder) + "）"));
        }
        folderList.getItems().setAll(entries);
        FolderEntry select = entries.get(0);
        for (FolderEntry entry : entries) {
            if (entry.folder().equals(previousFolder)) {
                select = entry;
            }
        }
        folderList.getSelectionModel().select(select);
        showAudios();

        long total = audios.stream().mapToLong(PhoneAudio::getFileSize).sum();
        String text = audios.size() + " 个音频 · " + formatSize(total);
        if (!canManage()) {
            text += "。手机端 App 版本较旧，只能浏览和试听；更新后可在这里上传、重命名和删除。";
        } else if (!canFolders()) {
            text += "。手机端 App 版本较旧，不支持文件夹；更新后可分文件夹管理。";
        }
        statusLabel.setText(text);
        updateButtons();
    }

    private long count(String folder) {
        return allAudios.stream().filter(a -> a.getFolder().equals(folder)).count();
    }

    private void showAudios() {
        PhoneAudio selected = list.getSelectionModel().getSelectedItem();
        String selectedId = selected == null ? null : selected.getAudioId();
        FolderEntry entry = folderList.getSelectionModel().getSelectedItem();
        List<PhoneAudio> shown = entry == null || entry.isAll()
                ? allAudios
                : allAudios.stream().filter(a -> a.getFolder().equals(entry.folder())).toList();
        list.getItems().setAll(shown);
        if (selectedId != null) {
            for (PhoneAudio audio : shown) {
                if (audio.getAudioId().equals(selectedId)) {
                    list.getSelectionModel().select(audio);
                }
            }
        }
    }

    // ==================== 音频 ====================

    private void upload() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要传到手机的音频");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "音频文件", "*.mp3", "*.m4a", "*.aac", "*.wav", "*.flac", "*.ogg"));
        List<File> files = chooser.showOpenMultipleDialog(dialog.getDialogPane().getScene().getWindow());
        if (files == null || files.isEmpty()) {
            return;
        }
        setBusy(true);
        uploadNext(files, selectedFolder(), 0, 0);
    }

    private void uploadNext(List<File> files, String folder, int index, int succeeded) {
        if (index >= files.size()) {
            setBusy(false);
            reload();
            String where = folder.isEmpty() ? "" : "（" + folder + "）";
            Platform.runLater(() -> statusLabel.setText(
                    "已上传 " + succeeded + "/" + files.size() + " 个音频到手机" + where));
            return;
        }
        File file = files.get(index);
        String prefix = "(" + (index + 1) + "/" + files.size() + ") " + file.getName();
        statusLabel.setText("正在上传 " + prefix + "…");
        progressBar.setProgress(0);

        polarBell.uploadToLibraryAsync(
                file.toPath(),
                folder,
                (sent, total) -> Platform.runLater(() ->
                        progressBar.setProgress(total <= 0 ? 0 : (double) sent / total)),
                audioId -> Platform.runLater(() -> uploadNext(files, folder, index + 1, succeeded + 1)),
                error -> Platform.runLater(() -> {
                    showError("上传失败：" + file.getName(), error);
                    uploadNext(files, folder, index + 1, succeeded);
                }));
    }

    private void rename() {
        PhoneAudio selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        TextInputDialog input = new TextInputDialog(selected.getName());
        input.initOwner(dialog.getDialogPane().getScene().getWindow());
        input.setTitle("重命名");
        input.setHeaderText("只修改显示名称，已设置该音频的阶段不受影响");
        input.setContentText("新名称：");
        input.showAndWait().map(String::trim).filter(name -> !name.isEmpty()).ifPresent(name -> {
            setBusy(true);
            polarBell.renameLibraryAudioAsync(selected.getAudioId(), name,
                    this::doneAndReload, error -> failed("重命名失败", error));
        });
    }

    private void moveAudio() {
        PhoneAudio selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        List<String> choices = new ArrayList<>();
        choices.add("未分类");
        choices.addAll(polarBell.getFolders());
        String current = selected.getFolder().isEmpty() ? "未分类" : selected.getFolder();
        ChoiceDialog<String> choose = new ChoiceDialog<>(current, choices);
        choose.initOwner(dialog.getDialogPane().getScene().getWindow());
        choose.setTitle("移动到文件夹");
        choose.setHeaderText("移动“" + selected.getName() + "”，已设置该音频的阶段不受影响");
        choose.setContentText("文件夹：");
        Optional<String> result = choose.showAndWait();
        if (result.isEmpty() || result.get().equals(current)) {
            return;
        }
        String target = "未分类".equals(result.get()) ? "" : result.get();
        setBusy(true);
        polarBell.folderOperationAsync(
                client -> client.moveAudioToFolder(selected.getAudioId(), target),
                this::doneAndReload, error -> failed("移动失败", error));
    }

    private void delete() {
        PhoneAudio selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        if (!confirm("删除手机音频",
                "从手机删除“" + selected.getName() + "”？\n使用该音频的阶段将不再播放手机声音。")) {
            return;
        }
        setBusy(true);
        polarBell.deleteLibraryAudioAsync(selected.getAudioId(),
                this::doneAndReload, error -> failed("删除失败", error));
    }

    // ==================== 文件夹 ====================

    private Optional<String> askFolderName(String title, String initial) {
        TextInputDialog input = new TextInputDialog(initial);
        input.initOwner(dialog.getDialogPane().getScene().getWindow());
        input.setTitle(title);
        input.setHeaderText("文件夹只有一层；名称不能包含冒号，最多 40 个字");
        input.setContentText("名称：");
        return input.showAndWait().map(String::trim).filter(name -> !name.isEmpty());
    }

    private void createFolder() {
        askFolderName("新建文件夹", "").ifPresent(name -> {
            setBusy(true);
            polarBell.folderOperationAsync(client -> client.createFolder(name),
                    () -> Platform.runLater(() -> {
                        selectFolderAfterReload(name);
                        doneAndReload();
                    }),
                    error -> failed("新建文件夹失败", error));
        });
    }

    private void renameFolder() {
        FolderEntry entry = folderList.getSelectionModel().getSelectedItem();
        if (entry == null || !entry.isRealFolder()) {
            return;
        }
        askFolderName("重命名文件夹", entry.folder()).ifPresent(name -> {
            setBusy(true);
            polarBell.folderOperationAsync(client -> client.renameFolder(entry.folder(), name),
                    () -> Platform.runLater(() -> {
                        selectFolderAfterReload(name);
                        doneAndReload();
                    }),
                    error -> failed("重命名文件夹失败", error));
        });
    }

    private void deleteFolder() {
        FolderEntry entry = folderList.getSelectionModel().getSelectedItem();
        if (entry == null || !entry.isRealFolder()) {
            return;
        }
        if (!confirm("删除文件夹",
                "删除文件夹“" + entry.folder() + "”？\n其中的音频不会删除，会回到“未分类”。")) {
            return;
        }
        setBusy(true);
        polarBell.folderOperationAsync(client -> client.deleteFolder(entry.folder()),
                this::doneAndReload, error -> failed("删除文件夹失败", error));
    }

    /** 让下一次 reload 选中指定文件夹（新建或改名后）。 */
    private void selectFolderAfterReload(String folder) {
        FolderEntry placeholder = new FolderEntry(folder, folder);
        folderList.getItems().add(placeholder);
        folderList.getSelectionModel().select(placeholder);
    }

    private void doneAndReload() {
        Platform.runLater(() -> {
            setBusy(false);
            reload();
        });
    }

    private void failed(String title, Exception error) {
        Platform.runLater(() -> {
            setBusy(false);
            showError(title, error);
        });
    }

    private boolean confirm(String header, String text) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(dialog.getDialogPane().getScene().getWindow());
        confirm.setHeaderText(header);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void showError(String title, Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        statusLabel.setText(title + "：" + message);
    }

    private static String formatDuration(long ms) {
        long seconds = ms / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
