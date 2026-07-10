package com.cavisson.jenkins.log;

import hudson.EnvVars;
import hudson.model.TaskListener;

import java.util.Date;

/**
 * Wraps a build's {@link TaskListener} and filters output by the {@code LOG_LEVEL} environment
 * variable (ERROR|INFO|DEBUG, default INFO) so every task's Executor logs consistently. Every
 * emitted line is prefixed {@code "HH:mm:ss LEVEL  message"}, e.g.
 * {@code "10:00:01 INFO   Test completed."} - callers pass just the message, not their own
 * timestamp/level formatting. {@code error} messages always print (they precede an
 * {@code AbortException} or report a non-fatal problem worth surfacing regardless of level);
 * {@code info} messages are the normal per-task narration; {@code debug} messages are raw
 * request/response payloads, only shown when {@code LOG_LEVEL=DEBUG}.
 */
public final class CavLogger {

    private final TaskListener listener;
    private final CavLogLevel level;

    public CavLogger(TaskListener listener, EnvVars env) {
        this.listener = listener;
        this.level = CavLogLevel.fromString(env == null ? null : env.get("LOG_LEVEL"));
    }

    public void error(String message) {
        print(CavLogLevel.ERROR, message);
    }

    public void info(String message) {
        if (level.ordinal() >= CavLogLevel.INFO.ordinal()) {
            print(CavLogLevel.INFO, message);
        }
    }

    public void debug(String message) {
        if (level.ordinal() >= CavLogLevel.DEBUG.ordinal()) {
            print(CavLogLevel.DEBUG, message);
        }
    }

    private void print(CavLogLevel messageLevel, String message) {
        if (message == null || message.isEmpty()) {
            listener.getLogger().println();
            return;
        }
        listener.getLogger().println(prefix(messageLevel) + message);
    }

    private String prefix(CavLogLevel messageLevel) {
        return String.format("%1$tT [%2$-5s] ", new Date(), messageLevel.name());
    }
}
