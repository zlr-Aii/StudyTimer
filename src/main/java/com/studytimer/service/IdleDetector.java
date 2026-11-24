package com.studytimer.service;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;

/**
 * 读取 Windows 键鼠最后一次输入距今的毫秒数（GetLastInputInfo）。锁屏时也会持续增长。
 * 非 Windows 或调用失败时返回 0（即视为一直在使用）。
 */
public final class IdleDetector {

    private boolean failed;

    public long idleMillis() {
        if (failed) {
            return 0;
        }
        try {
            WinUser.LASTINPUTINFO info = new WinUser.LASTINPUTINFO();
            if (!User32.INSTANCE.GetLastInputInfo(info)) {
                return 0;
            }
            return Integer.toUnsignedLong(Kernel32.INSTANCE.GetTickCount() - info.dwTime);
        } catch (Throwable e) {
            failed = true;
            AppLog.warn("无法读取键鼠空闲时间，生命保障将不按空闲判断休息：" + e);
            return 0;
        }
    }
}
