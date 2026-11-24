


package com.studytimer;

/**
 * 打包后的入口：以 classpath 方式启动时，主类不能直接继承 Application，
 * 否则 JavaFX 会报 “JavaFX runtime components are missing”。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Main.main(args);
    }
}
