package com.studytimer;

public enum PolarBellConnectionState {

    DISCONNECTED("未连接"),
    CONNECTING("连接中"),
    CONNECTED("已连接"),
    /** 端口可访问但手机端 App 没有监听：通常是 App 未运行或 USB 转发未建立。 */
    UNAVAILABLE("App 未运行"),
    FAILED("连接失败"),
    CLOSED("已关闭");

    private final String displayName;

    PolarBellConnectionState(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}