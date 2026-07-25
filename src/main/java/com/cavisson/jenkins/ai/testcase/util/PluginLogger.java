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
     * message body and is grouped along with it - the backend itself sends multi-line event
     * messages (e.g. "AI Test Case Generation Completed" followed by indented Test Case/Test
     * Name/Story Link/Test Link lines is ONE event, not several). Grouping is verbatim; the one
     * reshaping done is in {@link #reshapeLinks} - see that method's javadoc.
     */
    private static final Pattern EVENT_START =
            Pattern.compile("^(?:\\d{2}:\\d{2}:\\d{2}\\s+)?\\[\\s*(INFO|DEBUG|ERROR|WARN)\\s*\\]\\s?(.*)$",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern PIPELINE_STARTED = Pattern.compile("^Pipeline started\\b.*");

    /**
     * Continuation-line fields reshaped by {@link #reshapeLinks} into clickable lines instead of
     * printed verbatim like every other continuation line. Each pattern captures two groups: (1)
     * everything up to and including the label and its colon-space - the backend's own leading
     * {@code HH:mm:ss} timestamp and indent included, kept 100% verbatim - and (2) the field
     * value, which is the only part that gets swapped for a hyperlink. Two distinct event shapes
     * are known:
     * <ul>
     * <li>"AI Test Case Generation Completed" - {@code Story}/{@code Story Link}/{@code Test
     *     Case}, no {@code Test Link} - reshaped to the original "Story" line with its value
     *     turned into a hyperlink to Story Link, plus the original "Test Case" line untouched
     *     (nothing to link Test Case to at this stage). The "Story Link" line itself is dropped -
     *     its value was only needed as the href.</li>
     * <li>"AI Test Case Automation Completed" - {@code Test Case}/{@code Test Name}/{@code Story
     *     Link}/{@code Test Link} - reshaped to the original "Test Case" line untouched (it's
     *     unrelated to Story Link here, a different value than the Generation-Completed event's
     *     Test Case), the original "Story Link" line with its value turned into a self-link, and
     *     the original "Test Name" line with its value turned into a hyperlink to Test Link. The
     *     "Test Link" line itself is dropped - its value was only needed as the href.</li>
     * </ul>
     * {@code STORY_LINE} must not accidentally match a "Story Link" line - it can't, since
     * "Story Link :" has "Link" between "Story" and the colon, which {@code Story\s*:} rejects.
     */
    private static final Pattern STORY_LINE      = Pattern.compile("^(.*\\bStory\\s*:\\s*)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STORY_LINK_LINE = Pattern.compile("^(.*\\bStory Link\\s*:\\s*)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEST_CASE_LINE  = Pattern.compile("^(.*\\bTest Case\\s*:\\s*)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEST_NAME_LINE  = Pattern.compile("^(.*\\bTest Name\\s*:\\s*)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEST_LINK_LINE  = Pattern.compile("^(.*\\bTest Link\\s*:\\s*)(.*)$", Pattern.CASE_INSENSITIVE);

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
        reshapeLinks(log, block);

        if (PIPELINE_STARTED.matcher(block.get(0)).matches()) {
            log.printAt(level, "Pipeline ID : " + pipelineId);
        }
    }

    /**
     * Prints the block's continuation lines (block.get(1..)), collapsing the known field lines
     * into clickable ones per the two shapes documented on {@link #STORY_LINE} et al. Any other
     * continuation line (including a field quartet not fully present) is printed verbatim, in
     * its original position, exactly as before.
     */
    private static void reshapeLinks(CavLogger log, List<String> block) {
        String storyPrefix = null, storyValue = null;
        String storyLinkPrefix = null, storyLinkValue = null;
        String testCaseLine = null;
        String testNamePrefix = null, testNameValue = null;
        String testLinkValue = null;

        for (int i = 1; i < block.size(); i++) {
            String line = block.get(i);
            Matcher m;
            if ((m = STORY_LINK_LINE.matcher(line)).matches()) {
                storyLinkPrefix = m.group(1);
                storyLinkValue = m.group(2).trim();
            } else if ((m = STORY_LINE.matcher(line)).matches()) {
                storyPrefix = m.group(1);
                storyValue = m.group(2).trim();
            } else if ((m = TEST_CASE_LINE.matcher(line)).matches()) {
                testCaseLine = line;
            } else if ((m = TEST_NAME_LINE.matcher(line)).matches()) {
                testNamePrefix = m.group(1);
                testNameValue = m.group(2).trim();
            } else if ((m = TEST_LINK_LINE.matcher(line)).matches()) {
                testLinkValue = m.group(2).trim();
            } else {
                log.raw(line);
            }
        }

        if (notBlank(testNameValue) && notBlank(testLinkValue)) {
            // "AI Test Case Automation Completed" shape.
            if (testCaseLine != null) {
                log.raw(testCaseLine);
            }
            if (notBlank(storyLinkPrefix) && notBlank(storyLinkValue)) {
                log.rawHyperlink(storyLinkPrefix, storyLinkValue, storyLinkValue);
            }
            log.rawHyperlink(testNamePrefix, testLinkValue, testNameValue);
        } else if (notBlank(storyPrefix) && notBlank(storyLinkValue)) {
            // "AI Test Case Generation Completed" shape.
            log.rawHyperlink(storyPrefix, storyLinkValue, storyValue);
            if (testCaseLine != null) {
                log.raw(testCaseLine);
            }
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

