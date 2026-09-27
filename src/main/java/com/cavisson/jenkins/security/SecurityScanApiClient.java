package com.cavisson.jenkins.security;

import com.cavisson.jenkins.scriptlog.CavLogger;

import com.cavisson.jenkins.http.HttpUtil;

import hudson.model.TaskListener;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Java port of lib/securityScanApi.js: notifies the Cavisson DashboardServer that a
 * Kubernetes-orchestrated Trivy or ZAP scan should run for the given target.
 */
final class SecurityScanApiClient {

    private static final int DEFAULT_POLL_INTERVAL_SECONDS = 15;
    private static final int DEFAULT_POLL_TIMEOUT_SECONDS = 7200; // 2 hours

    private SecurityScanApiClient() {
    }

//     static void callSecurityScanApi(TaskListener listener,
//                                  String securityScanApiBaseUrl,
//                                  String apiToken,
//                                  boolean allowInsecureSSL,
//                                  String stageName,
//                                  boolean scanDast,
//                                  boolean scanSca) throws IOException {

//     String cleanToken = apiToken == null ? "" : apiToken.trim();

//     String apiUrl = securityScanApiBaseUrl.replaceAll("/+$", "")
//             + "/tomcat/master/DashboardServer/v2/web/common/updateSecurityScanLastModified";

//     if (!cleanToken.isEmpty() && !"none".equalsIgnoreCase(cleanToken)) {
//         apiUrl = apiUrl + "?cavToken=" + java.net.URLEncoder.encode(cleanToken, "UTF-8");
//     }

//     Map<String, String> headers = new HashMap<>();
//     headers.put("Content-Type", "application/json");
//     headers.put("Accept", "application/json");

//     if (!cleanToken.isEmpty() && !"none".equalsIgnoreCase(cleanToken)) {
//         headers.put("cavtoken", cleanToken);
//         headers.put("cavToken", cleanToken);
//     }

//     JSONObject payload = new JSONObject();
//     payload.put("scanDast", scanDast);
//     payload.put("scanSca", scanSca);

//     listener.getLogger().println("========== " + stageName + " REST API Call ==========");
//     listener.getLogger().println("REST API Base URL : " + securityScanApiBaseUrl);
//     listener.getLogger().println("Final API URL     : " + apiUrl.replace(cleanToken, "***"));
//     listener.getLogger().println("Payload           : " + payload);
//     listener.getLogger().println("Token Found       : " + (!cleanToken.isEmpty() ? "yes" : "no"));
//     listener.getLogger().println("================================================");

//     HttpUtil.HttpResult response = HttpUtil.postJson(apiUrl, payload.toString(), headers, allowInsecureSSL);

//     listener.getLogger().println(stageName + " API Response:");
//     listener.getLogger().println(response.body);
// }

static Map<String, Object> callSecurityScanApi(TaskListener listener,
                                               String securityScanApiBaseUrl,
                                               String apiToken,
                                               boolean allowInsecureSSL,
                                               String stageName,
                                               boolean scanDast,
                                               boolean scanSca,
                                               String dataSourceName,
                                               String pipelineId,
                                               String pipelineRunId) throws IOException {

    String apiUrl = securityScanApiBaseUrl.replaceAll("/+$", "")
            + "/DashboardServer/v2/web/common/updateSecurityScanLastModified";

    String cleanToken = apiToken == null ? "" : apiToken.trim();

    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("Accept", "application/json");

    if (!cleanToken.isEmpty() && !"none".equalsIgnoreCase(cleanToken)) {
        headers.put("cavtoken", cleanToken);
    }

    String resolvedDataSourceName =
        dataSourceName == null ? "" : dataSourceName.trim();

    JSONObject payload = new JSONObject();
    payload.put("scanDast", scanDast);
    payload.put("scanSca", scanSca);
    payload.put("pipelineId", pipelineId);
    payload.put("pipelineRunId", pipelineRunId);

    if (!resolvedDataSourceName.isEmpty()) {
        payload.put("dataSourceName", resolvedDataSourceName);
    }
    CavLogger.debug(listener, "========== " + stageName + " REST API Call ==========");
    CavLogger.debug(listener, "REST API Base URL : " + securityScanApiBaseUrl);
    CavLogger.debug(listener, "Final API URL     : " + apiUrl);
    CavLogger.debug(listener, "Payload           : " + payload);
    CavLogger.debug(listener, "================================================");

    HttpUtil.HttpResult response = HttpUtil.postJson(apiUrl, payload.toString(), headers, allowInsecureSSL);

    String responseBody = response.body == null ? "" : response.body.trim();
    boolean success = response.statusCode >= 200 && response.statusCode < 300;

    String scanId = "";
    if (!responseBody.isEmpty()) {
        try {
            JSONObject responseJson = new JSONObject(responseBody);
            scanId = responseJson.optString("scanId", responseJson.optString("id", ""));

            JSONObject data = responseJson.optJSONObject("data");
            if (data != null && scanId.isEmpty()) {
                scanId = data.optString("scanId", data.optString("id", ""));
            }
        } catch (Exception ignored) {
            // Response is not JSON. Keep default values.
        }
    }

    String scanType = scanSca ? "SCA" : "DAST";
    String reportUrl = ScanResultDefaults.reportUrl(securityScanApiBaseUrl, scanType);
    String status = success ? "COMPLETED" : "FAILED";
    String message = success
            ? ScanResultDefaults.completionMessage(scanType)
            : (stageName + " REST API call failed. HTTP " + response.statusCode + " - " + responseBody);

    Map<String, Object> result = new HashMap<>();
    result.put("success", success);
    result.put("reportUrl", reportUrl);
    result.put("scanId", scanId);
    result.put("status", status);
    result.put("message", message);

    CavLogger.debug(listener, "Scan Type: " + scanType);
    CavLogger.debug(listener, "DataSource Name: " + resolvedDataSourceName);
    CavLogger.debug(listener, "Scan ID: " + scanId);
    CavLogger.debug(listener, "Report URL: " + reportUrl);
    CavLogger.debug(listener, "Status: " + status);

    ScanResultPrinter.print(
            listener,
            scanType,
            "kubernetes",
            stageName,
            success,
            status,
            scanId,
            reportUrl,
            message
    );

    return result;
}

/**
     * Polls the Cavisson DashboardServer's pipelineScanStatus endpoint for a Kubernetes-orchestrated
     * SCA/DAST scan (the run just triggered by callSecurityScanApi(...) above) until its
     * "overallStatus" reaches "COMPLETED", or until pollTimeoutSeconds elapses.
     *
     * Fully generic: the URL is built from securityScanApiBaseUrl + pipelineId/pipelineRunId, none of
     * which are hardcoded here - all come from the caller, exactly like callSecurityScanApi(...)
     * above. pollIntervalSeconds/pollTimeoutSeconds may be null (or <= 0) to fall back to
     * DEFAULT_POLL_INTERVAL_SECONDS/DEFAULT_POLL_TIMEOUT_SECONDS.
     *
     * A failed/unparseable individual poll attempt is logged and retried rather than aborting -
     * Kubernetes-mode SCA/DAST is already "fire-and-forget" (see CavSecurityPipelineBuilder#run's
     * javadoc), so a transient network hiccup while polling shouldn't fail the build either. The
     * only way this method throws is InterruptedException, propagated from Thread.sleep so the
     * caller's own interrupt handling (Jenkins step cancellation) keeps working.
     */
    static Map<String, Object> pollPipelineScanStatus(TaskListener listener,
                                                        String securityScanApiBaseUrl,
                                                        String apiToken,
                                                        boolean allowInsecureSSL,
                                                        String stageName,
                                                        String pipelineId,
                                                        String pipelineRunId,
                                                        Integer pollIntervalSeconds,
                                                        Integer pollTimeoutSeconds) throws InterruptedException {

        int intervalSeconds = (pollIntervalSeconds == null || pollIntervalSeconds <= 0)
                ? DEFAULT_POLL_INTERVAL_SECONDS : pollIntervalSeconds;
        int timeoutSeconds = (pollTimeoutSeconds == null || pollTimeoutSeconds <= 0)
                ? DEFAULT_POLL_TIMEOUT_SECONDS : pollTimeoutSeconds;

        String cleanToken = apiToken == null ? "" : apiToken.trim();
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        if (!cleanToken.isEmpty() && !"none".equalsIgnoreCase(cleanToken)) {
            headers.put("cavtoken", cleanToken);
        }

        String apiUrl = securityScanApiBaseUrl.replaceAll("/+$", "")
                + "/DashboardServer/v2/web/common/pipelineScanStatus"
                + "?pipelineId=" + urlEncode(pipelineId)
                + "&pipelineRunId=" + urlEncode(pipelineRunId);

        CavLogger.debug(listener, "========== " + stageName + " Pipeline Scan Status Poll ==========");
        CavLogger.debug(listener, "Final API URL     : " + apiUrl);
        CavLogger.debug(listener, "Poll interval (s) : " + intervalSeconds);
        CavLogger.debug(listener, "Poll timeout (s)  : " + timeoutSeconds);
        CavLogger.debug(listener, "===============================================================");

        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        String overallStatus = "";
        JSONObject lastResponseJson = null;

        while (true) {
            try {
                HttpUtil.HttpResult response = HttpUtil.getJson(apiUrl, headers, allowInsecureSSL);
                String responseBody = response.body == null ? "" : response.body.trim();

                CavLogger.debug(listener, stageName + " pipelineScanStatus response: " + responseBody);

                if (!responseBody.isEmpty()) {
                    lastResponseJson = new JSONObject(responseBody);
                    overallStatus = lastResponseJson.optString("overallStatus", "");
                }
            } catch (Exception pollAttemptFailure) {
                CavLogger.error(listener, stageName
                        + " pipelineScanStatus poll attempt failed (will retry until timeout): "
                        + pollAttemptFailure.getMessage());
            }

            CavLogger.info(listener, stageName + " status: "
                    + (overallStatus.isEmpty() ? "(unknown)" : overallStatus));

            if ("COMPLETED".equalsIgnoreCase(overallStatus)) {
                break;
            }

            if (System.currentTimeMillis() >= deadline) {
                CavLogger.error(listener, stageName + " pipelineScanStatus polling timed out after "
                        + timeoutSeconds + "s; last overallStatus="
                        + (overallStatus.isEmpty() ? "(unknown)" : overallStatus));
                break;
            }

            Thread.sleep(intervalSeconds * 1000L);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("overallStatus", overallStatus);
        result.put("completed", "COMPLETED".equalsIgnoreCase(overallStatus));
        if (lastResponseJson != null) {
            result.put("raw", lastResponseJson);
        }
        return result;
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return value == null ? "" : value;
        }
    }
}
