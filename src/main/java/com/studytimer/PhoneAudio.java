package com.studytimer;

public class PhoneAudio {

    private final String audioId;
    private final String name;
    private final long duration;
    private final long fileSize;
    /** 所在文件夹；空字符串 = 未分类（旧版 App 全部为空）。 */
    private final String folder;

    public PhoneAudio(String audioId, String name,
                      long duration, long fileSize) {
        this(audioId, name, duration, fileSize, "");
    }

    public PhoneAudio(String audioId, String name,
                      long duration, long fileSize, String folder) {
        this.audioId = audioId;
        this.name = name;
        this.duration = duration;
        this.fileSize = fileSize;
        this.folder = folder == null ? "" : folder;
    }

    public String getFolder() {
        return folder;
    }

    public String getAudioId() {
        return audioId;
    }

    public String getName() {
        return name;
    }

    public long getDuration() {
        return duration;
    }

    public long getFileSize() {
        return fileSize;
    }

    @Override
    public String toString() {
        return name;
    }
}