package com.cavisson.jenkins.stopcodecoverage;

import com.cavisson.jenkins.connection.CavissonConnection;
import com.cavisson.jenkins.env.CavissonDescriptionPublisher;
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
 * Calls the DashboardServer "Stop Code Coverage" REST API
 * ({@code GET /DashboardServer/v2/webreport/report/coverageReport/codeCovStop?appName=...}),
 * matching {@code task-end-coverage/index.js} in the Azure DevOps extension. A single
 * synchronous call, no polling - the response's boolean {@code success} field gates the
 * outcome, and {@code data.state} must be {@code "STOPPED"}. The report is not downloaded (the
 * ADO extension's own {@code downloadFile} helper is unused on this path too); only the
 * resolved report URL is logged/returned. {@code data.coverageXmlPath} (a path on the
 * DashboardServer's own filesystem, not under {@code baseUrl}) is published as
 * {@code CAV_CODE_COVERAGE_XML_PATH} for later steps that need it. Shared by both
 * {@link CavissonStopCodeCoverageBuilder} (Freestyle) and {@link CavissonStopCodeCoverageStep}
 * (Pipeline).
 */
final class CavissonStopCodeCoverageExecutor {

    private static final String API_PATH = "/DashboardServer/v2/webreport/report/coverageReport/codeCovStop";

    private CavissonStopCodeCoverageExecutor() {
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
            throw new AbortException("Application Name is required to stop code coverage.");
        }
        resolvedAppName = resolvedAppName.trim();

        log.info("========== Cavisson Stop Code Coverage ==========");
        log.info("Service Base URL : " + baseUrl);
        log.info("Application Name : " + resolvedAppName);
        log.info("==================================================");

        String url = baseUrl.replaceAll("/+$", "") + API_PATH + "?appName=" + urlEncode(resolvedAppName);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("cavToken", apiToken);

        HttpUtil.HttpResult response = HttpUtil.getJson(url, headers, allowInsecureSSL);
        log.debug("codeCovStop response: " + response.body);
        JSONObject responseJson = new JSONObject(response.body);

        if (!isSuccess(responseJson)) {
            throw new AbortException("Failed to stop code coverage: " + failureMessage(responseJson));
        }

        JSONObject data = responseJson.optJSONObject("data");
        String state = data == null ? "" : data.optString("state", "");
        if (!"STOPPED".equals(state)) {
            throw new AbortException("Coverage state is '" + state + "' instead of STOPPED.");
        }

        String coverageUuid = data.optString("coverageUuid", data.optString("uuid", ""));
        String respAppName = data.optString("appName", data.optString("applicationName", resolvedAppName));
        String reportPath = data.optString("reportUrl", "");
        String fullReportUrl = reportPath.isEmpty() ? "" : baseUrl.replaceAll("/+$", "") + reportPath;
        String coverageXmlPath = data.optString("coverageXmlPath", "");

        log.info("Code coverage stopped for '" + respAppName + "' (uuid=" + coverageUuid + ")");
        if (!fullReportUrl.isEmpty()) {
            log.info("Report URL: " + fullReportUrl);
        }
        if (!coverageXmlPath.isEmpty()) {
            log.info("Coverage XML Path: " + coverageXmlPath);
        }

        try {
            CavissonDescriptionPublisher.appendReportRow(run, env, fullReportUrl);
        } catch (IOException descriptionError) {
            log.error("Could not set build description with report link: " + descriptionError.getMessage());
        }

        Map<String, String> envVars = new LinkedHashMap<>();
        envVars.put("CAV_CODE_COVERAGE_UUID", coverageUuid);
        envVars.put("CAV_CODE_COVERAGE_APP_NAME", respAppName);
        envVars.put("CAV_CODE_COVERAGE_REPORT_URL", fullReportUrl);
        envVars.put("CAV_CODE_COVERAGE_XML_PATH", coverageXmlPath);
        CavissonEnvironmentPublisher.publish(run, envVars);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("state", state);
        result.put("coverageUuid", coverageUuid);
        result.put("applicationName", respAppName);
        result.put("reportUrl", fullReportUrl);
        result.put("coverageXmlPath", coverageXmlPath);
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

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
