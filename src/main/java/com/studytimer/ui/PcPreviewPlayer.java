package com.studytimer.ui;

import com.studytimer.PhoneAudio;
import com.studytimer.PolarBellService;
import com.studytimer.audio.PhoneFolderPlaylist;
import com.studytimer.audio.VlcAudioService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * 手机音频库的小播放器：播放列表为当前文件夹里的音频，顺序/随机、上一首/下一首、停止，播完自动接下一首。
 * 输出可选“电脑”（从手机取回缓存后用 VLC 播放，可暂停）或“手机”（手机播放，每 3 秒查询是否播完）。
 */
final class PcPreviewPlayer {

    private enum Output { PC, PHONE }

    private final PolarBellService polarBell;
    private final Path cacheDir;
    private final Consumer<String> status;
    private final VBox view;
    private final Label nowPlaying = new Label("未播放");
    private final Button prevButton = new Button("⏮");
    private final Button playButton = new Button("▶ 播放");
    private final Button nextButton = new Button("⏭");
    private final Button stopButton = new Button("■");
    private final ToggleButton pcToggle = new ToggleButton("电脑");
    private final ToggleButton phoneToggle = new ToggleButton("手机");
    private final ToggleButton orderToggle = new ToggleButton("顺序");
    private final ToggleButton randomToggle = new ToggleButton("随机");
    private final RandomGenerator random = RandomGenerator.getDefault();
    private final Timeline phoneCheck;

    private VlcAudioService vlc;
    private List<PhoneAudio> playlist = List.of();
    private int index = -1;
    private boolean paused;
    private Output activeOutput;
    /** 每次切歌递增，丢弃迟到的下载、查询和播完回调。 */
    private long generation;
    private boolean checking;

    /** 选中的音频（可为 null）与当前列表。 */
    interface Source {
        PhoneAudio selected();

        List<PhoneAudio> visible();
    }

    private final Source source;

    PcPreviewPlayer(PolarBellService polarBell, Path cacheDir, Source source, Consumer<String> status) {
        this.polarBell = polarBell;
        this.cacheDir = cacheDir;
        this.source = source;
        this.status = status;

        keepOneSelected(new ToggleGroup(), pcToggle, phoneToggle);
        keepOneSelected(new ToggleGroup(), orderToggle, randomToggle);
        pcToggle.setSelected(true);
        orderToggle.setSelected(true);
        pcToggle.selectedProperty().addListener((obs, o, n) -> {
            if (index >= 0) {
                stop();
            }
            updateButtons();
        });

        playButton.getStyleClass().add("accent-button");
        playButton.setOnAction(e -> onPlayButton());
        prevButton.setOnAction(e -> step(-1));
        nextButton.setOnAction(e -> step(1));
        stopButton.setOnAction(e -> stop());

        nowPlaying.getStyleClass().add("hint-text");
        nowPlaying.setMaxWidth(Double.MAX_VALUE);

        Label outputLabel = new Label("播放到");
        outputLabel.setMinWidth(Region.USE_PREF_SIZE);
        HBox controls = new HBox(6, outputLabel, pcToggle, phoneToggle, playButton, prevButton, nextButton,
                stopButton, orderToggle, randomToggle);
        controls.setAlignment(Pos.CENTER_LEFT);
        for (javafx.scene.Node node : controls.getChildren()) {
            if (node instanceof Region region) {
                region.setMinWidth(Region.USE_PREF_SIZE);
            }
        }
        // 歌名单独一行，太长时只缩略歌名，不挤压按钮
        view = new VBox(4, controls, nowPlaying);

        phoneCheck = new Timeline(new KeyFrame(Duration.seconds(3), e -> checkPhone()));
        phoneCheck.setCycleCount(Timeline.INDEFINITE);
        updateButtons();
    }

    private static void keepOneSelected(ToggleGroup group, ToggleButton... buttons) {
        for (ToggleButton button : buttons) {
            button.setToggleGroup(group);
        }
        group.selectedToggleProperty().addListener((obs, old, value) -> {
            if (value == null && old != null) {
                old.setSelected(true);
            }
        });
    }

    VBox getView() {
        return view;
    }

    private Output output() {
        return phoneToggle.isSelected() ? Output.PHONE : Output.PC;
    }

    private PhoneFolderPlaylist.Mode mode() {
        return randomToggle.isSelected() ? PhoneFolderPlaylist.Mode.RANDOM : PhoneFolderPlaylist.Mode.ORDER;
    }

    /** 双击音频或按“播放”：从选中的音频开始播放当前文件夹。 */
    void playSelected() {
        List<PhoneAudio> visible = source.visible();
        PhoneAudio selected = source.selected();
        if (visible.isEmpty()) {
            status.accept("当前文件夹没有音频");
            return;
        }
        if (output() == Output.PC && !polarBell.isLibraryReadSupported()) {
            status.accept("电脑播放需要更新手机 App；可以改为“手机”播放");
            return;
        }
        preparedNext = -1;
        playlist = List.copyOf(visible);
        index = selected != null ? playlist.indexOf(selected) : -1;
        if (index < 0) {
            index = PhoneFolderPlaylist.nextIndex(-1, playlist.size(), mode(), random);
        }
        playCurrent();
    }

    private void onPlayButton() {
        if (index >= 0 && activeOutput == Output.PC && vlc != null) {
            paused = !paused;
            vlc.setPaused(paused);
            updateButtons();
            return;
        }
        playSelected();
    }

