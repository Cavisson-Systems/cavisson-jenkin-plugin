package com.cavisson.jenkins.ai.testcase.util;

import com.cavisson.jenkins.log.CavLogger;
import com.cavisson.jenkins.log.CavLogLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Test Case Generation pipeline-specific log formatting, built on top of the shared
 * {@link CavLogger} (prefixes and secret-masking live there - this class only knows how to lay
 * out this pipeline's structured output: live event streaming/filtering, and the
 * final/failure/abort summaries).
 */
public final class PluginLogger {

    private PluginLogger() {}

    // -- Live event streaming --------------------------------------------------

    /**
     * Confirmed real backend event line shape: {@code HH:mm:ss [LEVEL] message} - a plain
     * time (no date, no brackets around it), then the level padded to 5 chars inside brackets,
     * then the message. A line matching this shape starts a new event; any line that does NOT
     * match it (blank lines, indented continuation text) belongs to the PREVIOUS event's
     * message body and is carried along verbatim - the backend itself sends multi-line event
     * messages (e.g. "AI Test Case Generation Completed" followed by indented
     * Story/Story Link/Test Case lines is ONE event, not several - no plugin-side reshaping
     * needed or wanted here).
     */
    private static final Pattern EVENT_START =
            Pattern.compile("^\\d{2}:\\d{2}:\\d{2}\\s+\\[\\s*(INFO|DEBUG|ERROR|WARN)\\s*\\]\\s?(.*)$",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern PIPELINE_STARTED = Pattern.compile("^Pipeline started\\b.*");

    /**
     * Streams backend event lines live during polling, filtered by the per-task Log Level
     * setting (distinct from the {@code LOG_LEVEL} env var, which keeps governing this
     * logger's own REST/debug diagnostics unaffected by this setting).
     *
     * The events API ({@code GET .../progress/{id}?format=events}) returns the COMPLETE
     * history on every poll. Physical lines are first grouped into logical events (a line
     * matching {@link #EVENT_START} opens a new event; everything after it up to the next
     * such line is that event's multi-line message body, kept verbatim). Each COMPLETE event
     * is then deduped against {@code printedEvents} (caller creates the Set once and passes it
     * on every poll cycle) - grouping before dedup means a multi-line event is never split
     * across polls or re-evaluated piecemeal.
     *
     * An event prints only if {@code selectedLevel.ordinal() >= event's own level.ordinal()}
     * (same ordinal convention as {@link CavLogLevel}) - no exceptions, no forced milestones:
     * whatever the backend tags as visible at the selected level is shown exactly as sent,
     * everything else is skipped. The one convenience kept: when a "Pipeline started" event
     * passes the filter, the Pipeline ID line is printed immediately after it (Pipeline ID
     * itself comes from the trigger response, not the event stream, so it is not itself
     * subject to level filtering).
     */
    public static void streamNewEvents(CavLogger log,
                                       String rawEvents,
                                       Set<String> printedEvents,
                                       String pipelineId,
                                       CavLogLevel selectedLevel) {
        if (rawEvents == null || rawEvents.trim().isEmpty()) return;

        String[]     lines        = rawEvents.split("\\r?\\n", -1);
        String       currentLevel = null;
        List<String> currentBlock = null;

        for (String line : lines) {
            Matcher start = EVENT_START.matcher(line);
            if (start.matches()) {
                flush(log, currentLevel, currentBlock, printedEvents, pipelineId, selectedLevel);
                currentLevel = start.group(1).toUpperCase();
                currentBlock = new ArrayList<>();
                currentBlock.add(start.group(2));
            } else if (currentBlock != null) {
                currentBlock.add(line);
            }
        }
        flush(log, currentLevel, currentBlock, printedEvents, pipelineId, selectedLevel);
    }

    /** Prints one complete (possibly multi-line) event, if new and passing the level filter. */
    private static void flush(CavLogger log,
                              String level,
                              List<String> block,
                              Set<String> printedEvents,
                              String pipelineId,
                              CavLogLevel selectedLevel) {
        if (level == null || block == null) return;

        while (!block.isEmpty() && block.get(block.size() - 1).trim().isEmpty()) {
            block.remove(block.size() - 1);
        }
        if (block.isEmpty()) return;

        String key = level + "|" + String.join("\n", block);
        if (printedEvents.contains(key)) return;
        printedEvents.add(key);

        if (selectedLevel.ordinal() < CavLogLevel.fromString(level).ordinal()) return;

        log.printAt(level, block.get(0));
        for (int i = 1; i < block.size(); i++) {
            log.raw(block.get(i));
        }

        if (PIPELINE_STARTED.matcher(block.get(0)).matches()) {
            log.printAt(level, "Pipeline ID : " + pipelineId);
        }
    }

    // -- Final Pipeline Summary --------------------------------------------------
    // Printed exactly ONCE after the pipeline reaches a terminal state.

    /**
     * Logs the completion summary at DEBUG (full diagnostics only - the INFO console shows just
     * the plain "AI Test Case Generation Completed Successfully" line, printed by the caller).
     * The Pipeline ID itself is printed once, right after trigger (see
     * {@code CavAITestCaseBuilder.perform()}), not repeated here.
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
        log.debug("Project        : " + project + " / " + subproject);
        log.debug("Source         : " + sourceType);
        log.debug("Test Suite     : " + testsuiteName);
        if (notBlank(testsuiteUrl))
            log.debug("Test Suite URL : " + testsuiteUrl);
        log.debug("User Stories   : " + storyCount);
        log.debug("Test Cases     : " + testcaseCount);
        if (notBlank(epicKey))
            log.debug("Epic           : " + epicKey);
        if (notBlank(batchId))
            log.debug("Batch ID       : " + batchId);
        log.debug("Duration       : " + durationSeconds + "s");
        log.debug("Status         : SUCCESS");
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

