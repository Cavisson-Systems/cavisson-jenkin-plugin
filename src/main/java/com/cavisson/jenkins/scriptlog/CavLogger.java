package com.cavisson.jenkins.scriptlog;

import hudson.EnvVars;
import hudson.console.LineTransformationOutputStream;
import hudson.model.TaskListener;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class CavLogger {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String LOG_LEVEL_ENV = "LOG_LEVEL";

    /*
     * Build-specific log level.
     * This is needed because Jenkins pipeline environment{} values are available
     * through EnvVars, not always through System.getenv().
     */
    private static final ThreadLocal<LogLevel> BUILD_LOG_LEVEL = new ThreadLocal<>();

    private CavLogger() {
    }

    public static void configure(EnvVars env) {
        String configuredLevel = null;

        if (env != null) {
            configuredLevel = env.get(LOG_LEVEL_ENV);
        }

        if (configuredLevel == null || configuredLevel.trim().isEmpty()) {
            configuredLevel = System.getenv(LOG_LEVEL_ENV);
        }

        BUILD_LOG_LEVEL.set(parseLogLevel(configuredLevel));
    }

    public static void clearConfiguration() {
        BUILD_LOG_LEVEL.remove();
    }

    public static void info(TaskListener listener, String message) {
        log(listener, "INFO", message);
    }

    public static void debug(TaskListener listener, String message) {
        log(listener, "DEBUG", message);
    }

    public static void error(TaskListener listener, String message) {
        log(listener, "ERROR", message);
    }

    public static void log(TaskListener listener, String level, String message) {
        log(listener, level, message, currentLogLevel());
    }

    private static void log(TaskListener listener, String level, String message, LogLevel configuredLogLevel) {
        if (listener == null) {
            return;
        }

        LogLevel messageLevel = parseLogLevel(level);

        if (!shouldLog(configuredLogLevel, messageLevel)) {
            return;
        }

        String time = LocalTime.now().format(TIME_FORMAT);

        if (message == null) {
            message = "";
        }

        String[] lines = message.split("\\r?\\n");

        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }

            listener.getLogger().println(
                    time + " [" + messageLevel.name() + " ] " + maskSecrets(line)
            );
        }
    }

    public static OutputStream infoStream(final TaskListener listener) {
        return new LogLineOutputStream(listener, "INFO", false, currentLogLevel());
    }

    public static OutputStream debugStream(final TaskListener listener) {
        return new LogLineOutputStream(listener, "DEBUG", false, currentLogLevel());
    }

    public static OutputStream errorStream(final TaskListener listener) {
        return new LogLineOutputStream(listener, "ERROR", false, currentLogLevel());
    }

    public static OutputStream scriptStream(final TaskListener listener) {
        return new LogLineOutputStream(listener, "INFO", true, currentLogLevel());
    }

    private static LogLevel currentLogLevel() {
        LogLevel configured = BUILD_LOG_LEVEL.get();

        if (configured != null) {
            return configured;
        }

        return parseLogLevel(System.getenv(LOG_LEVEL_ENV));
    }

    private static boolean shouldLog(LogLevel configuredLogLevel, LogLevel messageLevel) {
        if (configuredLogLevel == LogLevel.OFF) {
            return false;
        }

        return messageLevel.priority >= configuredLogLevel.priority;
    }

    private static LogLevel parseLogLevel(String level) {
        if (level == null || level.trim().isEmpty()) {
            return LogLevel.INFO;
        }

        String normalized = level.trim().toUpperCase(Locale.ENGLISH);

        if ("DEBUG".equals(normalized)) {
            return LogLevel.DEBUG;
        }

        if ("INFO".equals(normalized)) {
            return LogLevel.INFO;
        }

        if ("ERROR".equals(normalized)) {
            return LogLevel.ERROR;
        }

        if ("OFF".equals(normalized) || "NONE".equals(normalized)) {
            return LogLevel.OFF;
        }

        return LogLevel.INFO;
    }

    public static String maskSecrets(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        String masked = message;

        masked = masked.replaceAll("(?i)(cavToken/)[^\\s]+", "$1****");
        masked = masked.replaceAll("(?i)(cavToken=)[^&\\s]+", "$1****");

        masked = masked.replaceAll("(?i)(--token\\s+)\\S+", "$1****");
        masked = masked.replaceAll("(?i)(--sonarToken\\s+)\\S+", "$1****");
        masked = masked.replaceAll("(?i)(--userToken\\s+)\\S+", "$1****");

        masked = masked.replaceAll("(?i)(\"token\"\\s*:\\s*\")[^\"]+\"", "$1****\"");
        masked = masked.replaceAll("(?i)(\"cavToken\"\\s*:\\s*\")[^\"]+\"", "$1****\"");
        masked = masked.replaceAll("(?i)(\"sonar_token\"\\s*:\\s*\")[^\"]+\"", "$1****\"");
        masked = masked.replaceAll("(?i)(\"sonarToken\"\\s*:\\s*\")[^\"]+\"", "$1****\"");
        masked = masked.replaceAll("(?i)(\"userToken\"\\s*:\\s*\")[^\"]+\"", "$1****\"");
        masked = masked.replaceAll("(?i)(\"adminToken\"\\s*:\\s*\")[^\"]+\"", "$1****\"");

        masked = masked.replaceAll("(?i)squ_[A-Za-z0-9_\\-]+", "****");

        return masked;
    }

    private static boolean isErrorLikeLine(String line) {
        if (line == null) {
            return false;
        }

        String lower = line.toLowerCase(Locale.ENGLISH);

        return lower.contains("error")
                || lower.contains("failed")
                || lower.contains("failure")
                || lower.contains("exception")
                || lower.contains("exited with code")
                || lower.contains("build failed")
                || lower.contains("http 4")
                || lower.contains("http 5");
    }

    private enum LogLevel {
        DEBUG(10),
        INFO(20),
        ERROR(30),
        OFF(100);

        private final int priority;

        LogLevel(int priority) {
            this.priority = priority;
        }
    }

    private static final class LogLineOutputStream extends LineTransformationOutputStream {

        private final TaskListener listener;
        private final String defaultLevel;
        private final boolean autoClassify;
        private final LogLevel configuredLogLevel;

        private LogLineOutputStream(TaskListener listener,
                                    String defaultLevel,
                                    boolean autoClassify,
                                    LogLevel configuredLogLevel) {
            this.listener = listener;
            this.defaultLevel = defaultLevel;
            this.autoClassify = autoClassify;
            this.configuredLogLevel = configuredLogLevel;
        }

        @Override
        protected void eol(byte[] bytes, int len) throws IOException {
            int end = len;

            while (end > 0 && (bytes[end - 1] == '\n' || bytes[end - 1] == '\r')) {
                end--;
            }

            String line = new String(bytes, 0, end, StandardCharsets.UTF_8);

            if (line.trim().isEmpty()) {
                return;
            }

            String level = defaultLevel;

            if (autoClassify && isErrorLikeLine(line)) {
                level = "ERROR";
            }

            CavLogger.log(listener, level, line, configuredLogLevel);
        }
    }
}