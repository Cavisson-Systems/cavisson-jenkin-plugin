package com.cavisson.jenkins.startcodecoverage;

import com.cavisson.jenkins.connection.CavissonConnection;
import com.cavisson.jenkins.env.CavissonEnvironmentPublisher;
import com.cavisson.jenkins.http.HttpUtil;
import com.cavisson.jenkins.log.CavLogger;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls the DashboardServer "Start Code Coverage" REST API
 * ({@code GET /DashboardServer/v2/webreport/report/coverageReport/codeCovStart?appName=...}),
 * matching {@code task-start-coverage/index.js} in the Azure DevOps extension, plus
 * {@code pipelineId}/{@code pipelineRunId} query params (derived from the build environment,
 * same fallback chain as {@code AnalyseTestFailureExecutor}) so the server can correlate this
 * coverage run with the pipeline that started it. A single synchronous call, no polling - the
 * response's boolean {@code success} field gates the outcome, and {@code data.state} must be
 * {@code "RUNNING"}. Shared by both {@link CavissonStartCodeCoverageBuilder} (Freestyle) and
 * {@link CavissonStartCodeCoverageStep} (Pipeline).
 */
final class CavissonStartCodeCoverageExecutor {

    private static final String API_PATH = "/DashboardServer/v2/webreport/report/coverageReport/codeCovStart";

    private CavissonStartCodeCoverageExecutor() {
    }

    static Map<String, Object> run(Run<?, ?> run,
                                    EnvVars env,
                                    TaskListener listener,
                                    CavissonConnection connection,
                                    String applicationName) throws IOException {

        CavLogger log = new CavLogger(listener, env);

        String baseUrl = connection.getBaseUrl();
        String apiToken = connection.getApiToken();
        boolean allowInsecureSSL = true;

        String resolvedAppName = expand(env, applicationName);
        if (resolvedAppName == null || resolvedAppName.trim().isEmpty()) {
            throw new AbortException("Application Name is required to start code coverage.");
        }
        resolvedAppName = resolvedAppName.trim();

        String pipelineId = envValue(env, "pipelineId", "PIPELINE_ID", "JOB_NAME");
        String pipelineRunId = envValue(env, "pipelineRunId", "PIPELINE_RUN_ID", "BUILD_NUMBER");

        log.info("========== Cavisson Start Code Coverage ==========");
        log.info("Service Base URL : " + baseUrl);
        log.info("Application Name : " + resolvedAppName);
        log.info("Pipeline Id      : " + pipelineId);
        log.info("Pipeline Run Id  : " + pipelineRunId);
        log.info("===================================================");

        String url = baseUrl.replaceAll("/+$", "") + API_PATH + "?appName=" + urlEncode(resolvedAppName)
                + "&pipelineId=" + urlEncode(pipelineId) + "&pipelineRunId=" + urlEncode(pipelineRunId);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("cavToken", apiToken);

        HttpUtil.HttpResult response = HttpUtil.getJson(url, headers, allowInsecureSSL);
        log.debug("codeCovStart response: " + response.body);
        JSONObject responseJson = new JSONObject(response.body);

        if (!isSuccess(responseJson)) {
            throw new AbortException("Failed to start code coverage: " + failureMessage(responseJson));
        }

        JSONObject data = responseJson.optJSONObject("data");
        String state = data == null ? "" : data.optString("state", "");
        if (!"RUNNING".equals(state)) {
            throw new AbortException("Coverage state is '" + state + "' instead of RUNNING.");
        }

        String uuid = data.optString("uuid", "");
        String respAppName = data.optString("applicationName", resolvedAppName);
        log.info("Code coverage started for '" + respAppName + "' (uuid=" + uuid + ")");

        Map<String, String> envVars = new LinkedHashMap<>();
        envVars.put("CAV_CODE_COVERAGE_UUID", uuid);
        CavissonEnvironmentPublisher.publish(run, envVars);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("state", state);
        result.put("uuid", uuid);
        result.put("applicationName", respAppName);
        result.put("pipelineId", pipelineId);
        result.put("pipelineRunId", pipelineRunId);
        return result;
    }

    static boolean isSuccess(JSONObject responseJson) {
        return responseJson.optBoolean("success", false);
    }

    static String failureMessage(JSONObject responseJson) {
        return responseJson.optString("message", "Unknown error received from API");
    }

    private static String expand(EnvVars env, String value) {
        return value == null ? null : env.expand(value);
    }

    private static String envValue(EnvVars env, String... names) {
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
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
