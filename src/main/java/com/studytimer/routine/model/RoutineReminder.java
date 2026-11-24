package com.studytimer.routine.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * A recurring routine reminder definition.
 * The time is stored as HH:mm so the model can be serialized by the
 * project's existing Jackson setup without an additional Java-time module.
 */
public class RoutineReminder {

    private String id;
    private String name;
    private String time;
    private Set<String> repeatDays = new TreeSet<>();
    private boolean enabled = true;
    private String message = "";
    private String mediaType = "NONE";
    private String mediaPath = "";
    /**
     * 视频提醒的声音输出方式：
     * AUTO  自动（优先把视频原声上传到手机，失败回退电脑）
     * LOCAL 仅电脑
     * PHONE 优先手机（手机不可用时仍改由电脑播放，避免静音提醒；值保持 "PHONE" 以兼容数据）
     * 对非视频媒体暂时无影响。
     */
    private String audioOutput = "AUTO";
    /** 提醒音量 0-100（电脑和手机都适用）；旧数据没有该字段时为 100。 */
    private int volume = 100;
    // Optional ISO date (yyyy-MM-dd). When present, this is a one-time reminder.
    private String onceDate = "";
    private boolean completed;

    public RoutineReminder() {
        this(UUID.randomUUID().toString(), "新提醒", "08:00",
                Set.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY",
                        "FRIDAY", "SATURDAY", "SUNDAY"), true,
                "", "NONE", "");
    }

    public RoutineReminder(
            String id,
            String name,
            String time,
            Set<String> repeatDays,
            boolean enabled,
            String message,
            String mediaType,
            String mediaPath) {
        this.id = id == null || id.isBlank()
                ? UUID.randomUUID().toString() : id;
        this.name = name;
        this.time = time;
        this.repeatDays = repeatDays == null
                ? new TreeSet<>() : new TreeSet<>(repeatDays);
        this.enabled = enabled;
        this.message = message == null ? "" : message;
        this.mediaType = mediaType == null ? "NONE" : mediaType;
        this.mediaPath = mediaPath == null ? "" : mediaPath;
        this.onceDate = "";
        this.completed = false;
    }

    public RoutineReminder copy() {
        RoutineReminder copy = new RoutineReminder(id, name, time, repeatDays, enabled,
                message, mediaType, mediaPath);
        copy.setAudioOutput(audioOutput);
        copy.setVolume(volume);
        copy.setOnceDate(onceDate);
        copy.setCompleted(completed);
        return copy;
    }

    public void validate() {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("提醒 ID 不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("提醒名称不能为空");
        }
        if (time == null || !time.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
            throw new IllegalArgumentException("提醒时间必须使用 HH:mm 格式");
        }
        boolean oneTime = onceDate != null && !onceDate.isBlank();
        if (oneTime) {
            try { LocalDate.parse(onceDate); }
            catch (DateTimeParseException e) {
                throw new IllegalArgumentException("一次性提醒日期必须使用 yyyy-MM-dd 格式");
            }
        } else if (repeatDays == null || repeatDays.isEmpty()) {
            throw new IllegalArgumentException("每周重复提醒至少选择一个重复日期");
        }
        Set<String> validDays = Set.of("MONDAY", "TUESDAY", "WEDNESDAY",
                "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
        if (repeatDays != null && !validDays.containsAll(repeatDays)) {
            throw new IllegalArgumentException("重复日期包含无效值");
        }
        if (!Set.of("NONE", "IMAGE", "VIDEO", "AUDIO", "VOICE")
                .contains(mediaType)) {
            throw new IllegalArgumentException("不支持的提醒媒体类型：" + mediaType);
        }
        if (!"NONE".equals(mediaType)
                && (mediaPath == null || mediaPath.isBlank())) {
            throw new IllegalArgumentException("选择媒体后必须指定媒体文件路径");
        }
        if (!Set.of("AUTO", "LOCAL", "PHONE").contains(audioOutput)) {
            throw new IllegalArgumentException("不支持的声音输出方式：" + audioOutput);
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }
    public Set<String> getRepeatDays() { return new TreeSet<>(repeatDays); }
    public void setRepeatDays(Set<String> repeatDays) {
        this.repeatDays = repeatDays == null ? new TreeSet<>() : new TreeSet<>(repeatDays);
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message == null ? "" : message; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public String getMediaPath() { return mediaPath; }
    public void setMediaPath(String mediaPath) { this.mediaPath = mediaPath == null ? "" : mediaPath; }
    public String getAudioOutput() { return audioOutput; }
    public void setAudioOutput(String audioOutput) {
        this.audioOutput = audioOutput == null || audioOutput.isBlank()
                ? "AUTO" : audioOutput.trim().toUpperCase(java.util.Locale.ROOT);
    }
    public int getVolume() { return volume; }
    public void setVolume(int volume) { this.volume = Math.max(0, Math.min(100, volume)); }
    public String getOnceDate() { return onceDate; }
    public void setOnceDate(String onceDate) { this.onceDate = onceDate == null ? "" : onceDate.trim(); }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    /** Convenience method for application logic; it is not a persisted JSON property.
     * Older builds accidentally serialized this getter as the obsolete "oneTime" field.
     */
    @JsonIgnore
    public boolean isOneTime() { return onceDate != null && !onceDate.isBlank(); }
}
