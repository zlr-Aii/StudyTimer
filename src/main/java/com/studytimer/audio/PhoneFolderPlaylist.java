package com.studytimer.audio;

import com.studytimer.PhoneAudio;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/**
 * 阶段音乐“按文件夹播放”：选择保存在 phoneAudioId 里，格式为
 * {@code folder:RANDOM:<文件夹>} 或 {@code folder:ORDER:<文件夹>}（文件夹为空 = 未分类），
 * 不改变计划数据格式和手机协议；每次阶段开始时由电脑选出一首具体音频。
 */
public final class PhoneFolderPlaylist {

    public static final String PREFIX = "folder:";

    public enum Mode { RANDOM, ORDER }

    public record Selection(Mode mode, String folder) {
        public String toId() {
            return PREFIX + mode.name() + ":" + folder;
        }

        public String label() {
            String name = folder.isEmpty() ? "未分类" : folder;
            return "文件夹 " + name + "（" + (mode == Mode.RANDOM ? "随机" : "顺序") + "）";
        }
    }

    /** 每个文件夹上次播放的 audioId（顺序播放和随机避免重复用），只在本次运行内记住。 */
    private final Map<Selection, String> lastPlayed = new ConcurrentHashMap<>();
    private final RandomGenerator random;

    public PhoneFolderPlaylist() {
        this(RandomGenerator.getDefault());
    }

    PhoneFolderPlaylist(RandomGenerator random) {
        this.random = random;
    }

    public static boolean isFolderId(String phoneAudioId) {
        return phoneAudioId != null && phoneAudioId.startsWith(PREFIX);
    }

    public static Optional<Selection> parse(String phoneAudioId) {
        if (!isFolderId(phoneAudioId)) {
            return Optional.empty();
        }
        String rest = phoneAudioId.substring(PREFIX.length());
        int separator = rest.indexOf(':');
        if (separator < 0) {
            return Optional.empty();
        }
        try {
            Mode mode = Mode.valueOf(rest.substring(0, separator));
            return Optional.of(new Selection(mode, rest.substring(separator + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 从手机音频列表里选出这次要播的音频；文件夹为空时返回 empty。 */
    public Optional<PhoneAudio> next(Selection selection, List<PhoneAudio> audios) {
        List<PhoneAudio> items = audios.stream()
                .filter(a -> a.getFolder().equals(selection.folder()))
                .toList();
        if (items.isEmpty()) {
            return Optional.empty();
        }
        int lastIndex = indexOf(items, lastPlayed.get(selection));
        PhoneAudio chosen = items.get(nextIndex(lastIndex, items.size(), selection.mode(), random));
        lastPlayed.put(selection, chosen.getAudioId());
        return Optional.of(chosen);
    }

    /**
     * 下一首的位置：顺序循环；随机不连续重复同一首。last 为 -1 表示还没播过。
     * delta 为 -1 时（只用于顺序）返回上一首。
     */
    public static int nextIndex(int last, int size, Mode mode, RandomGenerator random) {
        return step(last, size, mode, random, 1);
    }

    public static int step(int last, int size, Mode mode, RandomGenerator random, int delta) {
        if (size <= 0) {
            return -1;
        }
        if (mode == Mode.ORDER) {
            if (last < 0) {
                return delta < 0 ? size - 1 : 0;
            }
            return Math.floorMod(last + delta, size);
        }
        if (size == 1) {
            return 0;
        }
        if (last < 0 || last >= size) {
            return random.nextInt(size);
        }
        return (last + 1 + random.nextInt(size - 1)) % size;
    }

    private static int indexOf(List<PhoneAudio> items, String audioId) {
        if (audioId == null) {
            return -1;
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getAudioId().equals(audioId)) {
                return i;
            }
        }
        return -1;
    }
}
