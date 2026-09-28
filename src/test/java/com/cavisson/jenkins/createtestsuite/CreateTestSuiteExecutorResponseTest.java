package com.cavisson.jenkins.createtestsuite;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in parsing of the /v2/scenario/data/createTestSuite response shape documented in
 * prod-src/web/scenarioservices/openapi-createTestSuite.yaml: the endpoint always answers
 * HTTP 200, and the actual outcome is carried by the "status" field ("success"/"fail").
 */
class CreateTestSuiteExecutorResponseTest {

    @Test
    void successResponseIsRecognizedAndExposesTestsuite() {
        JSONObject json = new JSONObject("{\"status\":\"success\",\"testsuite\":\"myProject/mySubProject/nightly_regression_suite\"}");

        assertTrue(CreateTestSuiteExecutor.isSuccess(json));
        assertEquals("myProject/mySubProject/nightly_regression_suite", json.optString("testsuite", ""));
    }

    @Test
    void failResponseIsRecognizedWithMessage() {
        JSONObject json = new JSONObject("{\"status\":\"fail\",\"msg\":\"No match found.\"}");

        assertFalse(CreateTestSuiteExecutor.isSuccess(json));
        assertEquals("No match found.", CreateTestSuiteExecutor.failureMessage(json));
    }

    @Test
    void missingTagsFailureMessageIsSurfaced() {
        JSONObject json = new JSONObject("{\"status\":\"fail\",\"msg\":\"tags are required\"}");

        assertFalse(CreateTestSuiteExecutor.isSuccess(json));
        assertEquals("tags are required", CreateTestSuiteExecutor.failureMessage(json));
    }

    @Test
    void parseTagsSplitsAndTrimsCommaSeparatedValues() {
        JSONArray tags = CreateTestSuiteExecutor.parseTags(" smoke ,regression,, api ");

        assertEquals(3, tags.length());
        assertEquals("smoke", tags.getString(0));
        assertEquals("regression", tags.getString(1));
        assertEquals("api", tags.getString(2));
    }

    @Test
    void parseTagsReturnsEmptyArrayForBlankInput() {
        assertEquals(0, CreateTestSuiteExecutor.parseTags("").length());
        assertEquals(0, CreateTestSuiteExecutor.parseTags(null).length());
        assertEquals(0, CreateTestSuiteExecutor.parseTags("   ").length());
    }

    @Test
    void lastPathSegmentStripsProjectAndSubproject() {
        assertEquals("nightly_regression_suite",
                CreateTestSuiteExecutor.lastPathSegment("myProject/mySubProject/nightly_regression_suite"));
    }

    @Test
    void lastPathSegmentHandlesNameWithoutSlashes() {
        assertEquals("smoke", CreateTestSuiteExecutor.lastPathSegment("smoke"));
    }

    @Test
    void lastPathSegmentHandlesBlankOrNullInput() {
        assertEquals("", CreateTestSuiteExecutor.lastPathSegment(""));
        assertEquals(null, CreateTestSuiteExecutor.lastPathSegment(null));
    }
}
