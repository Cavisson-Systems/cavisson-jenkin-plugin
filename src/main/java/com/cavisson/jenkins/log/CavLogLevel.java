package com.cavisson.jenkins.log;

import java.util.Locale;

/**
 * Verbosity requested via the {@code LOG_LEVEL} environment variable, shared by every task's
 * Executor. Ordinal order matters: higher ordinal = more verbose, and a message at a given
 * level prints whenever the configured level's ordinal is >= that message's level ordinal.
 */
public enum CavLogLevel {
    ERROR,
    INFO,
    DEBUG;

    /** Unset or unrecognized values fall back to INFO, matching prior (unconditional) logging behavior. */
    public static CavLogLevel fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return INFO;
        }
        try {
            return CavLogLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return INFO;
        }
    }
}
