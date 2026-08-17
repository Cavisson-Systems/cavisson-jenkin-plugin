package com.cavisson.jenkins.qualitygate;

import com.cavisson.jenkins.scriptlog.CavLogger;

import com.cavisson.jenkins.http.HttpUtil;

import hudson.AbortException;
import hudson.model.TaskListener;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Quality-gate evaluation REST call. When a Static/SonarQube scan ran earlier in the same
 * workspace, the caller supplies SonarQube-specific {@code pipelineParams}
 * (sonar_url/sonar_token/project_key/ce_task_id/ce_task_url, see {@link ReportTaskReader} and
 * {@link SonarTokenExchange}); otherwise it passes an empty {@code pipelineParams} object.
 */
final class QualityGateEvaluator {

    private static final int DEFAULT_QUALITY_GATE_TIMEOUT_SECONDS = 120;

    private QualityGateEvaluator() {
    }

    static void evaluate(TaskListener listener,
                          String baseUrl,
                          String apiToken,
                          String pipelineId,
                          String pipelineRunId,
                          String qualityGateName,
                          String qualityGateTimeout,
                          JSONObject pipelineParams) throws IOException {

        String normalizedBaseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        String qualityGateUrl = normalizedBaseUrl
                + "/tomcat/master/DashboardServer/v2/qualitygate/qualitygate/evaluate"
                + "?cavToken=" + apiToken;

        JSONObject payload = new JSONObject();
        payload.put("pipelineId", pipelineId);
        payload.put("pipelineRunId", pipelineRunId);
        payload.put("qualityGateName", qualityGateName);
        payload.put("qualityGateTimeout", parseTimeoutSeconds(qualityGateTimeout));
        payload.put("pipelineParams", pipelineParams == null ? new JSONObject() : pipelineParams);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        String safeQualityGateUrl = qualityGateUrl.replace(apiToken, "****");

        CavLogger.debug(listener, "========== Quality Gate Evaluation ==========");
        CavLogger.debug(listener, "URL: " + safeQualityGateUrl);

        HttpUtil.HttpResult response = HttpUtil.postJson(qualityGateUrl, payload.toString(), headers, true);

        CavLogger.debug(listener, "Quality Gate Evaluation Response:");
        CavLogger.debug(listener, response.body);
        CavLogger.debug(listener, "==============================================");

        JSONObject responseJson;
        try {
            responseJson = new JSONObject(response.body);
        } catch (Exception notJson) {
            return;
        }

        String status = responseJson.optString("status", "");
        String failureType = responseJson.optString("failureType", "");
        boolean failed = "FAIL".equalsIgnoreCase(status) && "FAIL".equalsIgnoreCase(failureType);

        CavLogger.info(listener, "Quality Gate: " + (failed ? "failed" : "success"));

        if (failed) {
            String errorMessage = responseJson.optString("errorMessage",
                    "Quality gate '" + qualityGateName + "' evaluation failed.");
            throw new AbortException(errorMessage);
        }
    }

    private static int parseTimeoutSeconds(String qualityGateTimeout) {
        if (qualityGateTimeout == null || qualityGateTimeout.trim().isEmpty()) {
            return DEFAULT_QUALITY_GATE_TIMEOUT_SECONDS;
        }

        try {
            return Integer.parseInt(qualityGateTimeout.trim());
        } catch (NumberFormatException notANumber) {
            return DEFAULT_QUALITY_GATE_TIMEOUT_SECONDS;
        }
    }
}
