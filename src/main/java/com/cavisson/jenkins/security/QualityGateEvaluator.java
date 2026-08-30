package com.cavisson.jenkins.security;

import com.cavisson.jenkins.scriptlog.CavLogger;

import com.cavisson.jenkins.http.HttpUtil;

import hudson.AbortException;
import hudson.model.TaskListener;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared quality-gate evaluation REST call, used after Static, Container (SCA), and Dynamic
 * (DAST) scans whenever a quality gate name is provided. Static supplies SonarQube-specific
 * {@code pipelineParams} (sonar_url/sonar_token/project_key/ce_task_id/ce_task_url); Container
 * and Dynamic pass an empty {@code pipelineParams} object.
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
        String errorMessage = responseJson.optString("errorMessage", "");

        // failureType is only present for a real condition violation; a structural error (e.g. an
        // unknown gate name) reports status:"FAIL" with no failureType and an errorMessage instead.
        boolean conditionFailed = "FAIL".equalsIgnoreCase(status) && "FAIL".equalsIgnoreCase(failureType);
        
        CavLogger.info(listener, "Quality Gate: " + (status.isEmpty() ? "" : status));
        
        if (conditionFailed) {
            //CavLogger.info(listener, "Quality Gate: failed");
            throw new AbortException(errorMessage.isEmpty()
                    ? "Quality gate '" + qualityGateName + "' evaluation failed." : errorMessage);
        }else if ("FAIL".equalsIgnoreCase(status) && !errorMessage.isEmpty()) {
            CavLogger.info(listener, "Quality Gate Error: " + errorMessage);
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
