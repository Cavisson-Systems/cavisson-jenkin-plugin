package com.cavisson.jenkins.stopcodecoverage;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Locks in parsing of the /v2/webreport/report/coverageReport/codeCovStop response shape,
 * matching task-end-coverage/index.js in the Azure DevOps extension: the boolean "success"
 * field gates the outcome, and a nested "data.state" of "STOPPED" confirms coverage stopped.
 */
public class CavissonStopCodeCoverageExecutorResponseTest {

    @Test
    public void successResponseIsRecognized() {
        JSONObject json = new JSONObject(
                "{\"success\":true,\"data\":{\"state\":\"STOPPED\",\"coverageUuid\":\"412b14eb-6c75-43c3-9f4a-d10d801f1920\","
                        + "\"appName\":\"Butique\",\"reportUrl\":\"/reports/myApp/1\","
                        + "\"coverageXmlPath\":\"/home/cavisson/work/logs/codeAnalyzer/report_412b14eb-6c75-43c3-9f4a-d10d801f1920.xml\"}}");

        assertTrue(CavissonStopCodeCoverageExecutor.isSuccess(json));
        assertEquals("STOPPED", json.getJSONObject("data").optString("state", ""));
        assertEquals("412b14eb-6c75-43c3-9f4a-d10d801f1920", json.getJSONObject("data").optString("coverageUuid", ""));
        assertEquals("Butique", json.getJSONObject("data").optString("appName", ""));
        assertEquals("/reports/myApp/1", json.getJSONObject("data").optString("reportUrl", ""));
        assertEquals("/home/cavisson/work/logs/codeAnalyzer/report_412b14eb-6c75-43c3-9f4a-d10d801f1920.xml",
                json.getJSONObject("data").optString("coverageXmlPath", ""));
    }

    @Test
    public void failureResponseIsRecognizedWithMessage() {
        JSONObject json = new JSONObject("{\"success\":false,\"message\":\"Application not found\"}");

        assertFalse(CavissonStopCodeCoverageExecutor.isSuccess(json));
        assertEquals("Application not found", CavissonStopCodeCoverageExecutor.failureMessage(json));
    }

    @Test
    public void missingMessageFallsBackToDefault() {
        JSONObject json = new JSONObject("{\"success\":false}");

        assertEquals("Unknown error received from API", CavissonStopCodeCoverageExecutor.failureMessage(json));
    }
}
