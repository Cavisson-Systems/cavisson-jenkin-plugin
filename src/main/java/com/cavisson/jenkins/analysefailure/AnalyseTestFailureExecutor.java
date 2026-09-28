package com.cavisson.jenkins.analysefailure;

import com.cavisson.jenkins.connection.CavissonConnection;
import com.cavisson.jenkins.env.CavissonDescriptionPublisher;
import com.cavisson.jenkins.env.CavissonEnvironmentPublisher;
import com.cavisson.jenkins.http.HttpUtil;
import com.cavisson.jenkins.log.CavLogger;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Calls the "Cav Codefix Agent" analysis API (documented in
 * analyze-failure-api-reference.md): a three-call flow -
 * prepareAnalysisContext -&gt; runAnalysisAsCli -&gt; poll shellStatus - run once per failing test
 * run. Given a bare {@code trNumber}, analyses just that one. Given a {@code tsrNumber}, fetches
 * that Test Suite Run's JUnit report, finds every failing testcase, and analyses all of them
 * concurrently (bounded by a configurable, clamped thread count). Shared by both
 * {@link AnalyseTestFailureBuilder} (Freestyle) and {@link AnalyseTestFailureStep} (Pipeline).
 */
final class AnalyseTestFailureExecutor {

    private static final String ANALYSIS_API_PATH = "/tomcat/master/DashboardServer/v2/web/cavOpenhands";
    private static final String JUNIT_REPORT_API_PATH = "/DashboardServer/v2/scenario/cicd";
    private static final long POLL_INTERVAL_MILLIS = 60000L;
    private static final int MAX_POLL_ATTEMPTS = 20;
    private static final int MIN_CONCURRENCY = 1;
    private static final int MAX_CONCURRENCY = 8;

    private AnalyseTestFailureExecutor() {
    }

    static Map<String, Object> run(Run<?, ?> run,
                                    FilePath workspace,
                                    EnvVars env,
                                    TaskListener listener,
                                    CavissonConnection connection,
                                    String tsrNumber,
                                    String trNumber,
                                    String scenario,
                                    String project,
                                    String subProject,
                                    String userName,
                                    String workProfileName,
                                    int concurrency) throws IOException, InterruptedException {

        CavLogger log = new CavLogger(listener, env);

        String resolvedTsrNumber = expand(env, tsrNumber);
        String resolvedTrNumber = expand(env, trNumber);

        boolean hasTsr = resolvedTsrNumber != null && !resolvedTsrNumber.trim().isEmpty();
        boolean hasTr = resolvedTrNumber != null && !resolvedTrNumber.trim().isEmpty();

        if (hasTsr == hasTr) {
            throw new AbortException("Exactly one of Test Suite Run (tsrNumber) or Test Run (trNumber) must be provided.");
        }

        String baseUrl = connection.getBaseUrl();
        String apiToken = connection.getApiToken();
        boolean allowInsecureSSL = true;

        // String reportUrl = baseUrl.replaceAll("/+$", "") + "/UnifiedDashboard/share.html?open=analysisfailure-test-report" + "&pipelineId=" + urlEncode(pipelineId) + "&pipelineRunId=" + urlEncode(pipelineRunId);
        String reportUrl = baseUrl.replaceAll("/+$", "") + "/UnifiedDashboard/share.html?tsr=" + urlEncode(resolvedTsrNumber) + "&open=test-execution-report&Status=Failure";
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("cavToken", apiToken);

        List<AnalysisTarget> targets;
        if (hasTr) {
            String resolvedScenario = expand(env, scenario);
            String resolvedProject = expand(env, project);
            String resolvedSubProject = expand(env, subProject);
            String resolvedUserName = expand(env, userName);
            String resolvedWorkProfileName = expand(env, workProfileName);

            requireNonEmpty(resolvedScenario, "Scenario is required when Test Run (trNumber) is used directly.");
            requireNonEmpty(resolvedProject, "Project is required when Test Run (trNumber) is used directly.");
            requireNonEmpty(resolvedSubProject, "Sub Project is required when Test Run (trNumber) is used directly.");
            requireNonEmpty(resolvedUserName, "User Name is required when Test Run (trNumber) is used directly.");
            requireNonEmpty(resolvedWorkProfileName, "Work Profile Name is required when Test Run (trNumber) is used directly.");

            targets = new ArrayList<>();
            targets.add(new AnalysisTarget("", resolvedTrNumber.trim(), resolvedScenario, resolvedProject,
                    resolvedSubProject, resolvedUserName, resolvedWorkProfileName));

            log.info("========== Cavisson Analyse Test Failure ==========");
            log.info("Test Run   : " + resolvedTrNumber.trim());
            log.info("Scenario   : " + resolvedScenario);
            log.info("====================================================");
        } else {
            log.info("========== Cavisson Analyse Test Failure ==========");
            log.info("Test Suite Run : " + resolvedTsrNumber.trim());
            log.info("====================================================");

            String junitXml = loadJunitReport(workspace, log, baseUrl, headers, allowInsecureSSL, resolvedTsrNumber.trim());
            targets = JunitFailureParser.parseFailingTestcases(junitXml);

            if (targets.isEmpty()) {
                log.info("No failing testcases found in Test Suite Run " + resolvedTsrNumber.trim() + ".");
            } else {
                log.info("Found " + targets.size() + " failing testcase(s) to analyse.");
                for (AnalysisTarget target : targets) {
                    log.info("  trNumber=" + target.trNumber + " tsrNumber=" + target.tsrNumber
                            + " scenario=" + target.scenario);
                }
            }
        }
        
        int clampedConcurrency = clampConcurrency(concurrency);
        log.debug("Concurrency: " + clampedConcurrency);

        List<Map<String, Object>> results = analyseAll(env, baseUrl, headers, allowInsecureSSL, targets, clampedConcurrency, log);

        long completedCount = results.stream().filter(r -> "completed".equals(r.get("shellStatus"))).count();
        long failedCount = results.size() - completedCount;

        for (Map<String, Object> result : results) {
            log.info("Test Run " + result.get("trNumber") + " (" + result.get("scenario") + "): "
                    + result.get("shellStatus") + (result.get("error") != null && !String.valueOf(result.get("error")).isEmpty()
                    ? " - " + result.get("error") : ""));
        }

        Map<String, String> envVars = new LinkedHashMap<>();
        envVars.put("CAV_ANALYSIS_COUNT", String.valueOf(results.size()));
        envVars.put("CAV_ANALYSIS_COMPLETED_COUNT", String.valueOf(completedCount));
        envVars.put("CAV_ANALYSIS_FAILED_COUNT", String.valueOf(failedCount));
        envVars.put("CAV_ANALYSIS_RESULTS_JSON", new JSONArray(results).toString());

        if (results.isEmpty()) {
            log.debug("No Failed Test Cases found to Analyse");
        } else {
            log.info("Analysis completed.");
            log.info(String.format("        Analyzed : %d", results.size()));
            log.info(String.format("        Completed: %d", completedCount));
            log.info(String.format("        Failed   : %d", failedCount));
            log.infoHyperlink("        Report   : ", reportUrl, "View Analyzed Report");

            try {
                CavissonDescriptionPublisher.appendReportRow(run, env, reportUrl, "Cavisson - Analyse Test Failure");
            } catch (IOException descriptionError) {
                log.error("Could not set build description with report link: " + descriptionError.getMessage());
            }

            envVars.put("CAV_ANALYSIS_REPORT_URL", reportUrl);
        }

        CavissonEnvironmentPublisher.publish(run, envVars);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("analyzedCount", results.size());
        summary.put("completedCount", (int) completedCount);
        summary.put("failedCount", (int) failedCount);
        summary.put("results", results);
        summary.put("reportUrl", results.isEmpty() ? "" : reportUrl);
        return summary;
    }

