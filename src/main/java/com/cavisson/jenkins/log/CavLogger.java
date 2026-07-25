package com.cavisson.jenkins.log;

import hudson.EnvVars;
import hudson.model.TaskListener;

/**
 * Wraps a build's {@link TaskListener} and filters output by the {@code LOG_LEVEL} environment
 * variable (ERROR|INFO|DEBUG, default INFO) so every task's Executor logs consistently. Every
 * emitted line is prefixed {@code "[LEVEL] message"} - callers pass just the message, not their
 * own level formatting. No timestamp is added here - Jenkins' own Timestamper plugin already
 * prefixes every console line with a wall-clock time, so adding one here would double it up.
 * {@code error} messages always print (they precede an {@code AbortException} or report a
 * non-fatal problem worth surfacing regardless of level); {@code warn}/{@code info} messages are
 * normal per-task narration, shown at INFO and above; {@code debug} messages are raw
 * request/response payloads, only shown when {@code LOG_LEVEL=DEBUG}. This is the single logger
 * implementation shared by every task's Executor, including the AI Test Case Generation module -
 * do not reintroduce a parallel PrintStream-based logger there.
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
     * Prints a line exactly as given - no {@code [LEVEL]} prefix, no gating by
     * {@code LOG_LEVEL}. For mirroring an already-formatted external log line (e.g. the
     * Agentic AI pipeline's own event stream) verbatim into the Jenkins console.
     */
    public void raw(String line) {
        listener.getLogger().println(line);
    }

    /**
     * Prints {@code "<prefix><text>"} where {@code <text>} is rendered as a clickable hyperlink
     * to {@code url} in the Jenkins console (via {@link hudson.console.HyperlinkNote}), falling
     * back to plain {@code "text (url)"} text in consumers (e.g. plain-text log downloads,
     * `mvn test` captured output) that don't render console notes. No {@code [LEVEL]} prefix, no
     * {@code LOG_LEVEL} gating - same "mirror external content verbatim" contract as {@link #raw}.
     */
    public void rawHyperlink(String prefix, String url, String text) {
        String label = (text == null || text.trim().isEmpty()) ? url : text;
        String line = (prefix == null ? "" : prefix) + hudson.console.HyperlinkNote.encodeTo(url, label);
        listener.getLogger().println(line);
    }

    /**
     * Prints {@code "[label] message"} unconditionally, ignoring this logger's own
     * {@code LOG_LEVEL} gating. For callers that do their own level-selection against a
     * verbosity setting distinct from {@code LOG_LEVEL} - e.g. filtering an already-leveled
     * external event stream (Agentic AI pipeline events) against a per-task "Log Level" field,
     * where {@code LOG_LEVEL} itself must keep governing this logger's own REST/debug
     * diagnostics unaffected by that per-task setting.
     */
    public void printAt(String label, String message) {
        print(label, message);
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

