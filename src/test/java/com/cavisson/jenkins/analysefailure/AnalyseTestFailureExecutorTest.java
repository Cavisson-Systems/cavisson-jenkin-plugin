package com.cavisson.jenkins.analysefailure;

import hudson.EnvVars;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Locks in parsing of the Cav Codefix Agent's Call 1/Call 2 response shape (an "error" field
 * that is null on success, a non-empty string on failure) and the concurrency clamp described
 * in the reference document (default 1, user-adjustable up to a max of 8).
 */
public class AnalyseTestFailureExecutorTest {

    @Test
    public void errorMessageIsNullWhenErrorFieldIsJsonNull() {
        JSONObject json = new JSONObject("{\"conversationId\":\"abc\",\"error\":null}");
        assertNull(AnalyseTestFailureExecutor.errorMessage(json));
    }

    @Test
    public void errorMessageIsNullWhenErrorFieldIsAbsent() {
        JSONObject json = new JSONObject("{\"conversationId\":\"abc\"}");
        assertNull(AnalyseTestFailureExecutor.errorMessage(json));
    }

    @Test
    public void errorMessageIsNullWhenErrorFieldIsEmptyString() {
        JSONObject json = new JSONObject("{\"error\":\"\"}");
        assertNull(AnalyseTestFailureExecutor.errorMessage(json));
    }

    @Test
    public void errorMessageIsReturnedWhenPresent() {
        JSONObject json = new JSONObject("{\"error\":\"scenario.json not found at path\"}");
        assertEquals("scenario.json not found at path", AnalyseTestFailureExecutor.errorMessage(json));
    }

    @Test
    public void concurrencyWithinRangeIsUnchanged() {
        assertEquals(1, AnalyseTestFailureExecutor.clampConcurrency(1));
        assertEquals(8, AnalyseTestFailureExecutor.clampConcurrency(8));
        assertEquals(4, AnalyseTestFailureExecutor.clampConcurrency(4));
    }

    @Test
    public void concurrencyBelowOneIsClampedToOne() {
        assertEquals(1, AnalyseTestFailureExecutor.clampConcurrency(0));
        assertEquals(1, AnalyseTestFailureExecutor.clampConcurrency(-5));
    }

    @Test
    public void concurrencyAboveEightIsClampedToEight() {
        assertEquals(8, AnalyseTestFailureExecutor.clampConcurrency(9));
        assertEquals(8, AnalyseTestFailureExecutor.clampConcurrency(100));
    }

    @Test
    public void envValueUsesExactNameBeforeFallbackName() {
        EnvVars env = new EnvVars();
        env.put("pipelineId", "exact");
        env.put("PIPELINE_ID", "fallback");

        assertEquals("exact", AnalyseTestFailureExecutor.envValue(env, "pipelineId", "PIPELINE_ID", "JOB_NAME"));
    }

    @Test
    public void envValueFallsBackToUppercaseName() {
        EnvVars env = new EnvVars();
        env.put("PIPELINE_RUN_ID", "42");

        assertEquals("42", AnalyseTestFailureExecutor.envValue(env, "pipelineRunId", "PIPELINE_RUN_ID", "BUILD_NUMBER"));
    }

    @Test
    public void envValueFallsBackToFreestyleBuildVariables() {
        EnvVars env = new EnvVars();
        env.put("JOB_NAME", "freestyle-job");
        env.put("BUILD_NUMBER", "17");

        assertEquals("freestyle-job", AnalyseTestFailureExecutor.envValue(env, "pipelineId", "PIPELINE_ID", "JOB_NAME"));
        assertEquals("17", AnalyseTestFailureExecutor.envValue(env, "pipelineRunId", "PIPELINE_RUN_ID", "BUILD_NUMBER"));
    }
}