    private static String loadJunitReport(FilePath workspace,
                                           CavLogger log,
                                           String baseUrl,
                                           Map<String, String> headers,
                                           boolean allowInsecureSSL,
                                           String tsrNumber) throws IOException, InterruptedException {

        FilePath cachedReport = workspace.child("cavisson-test-results").child("junit-" + tsrNumber + ".xml");
        if (cachedReport.exists()) {
            log.debug("Reusing JUnit report already fetched in this build: " + cachedReport.getRemote());
            return cachedReport.readToString();
        }

        log.info("Fetching JUnit report for Test Suite Run " + tsrNumber + "...");

        JSONObject junitRequest = new JSONObject();
        junitRequest.put("testRun", tsrNumber);
        junitRequest.put("testmode", "T");
        junitRequest.put("type", "testsuite");
        junitRequest.put("executionType", "functional");
        junitRequest.put("replaceTR", "false");

        String url = baseUrl.replaceAll("/+$", "") + JUNIT_REPORT_API_PATH + "/getJunitReport";
        HttpUtil.HttpResult response = HttpUtil.postJson(url, junitRequest.toString(), headers, allowInsecureSSL);
        return response.body;
    }

    private static List<Map<String, Object>> analyseAll(EnvVars env,
                                                          String baseUrl,
                                                          Map<String, String> headers,
                                                          boolean allowInsecureSSL,
                                                          List<AnalysisTarget> targets,
                                                          int concurrency,
                                                          CavLogger log) throws InterruptedException, IOException {

        if (targets.isEmpty()) {
            return new ArrayList<>();
        }

        String apiBase = baseUrl.replaceAll("/+$", "") + ANALYSIS_API_PATH;
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(concurrency, targets.size()));

        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (AnalysisTarget target : targets) {
                Callable<Map<String, Object>> task = () -> analyseOne(env, apiBase, headers, allowInsecureSSL, target, log);
                futures.add(executor.submit(task));
            }

