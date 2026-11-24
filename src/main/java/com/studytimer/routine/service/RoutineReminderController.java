package com.studytimer.routine.service;

import com.studytimer.routine.model.RoutineReminder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Application-level operations for routine reminders. */
public final class RoutineReminderController {

    private final RoutineReminderStorageService storageService;
    private final List<RoutineReminder> reminders = new ArrayList<>();
    private boolean initialized;

    public RoutineReminderController() {
        this(new RoutineReminderStorageService());
    }

    public RoutineReminderController(RoutineReminderStorageService storageService) {
        this.storageService = Objects.requireNonNull(storageService, "storageService");
    }

    public synchronized void initialize() throws IOException {
        reminders.clear();
        reminders.addAll(storageService.loadAll());
        initialized = true;
    }

    /** 初始化时若从备份恢复过数据，返回提示文字（只返回一次）；否则为 null。 */
    public String takeRecoveryNotice() {
        return storageService.takeRecoveryNotice();
    }

    public synchronized List<RoutineReminder> getReminders() {
        ensureInitialized();
        return reminders.stream().map(RoutineReminder::copy).toList();
    }

    public synchronized RoutineReminder add(RoutineReminder reminder) throws IOException {
        ensureInitialized();
        RoutineReminder copy = Objects.requireNonNull(reminder, "reminder").copy();
        copy.validate();
        if (findIndex(copy.getId()) >= 0) {
            throw new IllegalArgumentException("提醒 ID 已存在：" + copy.getId());
        }
        List<RoutineReminder> updated = new ArrayList<>(reminders);
        updated.add(copy);
        persist(updated);
        reminders.clear();
        reminders.addAll(updated);
        return copy.copy();
    }

    public synchronized void update(RoutineReminder reminder) throws IOException {
        ensureInitialized();
        RoutineReminder copy = Objects.requireNonNull(reminder, "reminder").copy();
        copy.validate();
        int index = findIndex(copy.getId());
        if (index < 0) {
            throw new IllegalArgumentException("找不到提醒：" + copy.getId());
        }
        List<RoutineReminder> updated = new ArrayList<>(reminders);
        updated.set(index, copy);
        persist(updated);
        reminders.clear();
        reminders.addAll(updated);
    }

    public synchronized void delete(String reminderId) throws IOException {
        ensureInitialized();
        int index = findIndex(reminderId);
        if (index < 0) {
            throw new IllegalArgumentException("找不到提醒：" + reminderId);
        }
        List<RoutineReminder> updated = new ArrayList<>(reminders);
        updated.remove(index);
        persist(updated);
        reminders.clear();
        reminders.addAll(updated);
    }

    public synchronized void setEnabled(String reminderId, boolean enabled) throws IOException {
        ensureInitialized();
        int index = findIndex(reminderId);
        if (index < 0) {
            throw new IllegalArgumentException("找不到提醒：" + reminderId);
        }
        RoutineReminder updated = reminders.get(index).copy();
        updated.setEnabled(enabled);
        List<RoutineReminder> candidate = new ArrayList<>(reminders);
        candidate.set(index, updated);
        persist(candidate);
        reminders.clear();
        reminders.addAll(candidate);
    }

    /** Marks a one-time reminder as consumed and persists that state. */
    public synchronized void markCompleted(String reminderId) throws IOException {
        ensureInitialized();
        int index = findIndex(reminderId);
        if (index < 0) {
            throw new IllegalArgumentException("找不到提醒：" + reminderId);
        }
        RoutineReminder updated = reminders.get(index).copy();
        updated.setCompleted(true);
        List<RoutineReminder> candidate = new ArrayList<>(reminders);
        candidate.set(index, updated);
        persist(candidate);
        reminders.clear();
        reminders.addAll(candidate);
    }

    private int findIndex(String reminderId) {
        for (int i = 0; i < reminders.size(); i++) {
            if (Objects.equals(reminders.get(i).getId(), reminderId)) {
                return i;
            }
        }
        return -1;
    }

    private void persist(List<RoutineReminder> candidate) throws IOException {
        storageService.saveAll(candidate);
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Routine Reminder 尚未初始化");
        }
    }
}
