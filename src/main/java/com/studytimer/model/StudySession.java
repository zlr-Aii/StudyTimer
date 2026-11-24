package com.studytimer.model;

/**
 * 一条学习记录：一个阶段结束（自然完成、跳过、重置）时写入。
 *
 * @param date           结束日期 yyyy-MM-dd（本地时区）
 * @param endedAt        结束时间 ISO-8601 本地时间
 * @param focus          是否计入专注（名称含“休息/rest/break”的阶段不计入）
 */
public record StudySession(
        String date,
        String endedAt,
        String planId,
        String planName,
        String stageName,
        long plannedSeconds,
        long elapsedSeconds,
        boolean completed,
        boolean focus
) {
}
