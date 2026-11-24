package com.studytimer.ui;

import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;

/**
 * 环形倒计时：外圈显示当前阶段已用时间比例，中间显示剩余时间。
 */
public final class TimerRing extends StackPane {

    private final Arc progressArc;

    public TimerRing(Label timeLabel, double radius) {
        getStyleClass().add("timer-ring");

        Circle track = new Circle(radius);
        track.setFill(Color.TRANSPARENT);
        track.getStyleClass().add("timer-ring-track");

        progressArc = new Arc(0, 0, radius, radius, 90, 0);
        progressArc.setType(ArcType.OPEN);
        progressArc.setFill(Color.TRANSPARENT);
        progressArc.setStrokeLineCap(StrokeLineCap.ROUND);
        progressArc.getStyleClass().add("timer-ring-progress");

        // 轨道圆与进度弧共用同一圆心，Group 的边界由完整轨道决定，进度变化不会引起抖动。
        Group ring = new Group(track, progressArc);
        getChildren().addAll(ring, timeLabel);
    }

    /** @param progress 0~1，表示当前阶段已经过的比例 */
    public void setProgress(double progress) {
        double clamped = Math.max(0, Math.min(1, progress));
        progressArc.setLength(-360 * clamped);
    }
}
