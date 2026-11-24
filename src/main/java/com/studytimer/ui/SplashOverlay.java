package com.studytimer.ui;

import com.studytimer.audio.AudioPlaybackCoordinator;
import com.studytimer.service.AppLog;
import com.studytimer.service.SplashSettingsStore.Settings;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 开屏：盖在主界面上的一层，显示图片/视频（或播放音乐）、一行文字和两个按钮。
 * 左按钮直接进入；右按钮先显示一段回应（文字 + 媒体），显示完自动进入。
 */
public final class SplashOverlay extends StackPane {

    /** 开机启动时等待手机连接的上限。 */
    private static final int PHONE_WAIT_SECONDS = 15;
    /** 开机启动时延迟开始播放，等系统声音设备就绪。 */
    private static final double AUTOSTART_DELAY_SECONDS = 2.5;
    /** 回应阶段：图片/纯文字的默认停留时间。 */
    private static final int DEFAULT_RESPONSE_SECONDS = 15;
    /** 回应阶段“播完为止”但无法得知何时播完（手机播放音乐）时的上限。 */
    private static final int RESPONSE_SAFETY_SECONDS = 600;

    private final Settings settings;
    private final boolean autostart;
    private final AudioPlaybackCoordinator audio;
    private final Consumer<String> onPhoneFallback;
    private final StackPane mediaLayer = new StackPane();
    private final Label message = new Label();
    private final HBox buttons;
    private final Label hint = new Label();
    private final Button skipButton = new Button("跳过");
    private final VBox panel;

    /** 每切换一次阶段加一，用来丢弃上一阶段迟到的回调。 */
    private int generation;
    private boolean response;
    private MediaPlayer mediaPlayer;
    private boolean localAudioPlaying;
    private boolean phoneRequested;
    private boolean videoStarted;
    private boolean closed;
    private Timeline phoneWait;
    private PauseTransition delay;
    private PauseTransition phoneTimeout;
    private Timeline countdown;

