package com.cavisson.jenkins.runtest;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in parsing of the real DashboardServer /startTest response shape observed in production:
 * a boolean "success" flag indicates the trigger outcome, while "status"/"message" at this stage
 * can hold unrelated/stale values (e.g. "status":"PASS" with a nested stringified "message" even
 * though the field names suggest a final test verdict). Regression test for a bug where "status"
 * was mistakenly used to decide start success, causing every real test run to fail immediately
 * instead of proceeding to the polling loop.
 */
class CavissonRunTestExecutorStartResponseTest {

    private static final String REAL_STARTTEST_RESPONSE =
            "{\"effectiveTestMode\":\"T\",\"success\":true,\"testType\":\"functional\",\"run\":1059,"
                    + "\"message\":\"{\\\"run\\\":\\\"1059\\\",\\\"status\\\":\\\"SUCCESS\\\"}\",\"error\":\"\","
                    + "\"workProfile\":\"system\",\"cleanupRequired\":false,\"status\":\"PASS\"}";

    @Test
    void realResponseWithSuccessTrueAndMisleadingStatusIsTreatedAsSuccessful() {
        JSONObject json = new JSONObject(REAL_STARTTEST_RESPONSE);

        assertTrue(CavissonRunTestExecutor.isStartSuccessful(json));
        assertEquals(1059L, json.optLong("run", 0));
    }

    @Test
    void successFalseIsTreatedAsFailure() {
        JSONObject json = new JSONObject("{\"success\":false,\"run\":0,\"error\":\"scenario not found\"}");

        assertFalse(CavissonRunTestExecutor.isStartSuccessful(json));
        assertEquals("scenario not found", CavissonRunTestExecutor.startErrorDetail(json));
    }

    @Test
    void missingSuccessFieldFallsBackToStatusEqualsSuccess() {
        JSONObject json = new JSONObject("{\"status\":\"success\",\"run\":42}");

        assertTrue(CavissonRunTestExecutor.isStartSuccessful(json));
    }

    @Test
    void errorDetailPrefersErrorFieldOverMessage() {
        JSONObject json = new JSONObject("{\"error\":\"bad request\",\"message\":\"fallback message\"}");

        assertEquals("bad request", CavissonRunTestExecutor.startErrorDetail(json));
    }

    @Test
    void errorDetailFallsBackToMessageWhenErrorIsEmpty() {
        JSONObject json = new JSONObject("{\"error\":\"\",\"message\":\"fallback message\"}");

        assertEquals("fallback message", CavissonRunTestExecutor.startErrorDetail(json));
    }

    @Test
    void effectiveTestModeOverridesRequestedModeWhenPresent() {
        JSONObject json = new JSONObject(REAL_STARTTEST_RESPONSE);

        assertEquals("T", CavissonRunTestExecutor.resolveEffectiveMode(json, "N"));
    }

    @Test
    void effectiveTestModeFallsBackToRequestedModeWhenAbsent() {
        JSONObject json = new JSONObject("{\"success\":true,\"run\":1}");

        assertEquals("N", CavissonRunTestExecutor.resolveEffectiveMode(json, "N"));
    }

    @Test
    void failAndFailedAreBothTerminalStatuses() {
        assertTrue(CavissonRunTestExecutor.isTerminalStatus("pass"));
        assertTrue(CavissonRunTestExecutor.isTerminalStatus("fail"));
        assertTrue(CavissonRunTestExecutor.isTerminalStatus("failed"));
        assertFalse(CavissonRunTestExecutor.isTerminalStatus("error"));
        assertFalse(CavissonRunTestExecutor.isTerminalStatus(""));
    }
}
