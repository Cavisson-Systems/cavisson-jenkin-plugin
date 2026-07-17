package com.cavisson.jenkins.ai.testcase.util;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * Centralized logging utility for the Cavisson AI Test Case Generation plugin.
 *
 * Log format (Amit's team standard):
 *   HH:mm:ss LEVEL  Message
 *
 * Log levels (LOG_LEVEL environment variable):
 *   ERROR  - errors only
 *   INFO   - INFO + ERROR (default)
 *   DEBUG  - DEBUG + INFO + ERROR  (includes all REST request/response details)
 *
 * LIVE EVENT STREAMING DESIGN
 * ============================
 * The backend events API returns the COMPLETE history on every poll:
 *   Poll 1 -> [A, B, C]
 *   Poll 2 -> [A, B, C, D, E]
 *   Poll 3 -> [A, B, C, D, E, F]
 *
 * streamNewEvents() is called on every poll cycle with the same printedLines
 * Set across all cycles. It prints ONLY lines not already in printedLines.
 * This produces a live tail-f effect - each backend event appears exactly
 * once in the Jenkins console in the exact order the backend produced it.
 *
 * NO TRANSFORMATION. NO SUMMARIZATION.
 * The backend log lines are printed as-is. The only processing is:
 *   1. Strip the date from the backend timestamp  [2026-07-08 15:45:55] -> 15:45:55
 *   2. Keep the LEVEL (INFO / ERROR) from the backend line
 *   3. Print the rest of the message verbatim
 *
 * Example backend line:
 *   [2026-07-08 15:46:00] INFO  GENERATE   Drafted 1 user stories
 *
 * Printed as:
 *   15:46:00 INFO  Drafted 1 user stories
 *
 * The STAGE token (GENERATE, INGEST etc.) is intentionally stripped because
 * the message text already contains all the information.
 */
public final class PluginLogger {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String SEP =
            "======================================================";

    // -- Log levels ------------------------------------------------------------

    public enum Level { ERROR, INFO, DEBUG }

    private static volatile Level currentLevel = Level.INFO;

    public static void configure(String envLogLevel) {
        if (envLogLevel == null || envLogLevel.trim().isEmpty()) {
            currentLevel = Level.INFO;
            return;
        }
        try {
            currentLevel = Level.valueOf(envLogLevel.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            currentLevel = Level.INFO;
        }
    }

    private PluginLogger() {}

    // -- Core log methods ------------------------------------------------------

    public static void logInfo(PrintStream log, String message) {
        if (currentLevel == Level.ERROR) return;
        log.println(ts() + " INFO  " + message);
    }

    public static void logWarn(PrintStream log, String message) {
        if (currentLevel == Level.ERROR) return;
        log.println(ts() + " WARN  " + message);
    }

    public static void logError(PrintStream log, String message) {
        log.println(ts() + " ERROR " + message);
    }

    public static void logDebug(PrintStream log, String message) {
        if (log == null || currentLevel != Level.DEBUG) return;
        log.println(ts() + " DEBUG " + message);
    }

    /**
     * Prints an exception's stack trace to the build log, DEBUG mode only.
     * Used for diagnosing REST call failures without polluting INFO output.
     */
    public static void logDebugStackTrace(PrintStream log, String context, Throwable t) {
        if (log == null || currentLevel != Level.DEBUG || t == null) return;
        log.println(ts() + " DEBUG " + context + " - exception stacktrace:");
        t.printStackTrace(log);
    }

    // -- REST call logging (DEBUG only) ----------------------------------------

    /**
     * Logs a REST call in DEBUG mode only.
     * Secrets (passwords, tokens) are automatically masked.
     */
    public static void logRestCall(PrintStream log,
                                   String method,
                                   String url,
                                   String payload,
                                   int    status,
                                   String response) {
        if (log == null || currentLevel != Level.DEBUG) return;

        log.println(ts() + " DEBUG " + method + " " + url);

        if (payload != null && !payload.trim().isEmpty()) {
            String safe = payload
                    .replaceAll("\"password\"\\s*:\\s*\"[^\"]*\"", "\"password\": \"***\"")
                    .replaceAll("\"cavToken\"\\s*:\\s*\"[^\"]*\"",  "\"cavToken\": \"***\"");
            log.println(ts() + " DEBUG Request Payload:");
            log.println(safe);
        }

        log.println(ts() + " DEBUG Response Status : " + status);

        if (response != null && !response.trim().isEmpty()) {
            log.println(ts() + " DEBUG Response Body:");
            log.println(truncate(response, 3000));
        }
    }

    // -- Live event streaming --------------------------------------------------

    /**
     * Streams backend event lines live during polling.
     *
     * Called on every poll cycle with the FULL event log from the backend.
     * Prints only lines not yet in printedLines (dedup).
     * Each new line is printed verbatim - no summarization, no rewriting.
     *
     * Backend line format:
     *   [2026-07-08 15:45:55] INFO  GENERATE   Drafted 1 user stories
     *
     * Printed as:
     *   15:45:55 INFO  Drafted 1 user stories
     *
     * ERROR lines from the backend are printed regardless of LOG_LEVEL.
     * INFO lines are skipped when LOG_LEVEL=ERROR.
     *
     * @param log          Jenkins console PrintStream
     * @param rawEvents    complete event text from GET /progress?format=events
     * @param printedLines dedup Set - caller creates once, passes every cycle
     */
    /**
     * Streams backend event lines live during polling.
     *
     * The events API returns the COMPLETE history on every poll.
     * This method prints ONLY lines not yet in printedLines (dedup).
     *
     * Every line is printed EXACTLY as received from the backend.
     * No parsing. No formatting. No filtering. No modification.
     *
     * Backend returns:
     *   [2026-07-10 11:34:09] INFO  GENERATE   Drafted 1 user stories
     *   [2026-07-10 11:34:09] DEBUG GENERATE   Calling LLM with prompt...
     *   [2026-07-10 11:34:19] ERROR GENERATE   Generation error: Result file not found.
     *
     * Jenkins prints:
     *   [2026-07-10 11:34:09] INFO  GENERATE   Drafted 1 user stories
     *   [2026-07-10 11:34:09] DEBUG GENERATE   Calling LLM with prompt...
     *   [2026-07-10 11:34:19] ERROR GENERATE   Generation error: Result file not found.
     *
     * @param log          Jenkins console PrintStream
     * @param rawEvents    raw text from GET /progress?format=events
     * @param printedLines dedup Set - caller creates once, passes on every poll cycle
     */
    public static void streamNewEvents(PrintStream log,
                                       String rawEvents,
                                       Set<String> printedLines) {
        if (log == null || rawEvents == null || rawEvents.trim().isEmpty()) return;

        for (String line : rawEvents.split("\\r?\\n")) {
            // Skip blank lines
            if (line.trim().isEmpty()) continue;

            // Dedup - skip lines already printed in a previous poll cycle
            if (printedLines.contains(line)) continue;
            printedLines.add(line);

            // Print exactly what the backend sent - zero modification
            log.println(line);
        }
    }

    // -- Final Pipeline Summary ------------------------------------------------
    // Printed exactly ONCE after the pipeline reaches a terminal state.
    // This is the only structured output block in the entire plugin.

    public static void printFinalSummary(PrintStream log,
                                         String pipelineId,
                                         String project,
                                         String subproject,
                                         String sourceType,
                                         String storyCount,
                                         String testcaseCount,
                                         String epicKey,
                                         String batchId,
                                         String testsuiteName,
                                         long   durationSeconds) {
        log.println(SEP);
        log.println(ts() + " INFO  AI Test Case Generation - Completed");
        log.println(SEP);
        log.println(ts() + " INFO  Pipeline ID  : " + pipelineId);
        log.println(ts() + " INFO  Project      : " + project + " / " + subproject);
        log.println(ts() + " INFO  Source       : " + sourceType);
        log.println(ts() + " INFO  Test Suite   : " + testsuiteName);
        log.println(ts() + " INFO  User Stories : " + storyCount);
        log.println(ts() + " INFO  Test Cases   : " + testcaseCount);
        if (notBlank(epicKey))
            log.println(ts() + " INFO  Epic         : " + epicKey);
        if (notBlank(batchId))
            log.println(ts() + " INFO  Batch ID     : " + batchId);
        log.println(ts() + " INFO  Duration     : " + durationSeconds + "s");
        log.println(ts() + " INFO  Status       : SUCCESS");
        log.println(SEP);
    }

    // -- Failure summary -------------------------------------------------------

    public static void printFailureSummary(PrintStream log,
                                           String failedStage,
                                           String reason) {
        log.println(SEP);
        log.println(ts() + " ERROR Pipeline Failed");
        if (notBlank(failedStage))
            log.println(ts() + " ERROR Failed Stage : " + failedStage);
        if (notBlank(reason))
            log.println(ts() + " ERROR Reason       : " + reason);
        log.println(ts() + " ERROR Action       : Provide the Pipeline ID to the Cavisson backend team.");
        log.println(SEP);
    }

    // -- Abort notice ----------------------------------------------------------

    public static void printAbort(PrintStream log, String pipelineId) {
        log.println(ts() + " WARN  Build aborted - sending abort signal to: " + pipelineId);
    }

    // -- Internal helpers ------------------------------------------------------

    private static String ts() {
        return LocalTime.now().format(TIME_FMT);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(empty)";
        return s.length() <= max ? s : s.substring(0, max) + "\n... [truncated]";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty() && !"null".equals(s);
    }




}
