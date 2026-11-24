package com.studytimer.routine.ui;

import com.studytimer.audio.AudioPlaybackCoordinator;
import com.studytimer.routine.model.RoutineReminder;
import com.studytimer.routine.service.RoutineReminderScheduler;
import com.studytimer.service.AppLog;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Full-screen presentation window for one triggered routine reminder. */
public final class RoutineReminderWindow implements AutoCloseable {

    private final AudioPlaybackCoordinator audioCoordinator;
    private final Consumer<Throwable> errorHandler;
    private Stage stage;
    private MediaPlayer mediaPlayer;
    private boolean closing;
    private boolean reminderAudioPlaying;
    private PauseTransition phoneAudioTimeout;
    private boolean phoneAudioRequested;
    private boolean videoPlaybackStarted;
    private String videoAudioFallbackReason;

    /** 可选的视频卡顿诊断：设置 STUDYTIMER_VIDEO_DIAG=1 后启用。 */
    private AnimationTimer videoDiagnostics;
    private long diagnosticsLastPulse;
    private long diagnosticsWindowStart;
    private int diagnosticsLateFrames;
    private long diagnosticsMaxGapMillis;
    private int diagnosticsSampleCount;
    private Runnable onDismissed = () -> {};
    private Consumer<String> onPhoneFallback = reason -> {};
    private BiConsumer<RoutineReminderScheduler.TriggeredReminder, Integer> onSnooze = (event, minutes) -> {};
    private RoutineReminderScheduler.TriggeredReminder currentEvent;
    private Label pendingLabel;

    public RoutineReminderWindow(
            AudioPlaybackCoordinator audioCoordinator,
            Consumer<Throwable> errorHandler) {
        this.audioCoordinator = Objects.requireNonNull(audioCoordinator, "audioCoordinator");
        this.errorHandler = errorHandler == null ? Throwable::printStackTrace : errorHandler;
    }

    public void setOnSnooze(
            BiConsumer<RoutineReminderScheduler.TriggeredReminder, Integer> onSnooze) {
        this.onSnooze = onSnooze == null ? (event, minutes) -> {} : onSnooze;
    }

    public void setOnDismissed(Runnable onDismissed) {
        this.onDismissed = onDismissed == null ? () -> {} : onDismissed;
    }

    /** 视频原声本应由手机播放、实际改由电脑播放时回调，参数为原因。 */
    public void setOnPhoneFallback(Consumer<String> onPhoneFallback) {
        this.onPhoneFallback = onPhoneFallback == null ? reason -> {} : onPhoneFallback;
    }

    private void notifyPhoneFallback(String reason) {
        AppLog.info("提醒视频声音改由电脑播放：" + reason);
        try {
            onPhoneFallback.accept(reason);
        } catch (RuntimeException e) {
            reportError(e);
        }
    }

    /** 显示当前提醒之后还有多少条在排队；0 时隐藏。 */
    public void setPendingCount(int count) {
        if (pendingLabel == null) {
            return;
        }
        pendingLabel.setText(count > 0 ? "后面还有 " + count + " 条提醒" : "");
        pendingLabel.setVisible(count > 0);
        pendingLabel.setManaged(count > 0);
    }

    private static String describeTrigger(RoutineReminderScheduler.TriggeredReminder event) {
        if (!event.missed()) {
            return "";
        }
        java.time.LocalDateTime at = event.scheduledAt();
        String time = at.toLocalDate().equals(event.triggeredAt().toLocalDate())
                ? at.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                : at.format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"));
        return "错过的提醒（原定 " + time + "）";
    }

    /** Displays a reminder. Must be called on the JavaFX Application Thread. */
    public void show(RoutineReminderScheduler.TriggeredReminder event) {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("提醒窗口必须在 JavaFX Application Thread 中显示");
        }
        Objects.requireNonNull(event, "event");
        closeCurrentWindow();

        currentEvent = event;
        videoAudioFallbackReason = null;
        RoutineReminder reminder = event.reminder();
        stage = new Stage();
        stage.setTitle("Routine Reminder - " + reminder.getName());
        stage.setFullScreenExitHint("按 Esc 可退出全屏显示");
        stage.setAlwaysOnTop(true);

