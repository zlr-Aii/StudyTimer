package com.studytimer.service;

import com.studytimer.model.StudySession;

import java.time.LocalDate;
import java.util.List;

/**
 * 长期目标进度（纯计算）。
 *
 * 已完成 = 开始日起，学习记录中该计划的专注秒数（休息阶段不算）。
 * 应完成 = 开始日到昨天的应做天数 × 每天遍数 × 计划当前专注分钟；今天单独显示“今天 x / y 遍”。
 * 计划改了以后，每遍分钟和剩余遍数都按当前计划重算。
 */
public record LongTermGoalProgress(
        long doneSeconds,
        long totalSeconds,
        /** 计划当前每遍的专注分钟；计划不存在或没有专注阶段时为 0。 */
        int focusMinutesPerRun,
        /** 到昨天为止应完成的秒数（不超过总量）。 */
        long expectedSeconds,
        /** 今天该计划已完成的专注秒数。 */
        long todaySeconds,
        boolean todayScheduled,
        int timesPerDay,
        /** 剩余遍数（按当前计划），无法计算时为 -1。 */
        int remainingRuns,
        /** 按计划节奏预计完成的日期，无法计算时为 null。 */
        LocalDate estimatedFinish
) {

    private static final int MAX_FORECAST_DAYS = 365 * 30;

    public static LongTermGoalProgress compute(LongTermGoalStore.Goal goal, List<StudySession> records,
                                               int focusMinutesPerRun, LocalDate today) {
        LocalDate start = goal.startLocalDate() == null ? today : goal.startLocalDate();
        long total = goal.totalHours * 3600L;
        long done = 0;
        long todayDone = 0;
        for (StudySession s : records) {
            if (!s.focus() || s.planId() == null || !s.planId().equals(goal.planId)) {
                continue;
            }
            LocalDate date;
            try {
                date = LocalDate.parse(s.date());
            } catch (RuntimeException e) {
                continue;
            }
            if (date.isBefore(start)) {
                continue;
            }
            done += Math.max(0, s.elapsedSeconds());
            if (date.equals(today)) {
                todayDone += Math.max(0, s.elapsedSeconds());
            }
        }

        long perDaySeconds = (long) goal.timesPerDay * focusMinutesPerRun * 60L;
        long expected = 0;
        if (perDaySeconds > 0 && today.isAfter(start)) {
            expected = Math.min(total, scheduledDays(goal, start, today.minusDays(1)) * perDaySeconds);
        }

        int remainingRuns = -1;
        LocalDate finish = null;
        long left = Math.max(0, total - done);
        if (focusMinutesPerRun > 0) {
            long perRun = focusMinutesPerRun * 60L;
            remainingRuns = (int) Math.min(Integer.MAX_VALUE, (left + perRun - 1) / perRun);
            if (left == 0) {
                finish = today;
            } else {
                long need = left + todayDone;
                LocalDate day = today;
                for (int i = 0; i < MAX_FORECAST_DAYS; i++, day = day.plusDays(1)) {
                    if (isScheduled(goal, day)) {
                        need -= perDaySeconds;
                        if (need <= 0) {
                            finish = day;
                            break;
                        }
                    }
                }
            }
        }

        return new LongTermGoalProgress(done, total, focusMinutesPerRun, expected, todayDone,
                isScheduled(goal, today), goal.timesPerDay, remainingRuns, finish);
    }

    public static boolean isScheduled(LongTermGoalStore.Goal goal, LocalDate day) {
        return goal.weekdays != null && goal.weekdays.contains(day.getDayOfWeek().getValue());
    }

    /** from..to（含两端）之间的应做天数。 */
    static long scheduledDays(LongTermGoalStore.Goal goal, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return 0;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        long weeks = days / 7;
        long count = weeks * (goal.weekdays == null ? 0 : goal.weekdays.size());
        LocalDate day = from.plusDays(weeks * 7);
        for (; !day.isAfter(to); day = day.plusDays(1)) {
            if (isScheduled(goal, day)) {
                count++;
            }
        }
        return count;
    }

    public boolean reached() {
        return doneSeconds >= totalSeconds;
    }

    public double fraction() {
        return totalSeconds <= 0 ? 0 : Math.min(1.0, (double) doneSeconds / totalSeconds);
    }

    /** 正数 = 超前，负数 = 落后（秒）。 */
    public long aheadSeconds() {
        return doneSeconds - expectedSeconds;
    }

    /** 今天已完成的遍数（按当前计划每遍分钟折算，向下取整）。 */
    public int todayRuns() {
        return focusMinutesPerRun <= 0 ? 0 : (int) (todaySeconds / (focusMinutesPerRun * 60L));
    }
}
