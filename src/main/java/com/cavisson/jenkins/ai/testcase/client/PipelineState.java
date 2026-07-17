package com.cavisson.jenkins.ai.testcase.client;

/**
 * Represents the lifecycle states reported by
 * {@code GET /agentic/api/testcasePipeline/status/{pipelineId}}.
 *
 * <p>The polling loop in {@link CavAITestCaseExecution} transitions through
 * these states until a terminal state ({@link #COMPLETED}, {@link #FAILED},
 * or {@link #ABORTED}) is reached.
 */
public enum PipelineState {

    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    ABORTED,
    UNKNOWN;

    /**
     * Parses a raw string returned by the API into a {@link PipelineState}.
     * Returns {@link #UNKNOWN} for any value not in the enum to avoid
     * {@link IllegalArgumentException} crashing the polling loop.
     *
     * @param raw the {@code state} field from the status JSON response
     * @return the matching enum constant, or {@link #UNKNOWN}
     */
    public static PipelineState fromString(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        try {
            return PipelineState.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /** @return {@code true} if this state means the pipeline has finished. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == ABORTED;
    }

    /** @return {@code true} if the build should be marked as a failure. */
    public boolean isFailure() {
        return this == FAILED || this == ABORTED;
    }
}
