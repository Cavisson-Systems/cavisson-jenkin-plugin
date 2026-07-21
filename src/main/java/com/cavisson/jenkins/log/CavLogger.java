package com.cavisson.jenkins.log;

import hudson.EnvVars;
import hudson.model.TaskListener;

/**
 * Wraps a build's {@link TaskListener} and filters output by the {@code LOG_LEVEL} environment
 * variable (ERROR|INFO|DEBUG, default INFO) so every task's Executor logs consistently. Every
 * emitted line is prefixed {@code "[LEVEL] message"} - callers pass just the message, not their
 * own level formatting. {@code error} messages always print (they precede an
 * {@code AbortException} or report a non-fatal problem worth surfacing regardless of level);
 * {@code warn}/{@code info} messages are normal per-task narration, shown at INFO and above;
 * {@code debug} messages are raw request/response payloads, only shown when
 * {@code LOG_LEVEL=DEBUG}. This is the single logger implementation shared by every task's
 * Executor, including the AI Test Case Generation module - do not reintroduce a
 * parallel PrintStream-based logger there.
 */
public final class CavLogger {

    private final TaskListener listener;
    private final CavLogLevel level;

    public CavLogger(TaskListener listener, EnvVars env) {
        this.listener = listener;
        this.level = CavLogLevel.fromString(env == null ? null : env.get("LOG_LEVEL"));
    }

    public void error(String message) {
        print("ERROR", message);
    }

    public void warn(String message) {
        if (level.ordinal() >= CavLogLevel.INFO.ordinal()) {
            print("WARN", message);
        }
    }

    public void info(String message) {
        if (level.ordinal() >= CavLogLevel.INFO.ordinal()) {
            print("INFO", message);
        }
    }

    public void debug(String message) {
        if (level.ordinal() >= CavLogLevel.DEBUG.ordinal()) {
            print("DEBUG", message);
        }
    }

    public boolean isDebugEnabled() {
        return level.ordinal() >= CavLogLevel.DEBUG.ordinal();
    }

    /**
     * Prints a "Label : value" line, label left-padded for column alignment with the rest of
     * a task's structured output (job/server/source/summary fields etc).
     */
    public void field(String label, String value) {
        info(pad(label) + ": " + (value == null ? "" : value));
    }

    public void errorField(String label, String value) {
        error(pad(label) + ": " + (value == null ? "" : value));
    }

    /**
     * Logs a REST call in DEBUG mode only. Secrets (passwords, tokens) are automatically
     * masked before printing.
     */
    public void restCall(String method, String url, String payload, int status, String response) {
        if (!isDebugEnabled()) return;

        debug(method + " " + url);

        if (payload != null && !payload.trim().isEmpty()) {
            String safe = payload
                    .replaceAll("\"password\"\\s*:\\s*\"[^\"]*\"", "\"password\": \"***\"")
                    .replaceAll("\"cavToken\"\\s*:\\s*\"[^\"]*\"",  "\"cavToken\": \"***\"");
            debug("Request Payload:\n" + safe);
        }

        debug("Response Status : " + status);

        if (response != null && !response.trim().isEmpty()) {
            debug("Response Body:\n" + truncate(response, 3000));
        }
    }

    /** Prints an exception's stack trace, DEBUG mode only. */
    public void debugStackTrace(String context, Throwable t) {
        if (!isDebugEnabled() || t == null) return;
        debug(context + " - exception stacktrace:");
        t.printStackTrace(listener.getLogger());
    }

    private void print(String label, String message) {
        if (message == null || message.isEmpty()) {
            listener.getLogger().println();
            return;
        }
        listener.getLogger().println(prefix(label) + message);
    }

    private String prefix(String label) {
        return String.format("[%-5s] ", label);
    }

    private static String pad(String label) {
        return String.format("%-18s", label == null ? "" : label);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(empty)";
        return s.length() <= max ? s : s.substring(0, max) + "\n... [truncated]";
    }
}