    private void step(int delta) {
        if (playlist.isEmpty()) {
            playSelected();
            return;
        }
        if (delta > 0 && preparedNext >= 0 && preparedNext < playlist.size() && preparedMode == mode()) {
            index = preparedNext;
        } else {
            index = PhoneFolderPlaylist.step(index, playlist.size(), mode(), random, delta);
        }
        preparedNext = -1;
        playCurrent();
    }

    private void playCurrent() {
        if (index < 0 || index >= playlist.size()) {
            stop();
            return;
        }
        long token = ++generation;
        paused = false;
        phoneCheck.stop();
        if (vlc != null) {
            vlc.stop();
        }
        activeOutput = output();
        if (activeOutput == Output.PHONE) {
            playOnPhone(playlist.get(index), token);
        } else {
            playOnPc(playlist.get(index), token);
        }
        updateButtons();
    }

    // ==================== 手机 ====================

    private void playOnPhone(PhoneAudio audio, long token) {
        polarBell.playAudioAsync(audio.getAudioId(), error -> Platform.runLater(() -> {
            if (token == generation) {
                fail("手机播放失败：" + error.getMessage());
            }
        }));
        nowPlaying.setText(label("手机播放中", audio));
        if (polarBell.isPlayStateSupported()) {
            phoneCheck.playFromStart();
        }
    }

    private void checkPhone() {
        if (checking || activeOutput != Output.PHONE || index < 0) {
            return;
        }
        long token = generation;
        checking = true;
        polarBell.getPlayStateAsync(
                playing -> Platform.runLater(() -> {
                    checking = false;
                    if (token == generation && !playing) {
                        step(1);
                    }
                }),
                error -> Platform.runLater(() -> checking = false));
    }

    // ==================== 电脑 ====================

    private void playOnPc(PhoneAudio audio, long token) {
        nowPlaying.setText("正在从手机取回：" + audio.getName() + "…");
        polarBell.downloadLibraryAudioAsync(audio, cacheDir,
                (done, total) -> Platform.runLater(() -> {
                    if (token == generation && total > 0) {
                        nowPlaying.setText("正在从手机取回：" + audio.getName()
                                + "（" + (done * 100 / total) + "%）");
                    }
                }),
                file -> Platform.runLater(() -> {
                    if (token == generation) {
                        startVlc(audio, file, token);
                    }
                }),
                error -> Platform.runLater(() -> {
                    if (token == generation) {
                        fail("电脑播放失败：" + error.getMessage());
                    }
                }));
    }

    private void startVlc(PhoneAudio audio, Path file, long token) {
        try {
            if (vlc == null) {
                vlc = new VlcAudioService();
            }
            vlc.playAnyFormat(file, () -> Platform.runLater(() -> {
                if (token == generation) {
                    step(1);
                }
            }));
            nowPlaying.setText(label("电脑播放中", audio));
            prefetchNext(token);
        } catch (RuntimeException e) {
            fail("电脑播放失败（需要本机安装 VLC）：" + e.getMessage());
        }
        updateButtons();
    }

    /** 下一首（随机时也提前定好）的位置，-1 表示未定。 */
    private int preparedNext = -1;
    private PhoneFolderPlaylist.Mode preparedMode;

    /** 当前这首开始播放后，在后台提前取回下一首，播完即可直接接上。 */
    private void prefetchNext(long token) {
        if (playlist.size() < 2) {
            return;
        }
        preparedMode = mode();
        preparedNext = PhoneFolderPlaylist.nextIndex(index, playlist.size(), preparedMode, random);
        PhoneAudio next = playlist.get(preparedNext);
        polarBell.downloadLibraryAudioAsync(next, cacheDir, null,
                file -> { },
                error -> Platform.runLater(() -> {
                    if (token == generation) {
                        status.accept("预取下一首失败（轮到时会重试）：" + error.getMessage());
                    }
                }));
    }

    private String label(String prefix, PhoneAudio audio) {
        return prefix + "（" + (mode() == PhoneFolderPlaylist.Mode.RANDOM ? "随机" : "顺序") + " "
                + (index + 1) + "/" + playlist.size() + "）：" + audio.getName();
    }

    private void fail(String message) {
        status.accept(message);
        generation++;
        index = -1;
        phoneCheck.stop();
        nowPlaying.setText("未播放");
        updateButtons();
    }

    void stop() {
        boolean wasPhone = index >= 0 && activeOutput == Output.PHONE;
        generation++;
        index = -1;
        paused = false;
        phoneCheck.stop();
        if (vlc != null) {
            vlc.stop();
        }
        if (wasPhone) {
            polarBell.stopAsync(error -> Platform.runLater(() ->
                    status.accept("手机停止失败：" + error.getMessage())));
        }
        activeOutput = null;
        nowPlaying.setText("未播放");
        updateButtons();
    }

    /** 对话框关闭时停止播放并释放 VLC。 */
    void close() {
        stop();
        if (vlc != null) {
            vlc.close();
            vlc = null;
        }
    }

    void updateButtons() {
        boolean active = index >= 0;
        boolean pcActive = active && activeOutput == Output.PC;
        playButton.setText(!active ? "▶ 播放" : !pcActive ? "▶ 从选中播放" : paused ? "▶ 继续" : "⏸ 暂停");
        prevButton.setDisable(!active);
        nextButton.setDisable(!active);
        stopButton.setDisable(!active);
        if (!active && output() == Output.PC && !polarBell.isLibraryReadSupported()) {
            nowPlaying.setText("电脑播放需要更新手机 App");
        } else if (!active && output() == Output.PHONE && !polarBell.isPlayStateSupported()) {
            nowPlaying.setText("手机 App 较旧：只播一首，不会自动接下一首");
        } else if (!active) {
            nowPlaying.setText("未播放");
        }
    }
}
