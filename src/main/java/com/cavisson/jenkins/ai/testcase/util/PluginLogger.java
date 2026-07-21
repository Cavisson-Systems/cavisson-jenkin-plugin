package com.cavisson.jenkins.ai.testcase.util;

import com.cavisson.jenkins.log.CavLogger;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Test Case Generation pipeline-specific log formatting, built on top of the shared
 * {@link CavLogger} (level gating, prefixes and secret-masking all live there now - this
 * class only knows how to lay out this pipeline's structured output: live event streaming
 * dedup, and the final/failure/abort summaries).
 */
public final class PluginLogger {

    private PluginLogger() {}

    // -- Live event streaming --------------------------------------------------

    /**
     * Backend event line shape: {@code [2026-07-21 12:21:05] INFO  GENERATE   message...}.
     * Group 1 is the stage token (GENERATE, FINALIZE, ...), group 2 is the message with the
     * timestamp/level/stage prefix stripped.
     */
    private static final Pattern EVENT_LINE =
            Pattern.compile("^\\[[^\\]]+\\]\\s+\\S+\\s+(\\S+)\\s+(.*)$");

    /**
     * Only these two milestones are worth surfacing at INFO by default - everything else the
     * backend emits (LLM calls, ingestion chatter, intermediate stage noise) is internal detail,
     * still available in full via LOG_LEVEL=DEBUG.
     */
    private static final Pattern[] INFO_WORTHY_MESSAGE = {
            Pattern.compile("^Testcases generated from .*"),
            Pattern.compile("^Testcase '.*' automated and saved .*")
    };

    /**
     * Streams backend event lines live during polling.
     *
     * The events API returns the COMPLETE history on every poll. This method prints ONLY
     * lines not yet in printedLines (dedup) - caller creates the Set once and passes it on
     * every poll cycle. Dedup bookkeeping runs for every line regardless of level so nothing
     * is skipped if the level changes mid-poll.
     *
     * Only the "Testcases generated from ..." (GENERATE) and "Testcase '...' automated and
     * saved ..." (FINALIZE) milestones print at INFO - with the backend's
     * {@code [timestamp] LEVEL STAGE} prefix trimmed off, leaving just the message. Every other
     * event line is still printed verbatim (prefix included) at DEBUG, for full diagnostics.
     */
    public static void streamNewEvents(CavLogger log,
                                       String rawEvents,
                                       Set<String> printedLines) {
        if (rawEvents == null || rawEvents.trim().isEmpty()) return;

        for (String line : rawEvents.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            if (printedLines.contains(line)) continue;
            printedLines.add(line);

            Matcher m = EVENT_LINE.matcher(line.trim());
            String message = m.matches() ? m.group(2) : null;

            if (message != null && isInfoWorthy(message)) {
                log.info(message);
            } else {
                log.debug(line);
            }
        }
    }

    private static boolean isInfoWorthy(String message) {
        for (Pattern p : INFO_WORTHY_MESSAGE) {
            if (p.matcher(message).matches()) return true;
        }
        return false;
    }

    // -- Final Pipeline Summary --------------------------------------------------
    // Printed exactly ONCE after the pipeline reaches a terminal state.

    /**
     * Prints the completion summary. The Pipeline ID itself is printed once, right after
     * trigger (see {@code CavAITestCaseBuilder.perform()}), not repeated here.
     */
    public static void printFinalSummary(CavLogger log,
                                         String project,
                                         String subproject,
                                         String sourceType,
                                         String testsuiteName,
                                         String testsuiteUrl,
                                         String storyCount,
                                         String testcaseCount,
                                         String epicKey,
                                         String batchId,
                                         long   durationSeconds) {
        log.field("Project", project + " / " + subproject);
        log.field("Source", sourceType);
        log.field("Test Suite", testsuiteName);
        if (notBlank(testsuiteUrl))
            log.field("Test Suite URL", testsuiteUrl);
        log.field("User Stories", storyCount);
        log.field("Test Cases", testcaseCount);
        if (notBlank(epicKey))
            log.field("Epic", epicKey);
        if (notBlank(batchId))
            log.field("Batch ID", batchId);
        log.field("Duration", durationSeconds + "s");
        log.field("Status", "SUCCESS");
    }

    // -- Failure summary -------------------------------------------------------

    public static void printFailureSummary(CavLogger log,
                                           String failedStage,
                                           String reason) {
        log.error("AI Test Case Generation Failed");
        if (notBlank(failedStage))
            log.errorField("Failed Stage", failedStage);
        if (notBlank(reason))
            log.errorField("Reason", reason);
    }

    // -- Abort notice ----------------------------------------------------------

    public static void printAbort(CavLogger log, String pipelineId) {
        log.warn("Build aborted - sending abort signal to pipeline " + pipelineId);
    }

    // -- Internal helpers ------------------------------------------------------

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty() && !"null".equals(s);
    }
}