    public SplashOverlay(Settings settings, boolean autostart,
                         AudioPlaybackCoordinator audio, Consumer<String> onPhoneFallback) {
        this.settings = settings;
        this.autostart = autostart;
        this.audio = audio;
        this.onPhoneFallback = onPhoneFallback;

        getStyleClass().add("splash-overlay");
        mediaLayer.getStyleClass().add("splash-media");

        message.setText(settings.message == null ? "" : settings.message);
        message.getStyleClass().add("splash-message");
        message.setWrapText(true);

        Button yes = new Button(textOr(settings.yesText, "Yes"));
        Button no = new Button(textOr(settings.noText, "Not now"));
        yes.getStyleClass().addAll("splash-button", "splash-yes");
        no.getStyleClass().addAll("splash-button", "splash-no");
        yes.setDefaultButton(true);
        yes.setOnAction(e -> close(yes.getText()));
        no.setOnAction(e -> showResponse(no.getText()));

        hint.getStyleClass().add("splash-hint");
        hint.setWrapText(true);
        setShown(hint, false);

        skipButton.getStyleClass().add("splash-skip");
        skipButton.setOnAction(e -> close(null));
        setShown(skipButton, false);

        buttons = new HBox(16, yes, no);
        buttons.setAlignment(Pos.CENTER);

        panel = new VBox(18, message, buttons, hint, skipButton);
        panel.setAlignment(Pos.CENTER);
        panel.getStyleClass().add("splash-panel");
        panel.setMaxWidth(760);
        panel.setMaxHeight(USE_PREF_SIZE);
        StackPane.setAlignment(panel, Pos.BOTTOM_CENTER);
        StackPane.setMargin(panel, new Insets(0, 24, 56, 24));

        getChildren().addAll(mediaLayer, panel);
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /** 加到主界面上之后调用。 */
    public void start() {
        startMedia(settings.mediaType, settings.mediaPath, autostart);
    }

    // ==================== 回应阶段 ====================

    private void showResponse(String choice) {
        if (closed || response) {
            return;
        }
        AppLog.info("开屏选择：" + choice);
        response = true;
        stopPlayback();
        mediaLayer.getChildren().clear();

        message.setText(textOr(settings.noMessage, Settings.DEFAULT_NO_MESSAGE));
        message.getStyleClass().add("splash-response");
        setShown(buttons, false);
        setShown(hint, false);
        setShown(skipButton, true);

        String type = settings.noMediaType == null ? Settings.MEDIA_NONE : settings.noMediaType;
        boolean playback = Settings.MEDIA_VIDEO.equals(type) || Settings.MEDIA_AUDIO.equals(type);
        boolean started = startMedia(type, settings.noMediaPath, false);

        int seconds = settings.noDurationSeconds;
        if (seconds <= 0) {
            // 0 = 播完为止；图片、纯文字或媒体没能开始时按默认停留
            seconds = playback && started ? 0 : DEFAULT_RESPONSE_SECONDS;
        }
        if (seconds > 0) {
            startCountdown(seconds);
        } else if (Settings.MEDIA_AUDIO.equals(type) && phoneRequested) {
            // 手机播放音乐时电脑不知道何时播完，设一个上限
            startCountdown(RESPONSE_SAFETY_SECONDS);
        }
    }

    private void startCountdown(int seconds) {
        int[] left = {seconds};
        skipButton.setText("跳过（" + left[0] + "）");
        countdown = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            left[0]--;
            if (left[0] <= 0) {
                countdown.stop();
                close(null);
            } else {
                skipButton.setText("跳过（" + left[0] + "）");
            }
        }));
        countdown.setCycleCount(seconds);
        countdown.play();
    }

    /** 回应阶段的媒体自然播完：进入主界面。 */
    private void onMediaFinished(int gen) {
        if (closed || gen != generation || !response) {
            return;
        }
        AppLog.info("开屏回应播放完毕，进入主界面");
        close(null);
    }

    // ==================== 媒体 ====================

    /** 返回媒体是否成功开始（或已排队开始）。 */
    private boolean startMedia(String type, String pathText, boolean useAutostartDelay) {
        Path path = mediaPath(pathText);
        if (path == null || type == null || Settings.MEDIA_NONE.equals(type)) {
            return false;
        }
        try {
            if (Settings.MEDIA_IMAGE.equals(type)) {
                showImage(path);
            } else if (Settings.MEDIA_VIDEO.equals(type)) {
                prepareVideo(path);
                startAfterDelay(useAutostartDelay, () -> startPlayback(path, true, useAutostartDelay));
            } else if (Settings.MEDIA_AUDIO.equals(type)) {
                showHint("♪ " + path.getFileName());
                startAfterDelay(useAutostartDelay, () -> startPlayback(path, false, useAutostartDelay));
            } else {
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            AppLog.error("开屏媒体加载失败", e);
            showHint("媒体加载失败：" + e.getMessage());
            return false;
        }
    }

    private Path mediaPath(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Path path = Path.of(text).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            showHint("开屏文件不存在：" + path);
            AppLog.warn("开屏文件不存在：" + path);
            return null;
        }
        return path;
    }

    private void showImage(Path path) {
        Image image = new Image(path.toUri().toASCIIString(), true);
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.fitWidthProperty().bind(widthProperty());
        view.fitHeightProperty().bind(heightProperty());
        image.exceptionProperty().addListener((obs, old, error) -> {
            if (error != null) {
                showHint("图片加载失败：" + error.getMessage());
            }
        });
        mediaLayer.getChildren().setAll(view);
    }

    private void prepareVideo(Path path) {
        String ext = extension(path);
        if (!ext.equals("mp4") && !ext.equals("m4v")) {
            throw new IllegalArgumentException("开屏视频请使用 MP4（H.264/AAC）");
        }
        int gen = generation;
        MediaPlayer player = new MediaPlayer(new Media(path.toUri().toASCIIString()));
        mediaPlayer = player;
        player.setVolume(settings.volume / 100.0);
        player.setOnError(() -> {
            AppLog.error("开屏视频播放失败", player.getError());
            showHint("视频播放失败：" + player.getError().getMessage());
        });
        // 声音在手机上时电脑端是静音播放画面，播完事件同样有效
        player.setOnEndOfMedia(() -> onMediaFinished(gen));
        MediaView view = new MediaView(player);
        view.setPreserveRatio(true);
        view.fitWidthProperty().bind(widthProperty());
        view.fitHeightProperty().bind(heightProperty());
        mediaLayer.getChildren().setAll(view);
    }

    private void startAfterDelay(boolean useDelay, Runnable action) {
        if (!useDelay) {
            action.run();
            return;
        }
        int gen = generation;
        delay = new PauseTransition(Duration.seconds(AUTOSTART_DELAY_SECONDS));
        delay.setOnFinished(e -> {
            if (!closed && gen == generation) {
                action.run();
            }
        });
        delay.play();
    }

    private void startPlayback(Path path, boolean video, boolean waitForPhone) {
        if (!Settings.OUTPUT_PHONE.equals(settings.output)) {
            playLocal(path, video, null);
            return;
        }
        if (audio.isPhoneUploadPlaybackAvailable()) {
            playOnPhone(path, video);
            return;
        }
        if (!waitForPhone) {
            playLocal(path, video, "手机未连接");
            return;
        }
        // 开机时手机连接要几秒到十几秒：先等一会儿，超时改由电脑播放。
        showHint("正在等待手机连接…");
        int gen = generation;
        int[] waited = {0};
        phoneWait = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (closed || gen != generation) {
                phoneWait.stop();
                return;
            }
            waited[0]++;
            if (audio.isPhoneUploadPlaybackAvailable()) {
                phoneWait.stop();
                playOnPhone(path, video);
            } else if (waited[0] >= PHONE_WAIT_SECONDS) {
                phoneWait.stop();
                playLocal(path, video, "开机后 " + PHONE_WAIT_SECONDS + " 秒内手机未连接");
            }
        }));
        phoneWait.setCycleCount(Timeline.INDEFINITE);
        phoneWait.play();
    }

    private void playLocal(Path path, boolean video, String fallbackReason) {
        if (closed) {
            return;
        }
        if (fallbackReason != null) {
            AppLog.warn("开屏声音改由电脑播放：" + fallbackReason);
            onPhoneFallback.accept(fallbackReason);
        }
        if (video) {
            if (mediaPlayer != null) {
                videoStarted = true;
                mediaPlayer.setMute(false);
                mediaPlayer.play();
            }
            setShown(hint, false);
            return;
        }
        int gen = generation;
        try {
            audio.playReminderAudio(path, settings.volume,
                    () -> Platform.runLater(() -> onMediaFinished(gen)));
            localAudioPlaying = true;
            showHint("♪ " + path.getFileName());
        } catch (RuntimeException e) {
            AppLog.error("开屏音乐播放失败", e);
            showHint("音乐播放失败：" + e.getMessage());
        }
    }

    private void playOnPhone(Path path, boolean video) {
        if (closed) {
            return;
        }
        int gen = generation;
        phoneRequested = true;
        showHint("正在发送到手机…");
        if (video && mediaPlayer != null) {
            mediaPlayer.setMute(true);
        }

        long sizeMb = 0;
        try {
            sizeMb = Files.size(path) / 1024 / 1024;
        } catch (Exception ignored) {
        }
        phoneTimeout = new PauseTransition(Duration.seconds(15 + sizeMb / 4 + (video ? 30 : 0)));
        phoneTimeout.setOnFinished(e -> fallbackFromPhone(gen, path, video, "手机播放超时"));
        phoneTimeout.play();

        Runnable started = () -> Platform.runLater(() -> {
            if (closed || gen != generation || videoStarted || localAudioPlaying) {
                // 已关闭、已回退电脑或已换阶段：停掉迟到的手机声音。
                // 换到的新阶段也在用手机时不停，新的上传排在后面，会自然替换它。
                boolean newStageUsesPhone = !closed && gen != generation && phoneRequested;
                if (!newStageUsesPhone) {
                    audio.stopLateReminderPhoneAudio();
                }
                return;
            }
            stopPhoneTimeout();
            if (video && mediaPlayer != null) {
                videoStarted = true;
                mediaPlayer.play();
            }
            showHint(video ? "声音由手机播放" : "♪ " + path.getFileName() + "（手机播放）");
        });
        Consumer<Throwable> failed = error -> Platform.runLater(() ->
                fallbackFromPhone(gen, path, video, error.getMessage()));

        if (video) {
            audio.playReminderVideoSoundOnPhone(path, settings.volume, started, failed);
        } else {
            audio.playReminderFileOnPhone(path, settings.volume, started, failed);
        }
    }

    private void fallbackFromPhone(int gen, Path path, boolean video, String reason) {
        if (gen != generation) {
            return;
        }
        stopPhoneTimeout();
        if (closed || videoStarted || localAudioPlaying) {
            return;
        }
        playLocal(path, video, reason == null ? "手机播放失败" : reason);
    }

    private void stopPhoneTimeout() {
        if (phoneTimeout != null) {
            phoneTimeout.stop();
            phoneTimeout = null;
        }
    }

    private void showHint(String text) {
        hint.setText(text);
        setShown(hint, true);
    }

    /** 停止当前阶段的所有播放和计时，界面不动。 */
    private void stopPlayback() {
        generation++;
        if (delay != null) {
            delay.stop();
            delay = null;
        }
        if (phoneWait != null) {
            phoneWait.stop();
            phoneWait = null;
        }
        if (countdown != null) {
            countdown.stop();
            countdown = null;
        }
        stopPhoneTimeout();
        videoStarted = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.dispose();
            } catch (RuntimeException ignored) {
                // 关闭开屏不能因为播放器异常失败
            }
            mediaPlayer = null;
        }
        if (localAudioPlaying) {
            audio.stopReminderAudio();
            localAudioPlaying = false;
        }
        if (phoneRequested) {
            audio.stopPhoneAudio();
            phoneRequested = false;
        }
    }

    /** 停止所有播放并淡出移除。choice 为 null 时不写选择日志。 */
    public void close(String choice) {
        if (closed) {
            return;
        }
        if (choice != null) {
            AppLog.info("开屏选择：" + choice);
        }
        stopPlayback();
        closed = true;

        setMouseTransparent(true);
        FadeTransition fade = new FadeTransition(Duration.millis(220), this);
        fade.setToValue(0);
        fade.setOnFinished(e -> {
            if (getParent() instanceof javafx.scene.layout.Pane pane) {
                pane.getChildren().remove(this);
            }
        });
        fade.play();
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
