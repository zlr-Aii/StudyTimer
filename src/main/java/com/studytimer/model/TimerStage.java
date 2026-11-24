
package com.studytimer.model;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.UUID;

public class TimerStage {

    // 阶段唯一 ID，创建后不再改变
    private final String id;

    private final StringProperty name = new SimpleStringProperty();
    private final IntegerProperty minutes = new SimpleIntegerProperty();

    // 当前阶段对应的音乐文件路径
    private final StringProperty audioFile =
            new SimpleStringProperty("");

    // 阶段结束前多少秒开始播放音乐
    private final IntegerProperty audioLeadSeconds =
            new SimpleIntegerProperty(30);

    // 手机音频 ID：null=不播放，builtin:x=内置提示音，audio_xxx=媒体库音频
    private final StringProperty phoneAudioId =
            new SimpleStringProperty(null);

    // 手机播放音量 0-100
    private final IntegerProperty phoneVolume =
            new SimpleIntegerProperty(100);

    // 保留原来的构造方法
    public TimerStage(String name, int minutes) {
        this(UUID.randomUUID().toString(), name, minutes);
    }

    // 新增构造方法：允许从 JSON 恢复原来的 ID
    public TimerStage(String id, String name, int minutes) {

        this.id = (id == null || id.isBlank())
                ? UUID.randomUUID().toString()
                : id;

        this.name.set(name);
        this.minutes.set(Math.max(1, minutes));
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name.get();
    }

    public void setName(String value) {
        name.set(value);
    }

    public StringProperty nameProperty() {
        return name;
    }

    public int getMinutes() {
        return minutes.get();
    }

    public void setMinutes(int value) {
        minutes.set(Math.max(1, value));
    }

    public IntegerProperty minutesProperty() {
        return minutes;
    }

    public String getAudioFile() {
        return audioFile.get();
    }

    public void setAudioFile(String value) {
        audioFile.set(value == null ? "" : value);
    }

    public StringProperty audioFileProperty() {
        return audioFile;
    }

    public int getAudioLeadSeconds() {
        return audioLeadSeconds.get();
    }

    public void setAudioLeadSeconds(int value) {
        audioLeadSeconds.set(Math.max(1, value));
    }

    public IntegerProperty audioLeadSecondsProperty() {
        return audioLeadSeconds;
    }

    public String getPhoneAudioId() {
        return phoneAudioId.get();
    }

    public void setPhoneAudioId(String value) {
        if (value != null && value.isBlank()) {
            value = null;
        }
        phoneAudioId.set(value);
    }

    public StringProperty phoneAudioIdProperty() {
        return phoneAudioId;
    }

    public int getPhoneVolume() {
        return phoneVolume.get();
    }

    public void setPhoneVolume(int value) {
        phoneVolume.set(Math.max(0, Math.min(100, value)));
    }

    public IntegerProperty phoneVolumeProperty() {
        return phoneVolume;
    }
}