            List<Map<String, Object>> results = new ArrayList<>();
            for (Future<Map<String, Object>> future : futures) {
                try {
                    results.add(future.get());
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException) {
                        throw (IOException) cause;
                    }
                    throw new IOException("Analysis task failed: " + cause, cause);
                }
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }

    private static Map<String, Object> analyseOne(EnvVars env,
                                                    String apiBase,
                                                    Map<String, String> headers,
                                                    boolean allowInsecureSSL,
                                                    AnalysisTarget target,
                                                    CavLogger log) throws IOException, InterruptedException {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trNumber", target.trNumber);
        result.put("tsrNumber", target.tsrNumber);
        result.put("scenario", target.scenario);
        result.put("project", target.project);
        result.put("subProject", target.subProject);
        result.put("pipelineId", envValue(env, "pipelineId", "PIPELINE_ID", "JOB_NAME"));
        result.put("pipelineRunId", envValue(env, "pipelineRunId", "PIPELINE_RUN_ID", "BUILD_NUMBER"));

        JSONObject call1Body = new JSONObject();
        call1Body.put("scenario", target.scenario);
        call1Body.put("projectName", target.project);
        call1Body.put("subProjectName", target.subProject);
        call1Body.put("userName", target.userName);
        call1Body.put("workProfileName", target.workProfileName);
        call1Body.put("trNumber", target.trNumber);
        call1Body.put("tsrNumber", target.tsrNumber);
        call1Body.put("requestFromCli", true);

        log.debug("[TR " + target.trNumber + "] prepareAnalysisContext request: " + call1Body);
        HttpUtil.HttpResult call1Response = HttpUtil.postJson(apiBase + "/prepareAnalysisContext", call1Body.toString(), headers, allowInsecureSSL);
        log.debug("[TR " + target.trNumber + "] prepareAnalysisContext response: " + call1Response.body);
        JSONObject call1Json = new JSONObject(call1Response.body);

        String call1Error = errorMessage(call1Json);
        if (call1Error != null) {
            result.put("shellStatus", "error");
            result.put("error", call1Error);
            return result;
        }

        JSONObject call2Body = new JSONObject(call1Json.toString());
        call2Body.remove("error");
        call2Body.put("scenario", target.scenario);
        call2Body.put("projectName", target.project);
        call2Body.put("subProjectName", target.subProject);
        call2Body.put("userName", target.userName);
        call2Body.put("workProfileName", target.workProfileName);
        call2Body.put("trNumber", target.trNumber);
        call2Body.put("tsrNumber", target.tsrNumber);
        call2Body.put("pipelineId", result.get("pipelineId"));
        call2Body.put("pipelineRunId", result.get("pipelineRunId"));

        log.debug("[TR " + target.trNumber + "] runAnalysisAsCli request: " + call2Body);
        HttpUtil.HttpResult call2Response = HttpUtil.postJson(apiBase + "/runAnalysisAsCli", call2Body.toString(), headers, allowInsecureSSL);
        log.debug("[TR " + target.trNumber + "] runAnalysisAsCli response: " + call2Response.body);
        JSONObject call2Json = new JSONObject(call2Response.body);

        String call2Error = errorMessage(call2Json);
        if (call2Error != null) {
            result.put("shellStatus", "error");
            result.put("error", call2Error);
            return result;
        }

        String conversationId = call2Json.optString("conversationId", "");
        result.put("conversationId", conversationId);

        for (int attempt = 1; attempt <= MAX_POLL_ATTEMPTS; attempt++) {
            String statusUrl = apiBase + "/shellStatus?conversationId=" + urlEncode(conversationId);
            HttpUtil.HttpResult statusResponse = HttpUtil.getJson(statusUrl, headers, allowInsecureSSL);
            log.debug("[TR " + target.trNumber + "] shellStatus response: " + statusResponse.body);
            JSONObject statusJson = new JSONObject(statusResponse.body);

            String shellStatus = statusJson.optString("shellStatus", "unknown");
            String conversationUrl = statusJson.optString("conversationUrl", "");
            result.put("conversationUrl", conversationUrl);

            if ("completed".equals(shellStatus)) {
                result.put("shellStatus", "completed");
                result.put("error", "");
                result.put("llmModel", statusJson.optString("llmModel", ""));
                return result;
            } else if ("failed".equals(shellStatus)) {
                result.put("shellStatus", "failed");
                result.put("error", statusJson.optString("error", "Unknown error"));
                return result;
            }

            // "running" or "unknown" (record not committed to MongoDB yet) - keep polling.
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }

        result.put("shellStatus", "timeout");
        result.put("error", "Analysis did not complete after " + MAX_POLL_ATTEMPTS + " poll attempts.");
        return result;
    }

    static String errorMessage(JSONObject json) {
        if (!json.has("error") || json.isNull("error")) {
            return null;
        }
        String error = json.optString("error", "");
        return error.isEmpty() ? null : error;
    }

    static int clampConcurrency(int requested) {
        if (requested < MIN_CONCURRENCY) {
            return MIN_CONCURRENCY;
        }
        if (requested > MAX_CONCURRENCY) {
            return MAX_CONCURRENCY;
        }
        return requested;
    }

    private static void requireNonEmpty(String value, String message) throws AbortException {
        if (value == null || value.trim().isEmpty()) {
            throw new AbortException(message);
        }
    }

    private static String expand(EnvVars env, String value) {
        return value == null ? null : env.expand(value);
    }

    static String envValue(EnvVars env, String... names) {
        for (String name : names) {
            String value = env.get(name);
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }
}
