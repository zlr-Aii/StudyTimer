
package com.studytimer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class FocusSessionStorage {

    private final ObjectMapper objectMapper;
    private final Path stateFile;

    public FocusSessionStorage() {

        objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        String appData = System.getenv("APPDATA");

        if (appData == null || appData.isBlank()) {
            appData = System.getProperty("user.home");
        }

        if (appData == null || appData.isBlank()) {
            throw new IllegalStateException("无法确定 StudyTimer 数据目录");
        }

        Path appDirectory = Path.of(appData, "StudyTimer");

        stateFile = appDirectory.resolve("focus-session-state.json");
    }

    /**
     * 加载上一次保存的计时状态。
     */
    public State load() throws IOException {

        if (!Files.isRegularFile(stateFile)) {
            return null;
        }

        return objectMapper.readValue(
                stateFile.toFile(),
                State.class
        );
    }

    /**
     * 原子保存计时状态。
     */
    public void save(State state) throws IOException {

        Files.createDirectories(stateFile.getParent());

        Path tempFile = stateFile.resolveSibling(
                stateFile.getFileName() + ".tmp"
        );

        try {

            objectMapper.writeValue(
                    tempFile.toFile(),
                    state
            );

            try {
                Files.move(
                        tempFile,
                        stateFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );

            } catch (java.nio.file.AtomicMoveNotSupportedException e) {

                Files.move(
                        tempFile,
                        stateFile,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * 持久化的数据结构。
     */
    public static class State {

        private int schemaVersion;
        private String planId;
        private int currentIndex;
        private long remainingSeconds;
        private boolean started;
        private boolean finished;
        private long savedAtEpochMillis;

        public State() {
        }

        public State(
                int schemaVersion,
                String planId,
                int currentIndex,
                long remainingSeconds,
                boolean started,
                boolean finished,
                long savedAtEpochMillis
        ) {
            this.schemaVersion = schemaVersion;
            this.planId = planId;
            this.currentIndex = currentIndex;
            this.remainingSeconds = remainingSeconds;
            this.started = started;
            this.finished = finished;
            this.savedAtEpochMillis = savedAtEpochMillis;
        }

        public int getSchemaVersion() {
            return schemaVersion;
        }

        public void setSchemaVersion(int schemaVersion) {
            this.schemaVersion = schemaVersion;
        }

        public String getPlanId() {
            return planId;
        }

        public void setPlanId(String planId) {
            this.planId = planId;
        }

        public int getCurrentIndex() {
            return currentIndex;
        }

        public void setCurrentIndex(int currentIndex) {
            this.currentIndex = currentIndex;
        }

        public long getRemainingSeconds() {
            return remainingSeconds;
        }

        public void setRemainingSeconds(long remainingSeconds) {
            this.remainingSeconds = remainingSeconds;
        }

        public boolean isStarted() {
            return started;
        }

        public void setStarted(boolean started) {
            this.started = started;
        }

        public boolean isFinished() {
            return finished;
        }

        public void setFinished(boolean finished) {
            this.finished = finished;
        }

        public long getSavedAtEpochMillis() {
            return savedAtEpochMillis;
        }

        public void setSavedAtEpochMillis(long savedAtEpochMillis) {
            this.savedAtEpochMillis = savedAtEpochMillis;
        }
    }
}
