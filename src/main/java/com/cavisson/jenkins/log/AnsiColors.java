package com.cavisson.jenkins.log;

import java.util.Locale;

/**
 * ANSI escape codes for coloring Jenkins console output. Only renders as color if the job has
 * the AnsiColor plugin's "Color ANSI Console Output" wrapper enabled (Freestyle: build
 * environment checkbox; Pipeline: wrap the stage in {@code ansiColor('xterm')}) - otherwise these
 * raw escape sequences print as literal text. Callers should still keep a plain-text label
 * alongside any colored value so the log is legible either way.
 */
public final class AnsiColors {

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";

    private AnsiColors() {
    }

    public static String green(String text) {
        return GREEN + text + RESET;
    }

    public static String red(String text) {
        return RED + text + RESET;
    }

    public static String yellow(String text) {
        return YELLOW + text + RESET;
    }

    public static String cyan(String text) {
        return CYAN + text + RESET;
    }

    public static String bold(String text) {
        return BOLD + text + RESET;
    }

    /** success/pass -> green, fail/failed/error -> red, anything else -> yellow. */
    public static String status(String status) {
        if (status == null) {
            return "";
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        if ("success".equals(normalized) || "pass".equals(normalized)) {
            return green(status);
        }
        if ("fail".equals(normalized) || "failed".equals(normalized) || "error".equals(normalized)) {
            return red(status);
        }
        return yellow(status);
    }
}
