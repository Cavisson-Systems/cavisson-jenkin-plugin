package com.cavisson.jenkins.runtest;

import com.cavisson.jenkins.connection.CavissonConnection;
import com.cavisson.jenkins.http.HttpUtil;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.ArtifactArchiver;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Java port of the Azure DevOps "CavissonRunTest" task (task/index.js): triggers a Cavisson
 * TestSuite or Load Test scenario via the DashboardServer REST API, polls until it finishes,
 * and (for Load Test runs) downloads and archives the HTML report. Shared by both
 * {@link CavissonRunTestBuilder} (Freestyle) and {@link CavissonRunTestStep} (Pipeline).
 */
final class CavissonRunTestExecutor {

    private static final String API_BASE_PATH = "/DashboardServer/v2/scenario/cicd";
    private static final long POLL_INTERVAL_MILLIS = 60000L;

    private CavissonRunTestExecutor() {
    }

    static Map<String, Object> run(Run<?, ?> run,
                                    FilePath workspace,
                                    Launcher launcher,
                                    EnvVars env,
                                    TaskListener listener,
                                    CavissonConnection connection,
                                    String testType,
                                    String project,
                                    String subProject,
                                    String username,
                                    String profile,
                                    String testSuiteName,
                                    String scenarioName) throws IOException, InterruptedException {

        String baseUrl = connection.getBaseUrl();
        String apiToken = connection.getApiToken();
        boolean allowInsecureSSL = true;

        String resolvedTestType = "LoadTest".equalsIgnoreCase(trim(testType)) ? "LoadTest" : "TestSuite";
        String mode = "LoadTest".equals(resolvedTestType) ? "N" : "T";

        String resolvedProject = expand(env, firstNonBlank(project, "default"));
        String resolvedSubProject = expand(env, firstNonBlank(subProject, "default"));
        String resolvedUsername = expand(env, firstNonBlank(username, "Cavisson"));
        String resolvedProfile = expand(env, firstNonBlank(profile, "default"));
        String targetScenario = "T".equals(mode) ? expand(env, testSuiteName) : expand(env, scenarioName);

        if (targetScenario == null || targetScenario.trim().isEmpty()) {
            throw new AbortException("T".equals(mode)
                    ? "TestSuite Name is required when Test Type is TestSuite."
                    : "Test Name (scenario) is required when Test Type is LoadTest.");
        }

        listener.getLogger().println("========== Cavisson Run Test ==========");
        listener.getLogger().println("Service Base URL : " + baseUrl);
        listener.getLogger().println("Test Type        : " + resolvedTestType);
        listener.getLogger().println("Project          : " + resolvedProject);
        listener.getLogger().println("Sub Project      : " + resolvedSubProject);
        listener.getLogger().println("Scenario         : " + targetScenario);
        listener.getLogger().println("========================================");

        String apiBase = baseUrl.replaceAll("/+$", "") + API_BASE_PATH;

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("cavToken", apiToken);

        JSONObject startBody = new JSONObject();
        startBody.put("testmode", mode);
        startBody.put("project", resolvedProject);
        startBody.put("subproject", resolvedSubProject);
        startBody.put("scenario", targetScenario);
        startBody.put("workProfile", resolvedProfile);
        startBody.put("username", resolvedUsername);

        listener.getLogger().println("Triggering Cavisson test " + resolvedProject + "/" + resolvedSubProject + "/" + targetScenario);

        HttpUtil.HttpResult startResponse = HttpUtil.postJson(apiBase + "/startTest", startBody.toString(), headers, allowInsecureSSL);
        listener.getLogger().println("startTest response: " + startResponse.body);
        JSONObject startJson = new JSONObject(startResponse.body);

        long runNo = startJson.optLong("run", 0);

        if (!isStartSuccessful(startJson) || runNo == 0) {
            throw new AbortException("Failed to start Cavisson test: " + startErrorDetail(startJson));
        }

        // The server can report back a different effective test mode than what was requested
        // (e.g. "effectiveTestMode":"N"/"T" in the startTest response); subsequent poll calls
        // must use that corrected mode, not the originally-requested one, or polling can behave
        // incorrectly against the actual run.
        String effectiveMode = resolveEffectiveMode(startJson, mode);

        listener.getLogger().println("Test triggered successfully with run number " + runNo);
        listener.getLogger().println("Polling for test completion...");

        String finalStatus = null;
        String statusMessage = "";
        String reportUrl = "";

        while (true) {
            String statusUrl = apiBase + "/checkConnectionStatus"
                    + "?testRun=" + runNo
                    + "&testmode=" + urlEncode(effectiveMode)
                    + "&scenarioName=" + urlEncode(targetScenario)
                    + "&replaceTR=false";

            HttpUtil.HttpResult statusResponse = HttpUtil.getJson(statusUrl, headers, allowInsecureSSL);
            listener.getLogger().println("checkConnectionStatus response: " + statusResponse.body);
            JSONObject statusJson = new JSONObject(statusResponse.body);

            boolean running = statusJson.optBoolean("running", false);

            if (!running) {
                finalStatus = statusJson.optString("status", "").toLowerCase(Locale.ROOT);
                statusMessage = firstNonBlank(statusJson.optString("error", ""),
                        firstNonBlank(statusJson.optString("msg", ""), statusJson.optString("message", "No message provided")));
                reportUrl = statusJson.optString("reportUrl", "");

                if (isTerminalStatus(finalStatus)) {
                    listener.getLogger().println("Test has completed with status '" + finalStatus + "'");
                    if (!reportUrl.isEmpty()) {
                        listener.getLogger().println("Report URL is - " + reportUrl);
                    }
                    break;
                } else if ("error".equals(finalStatus)) {
                    throw new AbortException("Cavisson test execution error: " + statusMessage);
                } else {
                    throw new AbortException("Unknown Cavisson test completion status: " + finalStatus);
                }
            } else {
                listener.getLogger().println("Test is still active. Waiting 60 seconds...");
                Thread.sleep(POLL_INTERVAL_MILLIS);
            }
        }

        if ("N".equals(effectiveMode)) {
            try {
                downloadAndArchiveReport(run, workspace, launcher, listener, apiBase, headers, allowInsecureSSL, runNo);
            } catch (IOException reportError) {
                listener.getLogger().println("Report download skipped: " + reportError.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("testStatus", finalStatus);
        result.put("reportUrl", reportUrl);
        result.put("runNo", runNo);
        result.put("message", statusMessage);
        return result;
    }

    private static void downloadAndArchiveReport(Run<?, ?> run,
                                                  FilePath workspace,
                                                  Launcher launcher,
                                                  TaskListener listener,
                                                  String apiBase,
                                                  Map<String, String> headers,
                                                  boolean allowInsecureSSL,
                                                  long runNo) throws IOException, InterruptedException {

        JSONObject reportRequest = new JSONObject();
        reportRequest.put("testRun", runNo);

        HttpUtil.HttpResult reportResponse = HttpUtil.postJson(apiBase + "/getHtmlReport", reportRequest.toString(), headers, allowInsecureSSL);

        FilePath reportDir = workspace.child("cavisson-report");
        reportDir.mkdirs();

        FilePath reportFile = reportDir.child("TestSuiteReport_" + runNo + ".html");
        reportFile.write(reportResponse.body, "UTF-8");

        ArtifactArchiver archiver = new ArtifactArchiver("cavisson-report/" + reportFile.getName());
        archiver.setAllowEmptyArchive(true);
        archiver.setOnlyIfSuccessful(false);
        archiver.perform(run, workspace, run.getEnvironment(listener), launcher, listener);

        listener.getLogger().println("HTML report downloaded and published to artifacts: " + reportFile.getName());
    }

    /**
     * The DashboardServer's startTest response uses a boolean "success" flag to report whether
     * the trigger itself succeeded; its "status"/"message" fields at this stage can reflect other
     * things (e.g. a stale/default test verdict such as {"status":"PASS", "message":"{...}"} seen
     * on a real server even though the field name suggests otherwise), so they must not be relied
     * upon to decide start success unless "success" is absent entirely.
     */
    static boolean isStartSuccessful(JSONObject startJson) {
        if (startJson.has("success")) {
            return startJson.optBoolean("success", false);
        }
        return "success".equalsIgnoreCase(startJson.optString("status", ""));
    }

    static String startErrorDetail(JSONObject startJson) {
        String errorDetail = startJson.optString("error", "");
        if (!errorDetail.isEmpty()) {
            return errorDetail;
        }
        return startJson.optString("message", "Unknown error");
    }

    /**
     * The server can normalize/override the requested test mode (e.g. respond with
     * "effectiveTestMode":"N" even though "T" was requested); poll calls must follow that
     * corrected mode rather than the originally-requested one.
     */
    static String resolveEffectiveMode(JSONObject startJson, String requestedMode) {
        String effectiveMode = startJson.optString("effectiveTestMode", "").trim().toUpperCase(Locale.ROOT);
        return effectiveMode.isEmpty() ? requestedMode : effectiveMode;
    }

    /** "fail" and "failed" are both observed as terminal (non-running) failure statuses. */
    static boolean isTerminalStatus(String status) {
        return "pass".equals(status) || "fail".equals(status) || "failed".equals(status);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.trim().isEmpty()) {
            return preferred;
        }
        return fallback;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String expand(EnvVars env, String value) {
        return value == null ? null : env.expand(value);
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
