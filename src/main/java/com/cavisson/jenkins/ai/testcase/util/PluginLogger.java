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
 * {@link CavLogger} (prefixes/masking live there) - this class lays out live event
 * streaming/filtering and the final/failure/abort summaries.
 */
public final class PluginLogger {

    private PluginLogger() {}

    // -- Live event streaming --------------------------------------------------

    /** {@code HH:mm:ss [LEVEL] message} starts a new event; any other line is that event's
     * continuation (multi-line) body, kept verbatim except for {@link #reshapeLinks}. */
    private static final Pattern EVENT_START =
            Pattern.compile("^(?:\\d{2}:\\d{2}:\\d{2}\\s+)?\\[\\s*(INFO|DEBUG|ERROR|WARN)\\s*\\]\\s?(.*)$",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern PIPELINE_STARTED = Pattern.compile("^Pipeline started\\b.*");

    /** Resent by the backend once per story/testcase; matched trimmed/case-insensitively
     * against {@code block.get(0)}. */
    private static final String HEADER_GENERATION = "AI Test Case Generation Completed";
    private static final String HEADER_AUTOMATION = "AI Test Case Automation Completed";

    /**
     * Fields {@link #reshapeLinks} turns into clickable lines. Generation-Completed: Story
     * line prints (as a Story-Link hyperlink) only once per story, plus a numbered "Test Case
     * N" line per occurrence. Automation-Completed: Test Case line untouched, Story printed in
     * the same hyperlinked format (falling back to relabeling "Story Link" if no separate
     * story-title line exists), Test Name turned into a Test-Link hyperlink.
     */
    private static final Pattern STORY_LINE      = Pattern.compile("^(.*\\bStory\\s*:\\s*)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STORY_LINK_LINE = Pattern.compile("^(.*\\bStory Link\\s*:\\s*)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEST_CASE_LINE  = Pattern.compile("^(.*\\bTest Case\\s*:\\s*)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEST_NAME_LINE  = Pattern.compile("^(.*\\bTest Name\\s*:\\s*)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEST_LINK_LINE  = Pattern.compile("^(.*\\bTest Link\\s*:\\s*)(.*)$", Pattern.CASE_INSENSITIVE);

    /** Rewrites a captured {@code "...Test Case..."} prefix into {@code "...Test Case <n>..."}. */
    private static final Pattern TEST_CASE_LABEL = Pattern.compile("(?i)(Test\\s*Case)(\\s*:)");

    /** Rewrites a captured {@code "...Story Link..."} prefix into {@code "...Story..."}. */
    private static final Pattern STORY_LINK_LABEL = Pattern.compile("(?i)Story\\s*Link");

    /** Marker-key prefixes stashed into the caller's {@code printedEvents} Set (never printed)
     * so Generation-Completed's per-story dedup/numbering survives the whole polling run. */
    private static final String STORY_SHOWN_MARKER    = "STORY_SHOWN|";
    private static final String TEST_CASE_SEQ_MARKER  = "TESTCASE_SEQ|";

    /** First {@code Story Link} value found in the block's continuation lines, or null. */
    private static String extractStoryLinkValue(List<String> block) {
        for (int i = 1; i < block.size(); i++) {
            Matcher m = STORY_LINK_LINE.matcher(block.get(i));
            if (m.matches()) return m.group(2).trim();
        }
        return null;
    }

    /** Counts how many entries in {@code set} start with {@code prefix}. */
    private static int countPrefixed(Set<String> set, String prefix) {
        int count = 0;
        for (String entry : set) {
            if (entry.startsWith(prefix)) count++;
        }
        return count;
    }

    /**
     * Streams backend event lines live during polling, filtered by the per-task Log Level
     * (distinct from the {@code LOG_LEVEL} env var). The events API returns the COMPLETE
     * history every poll; lines are grouped into logical events by {@link #EVENT_START} then
     * deduped against {@code printedEvents} (caller-owned Set, reused every poll cycle - also
     * carries the Generation-Completed dedup/numbering markers, see {@link #STORY_SHOWN_MARKER}).
     * An event prints only if {@code selectedLevel.ordinal() >= event's level.ordinal()}. A
     * "Pipeline started" event gets the Pipeline ID line printed right after it.
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

        String headerText    = block.get(0);
        String trimmedHeader = headerText.trim();

        // Every header prints unconditionally, except Generation-Completed's, which only
        // prints once per story (Automation-Completed's "Story" line is never deduped, so its
        // header follows suit and prints every occurrence).
        boolean printHeader = true;
        if (trimmedHeader.equalsIgnoreCase(HEADER_GENERATION)) {
            String storyKey = extractStoryLinkValue(block);
            printHeader = storyKey != null && !printedEvents.contains(STORY_SHOWN_MARKER + storyKey);
        }
        if (printHeader) {
            log.printAt(level, headerText);
        }
        reshapeLinks(log, block, printedEvents);

        if (PIPELINE_STARTED.matcher(headerText).matches()) {
            log.printAt(level, "Pipeline ID : " + pipelineId);
        }
    }

    /**
     * Reshapes the block's continuation lines per {@link #STORY_LINE} et al. Every line is
     * buffered while scanning so print order can be controlled (Story first, then everything
     * else) regardless of the backend's own line order; unmatched lines - including its
     * pre-numbered {@code "Testcase 1"/"Testcase 2"} fields, which don't match
     * {@link #TEST_CASE_LINE}'s spaced "Test Case" label - print verbatim.
     */
    private static void reshapeLinks(CavLogger log, List<String> block, Set<String> printedEvents) {
        String storyPrefix = null, storyValue = null;
        String storyLinkPrefix = null, storyLinkValue = null;
        String testCasePrefix = null, testCaseValue = null;
        String testNamePrefix = null, testNameValue = null;
        String testLinkValue = null;
        List<String> otherLines = new ArrayList<>();

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
                testCasePrefix = m.group(1);
                testCaseValue = m.group(2).trim();
            } else if ((m = TEST_NAME_LINE.matcher(line)).matches()) {
                testNamePrefix = m.group(1);
                testNameValue = m.group(2).trim();
            } else if ((m = TEST_LINK_LINE.matcher(line)).matches()) {
                testLinkValue = m.group(2).trim();
            } else {
                otherLines.add(line);
            }
        }

        if (notBlank(testNameValue) && notBlank(testLinkValue)) {
            // Automation-Completed shape.
            String storyLabelPrefix = notBlank(storyPrefix) ? storyPrefix
                    : (storyLinkPrefix != null ? STORY_LINK_LABEL.matcher(storyLinkPrefix).replaceFirst("Story") : null);
            String storyLabelText = notBlank(storyValue) ? storyValue : storyLinkValue;
            if (storyLabelPrefix != null && notBlank(storyLinkValue)) {
                log.rawHyperlink(storyLabelPrefix, storyLinkValue, storyLabelText);
            }
            printAll(log, otherLines);
            if (testCasePrefix != null) {
                log.raw(testCasePrefix + testCaseValue);
            }
            log.rawHyperlink(testNamePrefix, testLinkValue, testNameValue);
        } else if (notBlank(storyPrefix) && notBlank(storyLinkValue)) {
            // Generation-Completed shape: Story prints once per story, Test Case is numbered
            // within it via marker entries in printedEvents (no separate counter needed).
            String storyMarker = STORY_SHOWN_MARKER + storyLinkValue;
            if (!printedEvents.contains(storyMarker)) {
                log.rawHyperlink(storyPrefix, storyLinkValue, storyValue);
                printedEvents.add(storyMarker);
            }
            printAll(log, otherLines);
            if (testCasePrefix != null) {
                String seqPrefix = TEST_CASE_SEQ_MARKER + storyLinkValue + "|";
                int seq = countPrefixed(printedEvents, seqPrefix) + 1;
                printedEvents.add(seqPrefix + seq);
                String numberedPrefix = TEST_CASE_LABEL.matcher(testCasePrefix)
                        .replaceFirst("$1 " + seq + "$2");
                log.raw(numberedPrefix + testCaseValue);
            }
        } else {
            printAll(log, otherLines);
        }
    }

    private static void printAll(CavLogger log, List<String> lines) {
        for (String line : lines) {
            log.raw(line);
        }
    }

    // -- Final Pipeline Summary --------------------------------------------------
    // Printed exactly ONCE after the pipeline reaches a terminal state.

    /** DEBUG-only diagnostics; the INFO console shows the plain "Completed" line instead
     * (printed by the caller). Pipeline ID is printed once, right after trigger. */
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