        Label title = new Label(reminder.getName());
        title.setFont(Font.font(34));
        title.setTextFill(Color.WHITE);
        title.setWrapText(true);

        Label info = new Label(describeTrigger(event));
        info.getStyleClass().add("reminder-info");
        info.setVisible(!info.getText().isEmpty());
        info.setManaged(info.isVisible());

        pendingLabel = new Label();
        pendingLabel.getStyleClass().add("reminder-pending");
        setPendingCount(0);

        Label message = new Label(reminder.getMessage());
        message.setFont(Font.font(21));
        message.setTextFill(Color.web("#e5e7eb"));
        message.setWrapText(true);
        message.setMaxWidth(900);

        StackPane mediaArea = new StackPane();
        mediaArea.getStyleClass().add("reminder-media-area");
        mediaArea.setMinHeight(260);
        VBox.setVgrow(mediaArea, Priority.ALWAYS);

        Label status = new Label();
        status.setTextFill(Color.web("#fca5a5"));
        status.setWrapText(true);

        try {
            showMedia(reminder, mediaArea, status);
        } catch (Exception e) {
            status.setText("媒体加载失败：" + e.getMessage());
            reportError(e);
        }

        Button snoozeFiveButton = new Button("稍后 5 分钟");
        Button snoozeTenButton = new Button("稍后 10 分钟");
        Button dismissButton = new Button("完成 / 关闭提醒");
        snoozeFiveButton.setFont(Font.font(17));
        snoozeTenButton.setFont(Font.font(17));
        dismissButton.setFont(Font.font(17));
        dismissButton.setDefaultButton(true);
        snoozeFiveButton.setOnAction(event1 -> snoozeCurrentReminder(5));
        snoozeTenButton.setOnAction(event1 -> snoozeCurrentReminder(10));
        dismissButton.setOnAction(event1 -> closeCurrentWindow());
        javafx.scene.layout.HBox actions = new javafx.scene.layout.HBox(
                12, snoozeFiveButton, snoozeTenButton, dismissButton);
        actions.setAlignment(Pos.CENTER);

