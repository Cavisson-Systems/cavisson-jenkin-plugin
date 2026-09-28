package com.cavisson.jenkins.accessibility;

import hudson.AbortException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Locks in the input-validation and severity-ranking logic used by
 * {@link AccessibilityScannerExecutor#run} before any scan/upload work happens, without needing
 * a running Jenkins instance (same style as {@code CavissonRunTestExecutorStartResponseTest}).
 */
class AccessibilityScannerExecutorTest {

    // ===================================================================================
    // Controller validation
    // ===================================================================================

    @Test
    void validControllerIsNormalized() throws Exception {
        assertEquals("home/cavisson/work", AccessibilityScannerExecutor.validateController("/home/cavisson/work"));
        assertEquals("home/cavisson/work", AccessibilityScannerExecutor.validateController("/home/cavisson/work/"));
    }

    @Test
    void blankControllerIsRejected() {
        assertAbort(() -> AccessibilityScannerExecutor.validateController(""));
        assertAbort(() -> AccessibilityScannerExecutor.validateController(null));
    }

    /**
     * Leading slash is mandatory, not just tolerated - the same path without it is rejected with
     * a message specifically calling out the missing '/', per explicit requirement (rather than
     * being silently accepted as equivalent).
     */
    @Test
    void missingLeadingSlashIsRejected() {
        assertAbort(() -> AccessibilityScannerExecutor.validateController("home/cavisson/work"));
    }

    /**
     * Report files are always written under /home/cavisson/work on the Cavisson server (see
     * {@code resolveReportBasePath}) - a syntactically-safe Controller value naming any other path
     * is still rejected, since accepting it would silently mislead the user about where their
     * reports actually landed.
     */
    @Test
    void otherPathsAreRejectedEvenIfSyntacticallySafe() {
        assertAbort(() -> AccessibilityScannerExecutor.validateController("/cavisson/work"));
        assertAbort(() -> AccessibilityScannerExecutor.validateController("/work/"));
        assertAbort(() -> AccessibilityScannerExecutor.validateController("/teams/qa"));
    }

    @Test
    void pathTraversalInControllerIsRejected() {
        assertAbort(() -> AccessibilityScannerExecutor.validateController("../../etc/passwd"));
        assertAbort(() -> AccessibilityScannerExecutor.validateController("work/../../secrets"));
    }

    @Test
    void unsafeCharactersInControllerAreRejected() {
        assertAbort(() -> AccessibilityScannerExecutor.validateController("work; rm -rf /"));
        assertAbort(() -> AccessibilityScannerExecutor.validateController("work?x=1"));
        assertAbort(() -> AccessibilityScannerExecutor.validateController("work\nSECOND-LINE"));
    }

    // ===================================================================================
    // Application URL validation
    // ===================================================================================

    @Test
    void validHttpAndHttpsUrlsAreAccepted() throws Exception {
        assertEquals("https://example.com", AccessibilityScannerExecutor.validateApplicationUrl("https://example.com"));
        assertEquals("http://10.10.30.23:4444/UnifiedDashboard/",
                AccessibilityScannerExecutor.validateApplicationUrl("http://10.10.30.23:4444/UnifiedDashboard/"));
    }

    @Test
    void internalHttpsUrlIsAccepted() throws Exception {
        assertEquals("https://10.10.30.23:4444/UnifiedDashboard/index.html",
                AccessibilityScannerExecutor.validateApplicationUrl("https://10.10.30.23:4444/UnifiedDashboard/index.html"));
    }

    @Test
    void blankApplicationUrlIsRejected() {
        assertAbort(() -> AccessibilityScannerExecutor.validateApplicationUrl(""));
        assertAbort(() -> AccessibilityScannerExecutor.validateApplicationUrl(null));
    }

    @Test
    void nonHttpSchemeIsRejected() {
        assertAbort(() -> AccessibilityScannerExecutor.validateApplicationUrl("ftp://example.com"));
        assertAbort(() -> AccessibilityScannerExecutor.validateApplicationUrl("javascript:alert(1)"));
        assertAbort(() -> AccessibilityScannerExecutor.validateApplicationUrl("file:///etc/passwd"));
    }

    @Test
    void malformedUrlIsRejected() {
        assertAbort(() -> AccessibilityScannerExecutor.validateApplicationUrl("not a url"));
    }

    // ===================================================================================
    // Severity ranking (mirrors the ADO extension's index.js highestSeverity computation)
    // ===================================================================================

    @Test
    void highestSeverityPicksCriticalOverLowerImpacts() {
        JSONObject axeJson = new JSONObject(
                "{\"violations\":[{\"impact\":\"minor\"},{\"impact\":\"critical\"},{\"impact\":\"serious\"}]}");

        assertEquals("CRITICAL", AccessibilityScannerExecutor.highestSeverity(axeJson));
    }

    @Test
    void highestSeverityIsNoneWhenNoViolations() {
        JSONObject axeJson = new JSONObject("{\"violations\":[]}");
        assertEquals("NONE", AccessibilityScannerExecutor.highestSeverity(axeJson));
    }

    @Test
    void highestSeverityIsNoneWhenViolationsKeyMissing() {
        JSONObject axeJson = new JSONObject("{}");
        assertEquals("NONE", AccessibilityScannerExecutor.highestSeverity(axeJson));
    }

    @Test
    void highestSeverityDefaultsUnknownImpactToMinor() {
        JSONObject axeJson = new JSONObject("{\"violations\":[{}]}");
        assertEquals("MINOR", AccessibilityScannerExecutor.highestSeverity(axeJson));
    }

    // ===================================================================================
    // Helpers
    // ===================================================================================

    private interface ThrowingCall {
        void call() throws Exception;
    }

    private static void assertAbort(ThrowingCall call) {
        try {
            call.call();
            fail("Expected AbortException");
        } catch (AbortException expected) {
            // expected
        } catch (Exception e) {
            fail("Expected AbortException but got " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
