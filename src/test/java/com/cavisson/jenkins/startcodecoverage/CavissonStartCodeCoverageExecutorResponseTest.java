package com.cavisson.jenkins.startcodecoverage;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in parsing of the /v2/webreport/report/coverageReport/codeCovStart response shape,
 * matching task-start-coverage/index.js in the Azure DevOps extension: the boolean "success"
 * field gates the outcome, and a nested "data.state" of "RUNNING" confirms coverage started.
 */
class CavissonStartCodeCoverageExecutorResponseTest {

    @Test
    void successResponseIsRecognized() {
        JSONObject json = new JSONObject(
                "{\"success\":true,\"data\":{\"state\":\"RUNNING\",\"uuid\":\"abc-123\",\"applicationName\":\"myApp\"}}");

        assertTrue(CavissonStartCodeCoverageExecutor.isSuccess(json));
        assertEquals("RUNNING", json.getJSONObject("data").optString("state", ""));
        assertEquals("abc-123", json.getJSONObject("data").optString("uuid", ""));
    }

    @Test
    void failureResponseIsRecognizedWithMessage() {
        JSONObject json = new JSONObject("{\"success\":false,\"message\":\"Application not found\"}");

        assertFalse(CavissonStartCodeCoverageExecutor.isSuccess(json));
        assertEquals("Application not found", CavissonStartCodeCoverageExecutor.failureMessage(json));
    }

    @Test
    void missingMessageFallsBackToDefault() {
        JSONObject json = new JSONObject("{\"success\":false}");

        assertEquals("Unknown error received from API", CavissonStartCodeCoverageExecutor.failureMessage(json));
    }
}