        VBox content = new VBox(22, title, info, message, mediaArea, status, actions, pendingLabel);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(36));
        content.getStyleClass().add("reminder-fullscreen");

        BorderPane root = new BorderPane(content);
        Scene scene = new Scene(root,
                Screen.getPrimary().getBounds().getWidth(),
                Screen.getPrimary().getBounds().getHeight());
        var themeUrl = RoutineReminderWindow.class.getResource("/com/studytimer/ui/reminder.css");
        if (themeUrl != null) {
            scene.getStylesheets().add(themeUrl.toExternalForm());
        }
        stage.setScene(scene);
        stage.setOnCloseRequest(this::handleCloseRequest);
        stage.setOnHidden(event1 -> cleanupPlayback());
        stage.show();
        stage.setFullScreen(true);
    }

    private void showMedia(RoutineReminder reminder, StackPane mediaArea, Label status) {
        String type = reminder.getMediaType();
        if (type == null || "NONE".equals(type)) {
            return;
        }
        Path path = Path.of(reminder.getMediaPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("文件不存在：" + path);
        }
        String uri = path.toUri().toASCIIString();

        switch (type) {
            case "IMAGE" -> {
                Image image = new Image(uri, true);
                ImageView imageView = new ImageView(image);
                imageView.setPreserveRatio(true);
                imageView.fitWidthProperty().bind(mediaArea.widthProperty().subtract(30));
                imageView.fitHeightProperty().bind(mediaArea.heightProperty().subtract(30));
                image.exceptionProperty().addListener((observable, oldValue, error) -> {
                    if (error != null) status.setText("图片加载失败：" + error.getMessage());
                });
                mediaArea.getChildren().setAll(imageView);
            }
            case "VIDEO" -> {
                String extension = fileExtension(path);
                if (!extension.equals("mp4") && !extension.equals("m4v")) {
                    throw new IllegalArgumentException(
                            "提醒视频建议使用 MP4/M4V（H.264 视频 + AAC 音频）。当前文件扩展名："
                                    + (extension.isBlank() ? "未知" : extension.toUpperCase())
                    );
                }
                Media media = new Media(uri);
                media.setOnError(() -> {
                    Throwable error = media.getError();
                    status.setText("视频媒体读取失败：" + errorMessage(error)
                            + "。请检查文件是否损坏及编码是否为 H.264/AAC。");
                    if (error != null) reportError(error);
                });
                mediaPlayer = new MediaPlayer(media);
                mediaPlayer.setVolume(reminder.getVolume() / 100.0);
                MediaView mediaView = new MediaView(mediaPlayer);
                mediaView.setPreserveRatio(true);
                mediaView.fitWidthProperty().bind(mediaArea.widthProperty().subtract(30));
                mediaView.fitHeightProperty().bind(mediaArea.heightProperty().subtract(30));
                mediaPlayer.setOnError(() -> {
                    Throwable error = mediaPlayer.getError();
                    AppLog.error("提醒视频播放失败：" + path.getFileName(), error);
                    status.setText("视频播放失败：" + errorMessage(error)
                            + "。高分辨率/高码率视频可能出现卡顿，建议转码为 1080p、30 FPS、H.264/AAC。");
                    if (error != null) reportError(error);
                });
                mediaPlayer.setOnStalled(() -> {
                    AppLog.warn("提醒视频读取停滞：" + path.getFileName()
                            + " @ " + mediaPlayer.getCurrentTime());
                    status.setText("视频读取暂时停滞；如果持续卡顿，建议降低分辨率或码率。");
                });
                // 视频播完自动关闭提醒（视为完成），队列里的下一条随即显示。
                mediaPlayer.setOnEndOfMedia(this::closeWhenMediaFinished);
                mediaPlayer.setOnPlaying(() -> AppLog.info(
                        "提醒视频开始播放：" + path.getFileName()
                                + "（静音=" + mediaPlayer.isMute() + "）"));

                boolean usePhoneAudio = shouldUsePhoneForVideo(reminder, path, status);

                // 手机接管声音时先静音电脑端，避免两边同时出声。
                if (usePhoneAudio) {
                    mediaPlayer.setMute(true);
                }

                mediaPlayer.setOnReady(() -> {
                    if (mediaPlayer == null || stage == null) {
                        return;
                    }
                    status.setText("视频已就绪。推荐：MP4、H.264/AAC、1080p、30 FPS。");

                    if (usePhoneAudio) {
                        startVideoWithPhoneAudio(path, reminder.getVolume(), status);
                    } else {
                        playVideoNow();
                        if (videoAudioFallbackReason != null) {
                            status.setText("手机未播放，改由电脑播放声音：" + videoAudioFallbackReason);
                            notifyPhoneFallback(videoAudioFallbackReason);
                        }
                    }
                });
                mediaArea.getChildren().setAll(mediaView);
            }
            case "AUDIO", "VOICE" -> {
                String description = "VOICE".equals(type) ? "语音提醒" : "音频提醒";
                Label audioLabel = new Label(description + "\n" + path.getFileName());
                audioLabel.setTextFill(Color.WHITE);
                audioLabel.setFont(Font.font(22));
                mediaArea.getChildren().setAll(audioLabel);
                Stage owner = stage;
                audioCoordinator.playReminderAudio(path, reminder.getVolume(), () -> Platform.runLater(() -> {
                    if (stage == owner) {
                        closeWhenMediaFinished();
                    }
                }));
                reminderAudioPlaying = true;
                status.setText("已启动" + description + "播放");
            }
            default -> status.setText("暂不支持的媒体类型：" + type);
        }
    }

    /**
     * 判断是否应该把视频原声交给手机播放。
     *
     * AUTO/PHONE 都会在手机不可用或文件过大时回退电脑声音，
     * 避免提醒窗口出现无声画面。
     */
    private boolean shouldUsePhoneForVideo(
            RoutineReminder reminder, Path path, Label status) {

        String output = reminder.getAudioOutput();
        if ("LOCAL".equals(output)) {
            return false;
        }

        if (!audioCoordinator.isPhoneUploadPlaybackAvailable()) {
            videoAudioFallbackReason = "手机未连接或 PolarBell App 不支持文件播放";
            status.setText("视频原声无法发送到手机："
                    + videoAudioFallbackReason + "；将使用电脑声音。");
            return false;
        }

        try {
            long size = Files.size(path);
            long limit = audioCoordinator.getPhoneUploadLimitBytes();

            // 有 ffmpeg 时只发送音轨，体积远小于视频，不按视频大小拦截。
            if (size > limit && !audioCoordinator.isVideoAudioExtractionAvailable()) {
                videoAudioFallbackReason = "视频文件 "
                        + (size / 1024 / 1024) + "MB，超过手机端 "
                        + (limit / 1024 / 1024) + "MB 上限";
                status.setText("视频原声无法发送到手机："
                        + videoAudioFallbackReason + "；将使用电脑声音。");
                return false;
            }

        } catch (Exception e) {
            videoAudioFallbackReason = "无法读取视频大小（" + errorMessage(e) + "）";
            status.setText("视频原声无法发送到手机："
                    + videoAudioFallbackReason + "；将使用电脑声音。");
            return false;
        }

        return true;
    }

    private void startVideoWithPhoneAudio(Path path, int volume, Label status) {
        phoneAudioRequested = true;
        videoPlaybackStarted = false;
        status.setText("正在把视频原声发送到手机…");

        // 大文件需要更长的上传时间：基础 15 秒，再按约 4MB/s 估算。
        long sizeMb = 0;
        try {
            sizeMb = Files.size(path) / 1024 / 1024;
        } catch (Exception ignored) {
        }
        // 提取音轨（必要时转码）另外预留 30 秒。
        long extractSeconds = audioCoordinator.isVideoAudioExtractionAvailable() ? 30 : 0;
        phoneAudioTimeout = new PauseTransition(
                Duration.seconds(15 + sizeMb / 4 + extractSeconds));
        phoneAudioTimeout.setOnFinished(event -> {
            if (!videoPlaybackStarted) {
                fallbackToLocalVideoAudio(status, "手机播放超时");
            }
        });
        phoneAudioTimeout.play();

        audioCoordinator.playReminderVideoSoundOnPhone(
                path,
                volume,
                () -> Platform.runLater(() -> {
                    if (mediaPlayer == null || stage == null) {
                        // 窗口在手机上传播放完成前已关闭，避免声音残留。
                        audioCoordinator.stopLateReminderPhoneAudio();
                        return;
                    }
                    if (videoPlaybackStarted) {
                        // 超时后已经回退电脑声音，停掉迟到的手机播放。
                        audioCoordinator.stopLateReminderPhoneAudio();
                        return;
                    }
                    videoPlaybackStarted = true;
                    stopPhoneAudioTimeout();
                    mediaPlayer.play();
                    startVideoDiagnostics();
                    status.setText("视频原声由手机播放（PolarBell）");
                }),
                error -> Platform.runLater(() -> {
                    if (mediaPlayer == null || stage == null) {
                        return;
                    }
                    fallbackToLocalVideoAudio(status, errorMessage(error));
                    reportError(error);
                })
        );
    }

    private void fallbackToLocalVideoAudio(Label status, String reason) {
        stopPhoneAudioTimeout();
        if (mediaPlayer == null || stage == null || videoPlaybackStarted) {
            return;
        }
        videoPlaybackStarted = true;
        mediaPlayer.setMute(false);
        mediaPlayer.play();
        startVideoDiagnostics();
        status.setText("手机未播放，改由电脑播放声音：" + reason);
        notifyPhoneFallback(reason);
    }

    /** 统一开始电脑端视频播放，并（可选）启动卡顿诊断。 */
    private void playVideoNow() {
        if (mediaPlayer == null) {
            return;
        }
        videoPlaybackStarted = true;
        mediaPlayer.play();
        startVideoDiagnostics();
    }

    private static boolean isVideoDiagnosticsEnabled() {
        String value = System.getenv("STUDYTIMER_VIDEO_DIAG");
        if (value == null || value.isBlank()) {
            value = System.getProperty("studytimer.video.diag");
        }
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim();
        return !"0".equals(normalized) && !"false".equalsIgnoreCase(normalized);
    }

    /**
     * 可选的卡顿诊断。
     *
     * 记录 JavaFX 渲染脉冲的实际间隔：如果这里出现大量 >34ms 的间隔，
     * 说明是界面渲染线程被拖慢；否则问题在解码/取帧一侧。
     * 默认关闭，避免正常使用产生日志噪音。
     */
    private void startVideoDiagnostics() {
        if (!isVideoDiagnosticsEnabled() || videoDiagnostics != null) {
            return;
        }

        AppLog.info("视频卡顿诊断已启用");
        diagnosticsLastPulse = 0;
        diagnosticsWindowStart = System.nanoTime();
        diagnosticsLateFrames = 0;
        diagnosticsMaxGapMillis = 0;
        diagnosticsSampleCount = 0;

        videoDiagnostics = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (diagnosticsLastPulse != 0) {
                    long gap = now - diagnosticsLastPulse;
                    diagnosticsSampleCount++;
                    // 59/60Hz 下超过两个刷新周期视为掉帧
                    if (gap > 34_000_000L) {
                        diagnosticsLateFrames++;
                        long millis = gap / 1_000_000L;
                        if (millis > diagnosticsMaxGapMillis) {
                            diagnosticsMaxGapMillis = millis;
                        }
                    }
                }
                diagnosticsLastPulse = now;

                if (now - diagnosticsWindowStart >= 5_000_000_000L) {
                    AppLog.info(String.format(java.util.Locale.ROOT,
                            "视频诊断：5 秒内 %d 帧，掉帧 %d 次，最大间隔 %d ms",
                            diagnosticsSampleCount,
                            diagnosticsLateFrames,
                            diagnosticsMaxGapMillis));
                    diagnosticsWindowStart = now;
                    diagnosticsLateFrames = 0;
                    diagnosticsMaxGapMillis = 0;
                    diagnosticsSampleCount = 0;
                }
            }
        };
        videoDiagnostics.start();
    }

    private void stopVideoDiagnostics() {
        if (videoDiagnostics != null) {
            videoDiagnostics.stop();
            videoDiagnostics = null;
            AppLog.info("视频卡顿诊断已停止");
        }
    }

    private void stopPhoneAudioTimeout() {
        if (phoneAudioTimeout != null) {
            phoneAudioTimeout.stop();
            phoneAudioTimeout = null;
        }
    }

    private static String fileExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String errorMessage(Throwable error) {
        if (error == null) return "未知媒体错误";
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private void snoozeCurrentReminder(int minutes) {
        if (currentEvent == null) {
            return;
        }
        try {
            onSnooze.accept(currentEvent, minutes);
            closeCurrentWindow();
        } catch (RuntimeException error) {
            reportError(error);
        }
    }

    private void closeWhenMediaFinished() {
        if (stage == null) {
            return;
        }
        AppLog.info("提醒媒体播放完毕，自动关闭提醒窗口");
        reminderAudioPlaying = false;
        closeCurrentWindow();
    }

    private void handleCloseRequest(WindowEvent event) {
        if (!closing) {
            event.consume();
            closeCurrentWindow();
        }
    }

    private void closeCurrentWindow() {
        if (stage == null) {
            cleanupPlayback();
            return;
        }
        Stage current = stage;
        stage = null;
        currentEvent = null;
        closing = true;
        try {
            current.close();
        } finally {
            closing = false;
            cleanupPlayback();
            onDismissed.run();
        }
    }

    private void cleanupPlayback() {
        stopPhoneAudioTimeout();
        stopVideoDiagnostics();
        videoPlaybackStarted = false;

        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.dispose();
            } catch (RuntimeException e) {
                reportError(e);
            }
            mediaPlayer = null;
        }
        if (reminderAudioPlaying) {
            audioCoordinator.stopReminderAudio();
            reminderAudioPlaying = false;
        }
        if (phoneAudioRequested) {
            audioCoordinator.stopPhoneAudio();
            phoneAudioRequested = false;
        }
    }

    private void reportError(Throwable error) {
        try {
            errorHandler.accept(error);
        } catch (RuntimeException ignored) {
            // Error reporting must not prevent the reminder window from closing.
        }
    }

    @Override
    public void close() {
        if (Platform.isFxApplicationThread()) {
            closeCurrentWindow();
        } else {
            Platform.runLater(this::closeCurrentWindow);
        }
    }
}
