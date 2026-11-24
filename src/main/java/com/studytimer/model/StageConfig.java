package com.studytimer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * 阶段配置的数据对象。
 *
 * 用于 JSON 保存和读取，
 * 不直接参与 JavaFX 界面绑定。
 */
public class StageConfig {

    private String id;
    private String name;
    private int minutes;
    private String audioFile;
    private int audioLeadSeconds;

    // 新版手机音频 ID
    private String phoneAudioId;

    // 手机播放音量 0-100；旧 JSON 没有该字段时为 100
    private int phoneVolume = 100;

    // 仅用于读取旧版 JSON，不会写入新 JSON
    @JsonProperty(
            value = "phoneSound",
            access = JsonProperty.Access.WRITE_ONLY
    )
    private int legacyPhoneSound;

    public StageConfig() {
    }

    public StageConfig(
            String name,
            int minutes,
            String audioFile,
            int audioLeadSeconds) {

        this(
                UUID.randomUUID().toString(),
                name,
                minutes,
                audioFile,
                audioLeadSeconds,
                (String) null
        );
    }

    public StageConfig(
            String id,
            String name,
            int minutes,
            String audioFile,
            int audioLeadSeconds) {

        this(
                id,
                name,
                minutes,
                audioFile,
                audioLeadSeconds,
                (String) null
        );
    }

    // 新版完整构造方法
    public StageConfig(
            String id,
            String name,
            int minutes,
            String audioFile,
            int audioLeadSeconds,
            String phoneAudioId) {

        this.id = id;
        this.name = name;
        this.minutes = minutes;
        this.audioFile = audioFile;
        this.audioLeadSeconds = audioLeadSeconds;
        this.phoneAudioId = phoneAudioId;
    }

    // 兼容旧版代码调用
    @Deprecated
    public StageConfig(
            String id,
            String name,
            int minutes,
            String audioFile,
            int audioLeadSeconds,
            int phoneSound) {

        this(
                id,
                name,
                minutes,
                audioFile,
                audioLeadSeconds,
                legacyPhoneSoundToId(phoneSound)
        );
    }

    private static String legacyPhoneSoundToId(int phoneSound) {

        if (phoneSound == 0) {
            return null;
        }

        if (phoneSound >= 1 && phoneSound <= 3) {
            return "builtin:" + phoneSound;
        }

        throw new IllegalArgumentException(
                "旧版手机提示音编号必须为 0~3"
        );
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getMinutes() {
        return minutes;
    }

    public void setMinutes(int minutes) {
        this.minutes = minutes;
    }

    public String getAudioFile() {
        return audioFile;
    }

    public void setAudioFile(String audioFile) {
        this.audioFile = audioFile;
    }

    public int getAudioLeadSeconds() {
        return audioLeadSeconds;
    }

    public void setAudioLeadSeconds(int audioLeadSeconds) {
        this.audioLeadSeconds = audioLeadSeconds;
    }

    public String getPhoneAudioId() {

        if (phoneAudioId != null) {
            return phoneAudioId;
        }

        // 旧 JSON 没有 phoneAudioId 时，使用旧字段转换
        return legacyPhoneSoundToId(legacyPhoneSound);
    }

    public void setPhoneAudioId(String phoneAudioId) {
        this.phoneAudioId = phoneAudioId;
    }

    public int getPhoneVolume() {
        return phoneVolume;
    }

    public void setPhoneVolume(int phoneVolume) {
        this.phoneVolume = Math.max(0, Math.min(100, phoneVolume));
    }
}