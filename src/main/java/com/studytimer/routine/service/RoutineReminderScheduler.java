package com.studytimer.routine.service;

import com.studytimer.routine.model.RoutineReminder;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Checks routine reminders and emits trigger events.
 * Each check covers the interval since the previous check, so reminders that
 * fell into a sleep / shutdown gap are emitted afterwards (marked as missed),
 * as long as they are no older than {@link #MISSED_LIMIT}.
 * This class has no JavaFX dependency; listeners are called on its daemon thread.
 */
public final class RoutineReminderScheduler implements AutoCloseable {

    /** 错过超过这个时长的提醒不再补发（一次性提醒直接标记完成）。 */
    public static final Duration MISSED_LIMIT = Duration.ofHours(24);

    private final RoutineReminderController reminderController;
    private final Consumer<TriggeredReminder> triggerListener;
    private final Consumer<Throwable> errorHandler;
    private final Clock clock;
    private final long checkIntervalMillis;
    private final RoutineReminderStateStore stateStore;
    private final Map<String, LocalDateTime> lastTriggeredOccurrences = new HashMap<>();
    private final List<RoutineReminderStateStore.Snooze> snoozedReminders = new ArrayList<>();
    private final Object stateLock = new Object();

    private LocalDateTime lastCheckedAt;
    private LocalDateTime lastSavedMinute;
    private ScheduledExecutorService executor;

    public RoutineReminderScheduler(
            RoutineReminderController reminderController,
            Consumer<TriggeredReminder> triggerListener) {
        this(reminderController, triggerListener, Throwable::printStackTrace,
                Clock.systemDefaultZone(), 1_000);
    }

    public RoutineReminderScheduler(
            RoutineReminderController reminderController,
            Consumer<TriggeredReminder> triggerListener,
            Consumer<Throwable> errorHandler,
            Clock clock,
            long checkIntervalMillis) {
        this(reminderController, triggerListener, errorHandler, clock,
                checkIntervalMillis, null);
    }

    /**
     * @param stateStore 保存上次检查时间和稍后提醒；为 null 时只保存在内存中
     */
    public RoutineReminderScheduler(
            RoutineReminderController reminderController,
            Consumer<TriggeredReminder> triggerListener,
            Consumer<Throwable> errorHandler,
            Clock clock,
            long checkIntervalMillis,
            RoutineReminderStateStore stateStore) {
        this.reminderController = Objects.requireNonNull(
                reminderController, "reminderController");
        this.triggerListener = Objects.requireNonNull(
                triggerListener, "triggerListener");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (checkIntervalMillis < 100) {
            throw new IllegalArgumentException("检查间隔不能小于 100 毫秒");
        }
        this.checkIntervalMillis = checkIntervalMillis;
        this.stateStore = stateStore;
        if (stateStore != null) {
            RoutineReminderStateStore.State state = stateStore.load();
            lastCheckedAt = state.lastCheckedAt();
            snoozedReminders.addAll(state.snoozes());
        }
    }

    /** Starts the scheduler. Calling start more than once has no effect. */
    public void start() {
        synchronized (stateLock) {
            if (executor != null && !executor.isShutdown()) {
                return;
            }
            executor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "routine-reminder-scheduler");
                thread.setDaemon(true);
                return thread;
            });
            executor.scheduleAtFixedRate(this::safeCheckNow,
                    0, checkIntervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Performs one check immediately. Public to support deterministic tests
     * and manual diagnostics; normally start() drives checks automatically.
     */
    public void checkNow() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime minuteStart = now.truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime oldest = now.minus(MISSED_LIMIT);

        List<TriggeredReminder> triggered = new ArrayList<>();
        synchronized (stateLock) {
            // 本次检查覆盖 (windowStart, now]。首次运行或时钟回拨时只看当前这一分钟。
            LocalDateTime windowStart = lastCheckedAt;
            if (windowStart == null || windowStart.isAfter(now)) {
                windowStart = minuteStart.minusNanos(1);
            }

            List<RoutineReminder> reminders = reminderController.getReminders();
            Set<String> enabledIds = new HashSet<>();
            for (RoutineReminder reminder : reminders) {
                if (reminder.isEnabled()) {
                    enabledIds.add(reminder.getId());
                }
            }

            boolean snoozesChanged = false;
            for (int i = 0; i < snoozedReminders.size();) {
                RoutineReminderStateStore.Snooze snoozed = snoozedReminders.get(i);
                // 提醒已删除或停用：丢弃对应的稍后提醒。
                boolean alive = enabledIds.contains(snoozed.reminder().getId());
                if (alive && now.isBefore(snoozed.dueAt())) {
                    i++;
                    continue;
                }
                snoozedReminders.remove(i);
                snoozesChanged = true;
                if (alive && !snoozed.dueAt().isBefore(oldest)) {
                    triggered.add(new TriggeredReminder(snoozed.reminder(), now,
                            snoozed.dueAt(), snoozed.dueAt().isBefore(minuteStart)));
                }
            }

            Set<String> currentIds = new HashSet<>();
            for (RoutineReminder reminder : reminders) {
                currentIds.add(reminder.getId());
                if (!reminder.isEnabled() || reminder.isCompleted()) {
                    continue;
                }
                LocalTime reminderTime = LocalTime.parse(reminder.getTime());
                LocalDateTime occurrence;
                if (reminder.isOneTime()) {
                    occurrence = LocalDateTime.of(
                            LocalDate.parse(reminder.getOnceDate()), reminderTime);
                    if (occurrence.isAfter(now)) {
                        continue;
                    }
                    if (!occurrence.isAfter(windowStart) || occurrence.isBefore(oldest)) {
                        // 早已过期且不在补发范围内：静默标记完成。
                        try {
                            reminderController.markCompleted(reminder.getId());
                        } catch (IOException e) {
                            reportError(e);
                        }
                        continue;
                    }
                } else {
                    occurrence = latestWeeklyOccurrence(reminder, reminderTime, now);
                    if (occurrence == null
                            || !occurrence.isAfter(windowStart)
                            || occurrence.isBefore(oldest)) {
                        continue;
                    }
                }
                if (occurrence.equals(lastTriggeredOccurrences.get(reminder.getId()))) {
                    continue;
                }

                if (reminder.isOneTime()) {
                    try {
                        reminderController.markCompleted(reminder.getId());
                    } catch (IOException e) {
                        // Do not emit a one-time reminder unless its consumed state is durable.
                        reportError(e);
                        continue;
                    }
                }
                lastTriggeredOccurrences.put(reminder.getId(), occurrence);
                triggered.add(new TriggeredReminder(reminder.copy(), now,
                        occurrence, occurrence.isBefore(minuteStart)));
            }
            lastTriggeredOccurrences.keySet().retainAll(currentIds);

            lastCheckedAt = now;
            if (snoozesChanged || !minuteStart.equals(lastSavedMinute)) {
                lastSavedMinute = minuteStart;
                saveStateLocked();
            }
        }

        for (TriggeredReminder event : triggered) {
            try {
                triggerListener.accept(event);
            } catch (Throwable error) {
                reportError(error);
            }
        }
    }

    private static LocalDateTime latestWeeklyOccurrence(
            RoutineReminder reminder, LocalTime time, LocalDateTime now) {
        Set<String> days = reminder.getRepeatDays();
        for (int i = 0; i <= 7; i++) {
            LocalDate date = now.toLocalDate().minusDays(i);
            DayOfWeek day = date.getDayOfWeek();
            if (!days.contains(day.name())) {
                continue;
            }
            LocalDateTime occurrence = LocalDateTime.of(date, time);
            if (!occurrence.isAfter(now)) {
                return occurrence;
            }
        }
        return null;
    }

    /**
     * Schedules the currently displayed reminder to be shown again after the
     * requested number of minutes. Snoozes are persisted when a state store is set.
     */
    public void snooze(TriggeredReminder event, int minutes) {
        Objects.requireNonNull(event, "event");
        if (minutes <= 0 || minutes > 24 * 60) {
            throw new IllegalArgumentException("稍后提醒时间必须在 1 到 1440 分钟之间");
        }
        LocalDateTime dueAt = LocalDateTime.now(clock).plus(Duration.ofMinutes(minutes));
        synchronized (stateLock) {
            snoozedReminders.add(new RoutineReminderStateStore.Snooze(event.reminder(), dueAt));
            saveStateLocked();
        }
    }

    public int getPendingSnoozeCount() {
        synchronized (stateLock) {
            return snoozedReminders.size();
        }
    }

    private void saveStateLocked() {
        if (stateStore == null) {
            return;
        }
        try {
            stateStore.save(new RoutineReminderStateStore.State(
                    lastCheckedAt, snoozedReminders));
        } catch (IOException | RuntimeException e) {
            reportError(e);
        }
    }

    private void safeCheckNow() {
        try {
            checkNow();
        } catch (Throwable error) {
            reportError(error);
        }
    }

    private void reportError(Throwable error) {
        try {
            errorHandler.accept(error);
        } catch (Throwable ignored) {
            // An error-reporting failure must not terminate the scheduler thread.
        }
    }

    public boolean isRunning() {
        synchronized (stateLock) {
            return executor != null && !executor.isShutdown();
        }
    }

    public void stop() {
        ScheduledExecutorService current;
        synchronized (stateLock) {
            current = executor;
            executor = null;
        }
        if (current != null) {
            current.shutdownNow();
        }
        synchronized (stateLock) {
            saveStateLocked();
            snoozedReminders.clear();
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * @param scheduledAt 原定触发时间（稍后提醒为到期时间）
     * @param missed      是否为错过后补发（电脑睡眠、关机或程序未运行期间）
     */
    public record TriggeredReminder(
            RoutineReminder reminder,
            LocalDateTime triggeredAt,
            LocalDateTime scheduledAt,
            boolean missed) {
        public TriggeredReminder {
            reminder = Objects.requireNonNull(reminder, "reminder").copy();
            Objects.requireNonNull(triggeredAt, "triggeredAt");
            if (scheduledAt == null) {
                scheduledAt = triggeredAt;
            }
        }

        public TriggeredReminder(RoutineReminder reminder, LocalDateTime triggeredAt) {
            this(reminder, triggeredAt, triggeredAt, false);
        }

        @Override
        public RoutineReminder reminder() {
            return reminder.copy();
        }
    }
}
