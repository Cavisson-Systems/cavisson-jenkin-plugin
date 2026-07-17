package com.cavisson.jenkins.security;

import com.cavisson.jenkins.scriptlog.CavLogger;

import com.cavisson.jenkins.http.HttpUtil;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Static / SonarQube scan stage. Java port of lib/codeAnalyzerRunner.js: exchange the
 * Cavisson API token for a short-lived SonarQube admin/user token pair, then hand off to the
 * bundled cav_scanner.sh (unchanged from the ADO extension) to do the actual build + scan.
 */
final class CodeAnalyzerRunner {

    private CodeAnalyzerRunner() {
    }

    static Map<String, Object> run(Launcher launcher,
                     TaskListener listener,
                     FilePath workspace,
                     EnvVars env,
                     String baseUrl,
                     String cavissonToken,
                     String projectKey,
                     String solutionPath,
                     String qualityGate,
                     String qualityGateTimeout) throws IOException, InterruptedException {

        String normalizedBaseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");

        if (normalizedBaseUrl.isEmpty()) {
            throw new IOException("Base URL is required for static scan.");
        }

        if (cavissonToken == null || cavissonToken.trim().isEmpty() || "none".equals(cavissonToken)) {
            throw new IOException("API Token is required in Cavisson Security Pipeline Service Connection when running static SonarQube scan.");
        }

        if (projectKey == null || projectKey.trim().isEmpty()) {
            throw new IOException("Static Scan Project Key is required.");
        }

        // listener.getLogger().println("========== Static Scan - Cavisson Code Analyzer ==========");
        // listener.getLogger().println("Type        : static");
        // listener.getLogger().println("Engine      : sonarqube");
        // listener.getLogger().println("Base URL    : " + normalizedBaseUrl);
        // listener.getLogger().println("Project Key : " + projectKey);
        // listener.getLogger().println("Target Path : " + (solutionPath == null || solutionPath.trim().isEmpty() ? "Full Repo Scan" : solutionPath));
        // listener.getLogger().println("Quality Gate: " + (qualityGate == null || qualityGate.trim().isEmpty() ? "Disabled" : qualityGate));
        // listener.getLogger().println("=========================================================");

        CavLogger.debug(listener, "========== SAST Scan - Cavisson Code Analyzer ==========");
        CavLogger.debug(listener, "Type        : SAST");
        CavLogger.debug(listener, "Engine      : sonarqube");
        CavLogger.debug(listener, "Base URL    : " + normalizedBaseUrl);
        CavLogger.debug(listener, "Project Key : " + projectKey);
        CavLogger.debug(listener, "Target Path : " + (solutionPath == null || solutionPath.trim().isEmpty() ? "Full Repo Scan" : solutionPath));
        CavLogger.debug(listener, "Quality Gate: " + (qualityGate == null || qualityGate.trim().isEmpty() ? "Disabled" : qualityGate));

        String tokenUrl = normalizedBaseUrl + "/DashboardServer/v2/web/common/getToken";

        JSONObject tokenRequest = new JSONObject();
        tokenRequest.put("token", cavissonToken);
        tokenRequest.put("validity_ms", 0);
        tokenRequest.put("generated_time", 0);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("cavToken", cavissonToken);

        // listener.getLogger().println("Requesting tokens from: " + tokenUrl);
        CavLogger.debug(listener, "Requesting tokens from: " + tokenUrl);
    
        HttpUtil.HttpResult tokenResponse = HttpUtil.postJson(tokenUrl, tokenRequest.toString(), headers, true);

        JSONObject responseData = new JSONObject(tokenResponse.body);

        if (!responseData.has("tokens")) {
            throw new IOException("Failed to get token: " + tokenResponse.body);
        }

        CavLogger.debug(listener, "Received token response from Cavisson. Token values are masked.");

        JSONObject parsedData = new JSONObject(responseData.getString("tokens"));

        String sonarToken = parsedData.optString("adminToken", null);
        String userToken = parsedData.optString("userToken", null);
        String userName = parsedData.optString("userName", null);

        if (sonarToken == null || userToken == null || userName == null
                || sonarToken.isEmpty() || userToken.isEmpty() || userName.isEmpty()) {
            throw new IOException("Invalid token response from Cavisson. adminToken/userToken/userName missing.");
        }

        String hostUrl = normalizedBaseUrl + "/cav-analysis/userName/" + userName + "/cavToken/" + cavissonToken;

        CavLogger.debug(listener, "========== CAV Scanner Task ==========");
        CavLogger.debug(listener, "Project Key    : " + projectKey);
        CavLogger.debug(listener, "Host URL       : " + CavLogger.maskSecrets(hostUrl));
        CavLogger.debug(listener, "Solution       : " + (solutionPath == null || solutionPath.trim().isEmpty() ? "Full Repo Scan" : solutionPath));
        CavLogger.debug(listener, "Cavisson Token : ****");
        CavLogger.debug(listener, "Sonar Token    : ****");
        CavLogger.debug(listener, "User Token     : ****");
        CavLogger.debug(listener, "User Name      : " + userName);
        CavLogger.debug(listener, "======================================");

        List<String> args = new ArrayList<>();
        Collections.addAll(args,
                "--projectKey", projectKey,
                "--hostUrl", hostUrl,
                "--userToken", userToken,
                "--userName", userName,
                "--token", cavissonToken,
                "--isSonarCloud", "false",
                "--sonarToken", sonarToken);

        if (solutionPath != null && !solutionPath.trim().isEmpty()) {
            Collections.addAll(args, "--solution", solutionPath.trim());
        }

        int exitCode = ScriptRunner.runBashScript(launcher, listener, workspace, env, "cav_scanner.sh", args, "Run Cavisson Code Analyzer");

        if (exitCode != 0) {
            String message = "cav_scanner.sh exited with code " + exitCode;
            CavLogger.error(listener, message);

            Map<String, Object> failureResult = new HashMap<>();
            failureResult.put("success", false);
            failureResult.put("reportUrl", ScanResultDefaults.reportUrl(normalizedBaseUrl, "SAST"));
            failureResult.put("scanId", "");
            failureResult.put("status", "FAILED");
            failureResult.put("message", message);

            ScanResultPrinter.print(listener, "SAST", "standalone", "SAST", false, "FAILED", "", failureResult.get("reportUrl").toString(), message);

            return failureResult;
        }


        FilePath reportTaskFile = workspace.child(".scannerwork/report-task.txt");
        String reportTaskContent = SecurityReportPublisher.readTextFile(reportTaskFile);

        CavLogger.debug(listener, "========== SAST Report Task ==========");
        if (reportTaskContent.isEmpty()) {
            CavLogger.debug(listener, "report-task.txt not found at " + reportTaskFile.getRemote() + ", skipping.");
        } else {
            CavLogger.debug(listener, reportTaskContent.trim());
        }

        Map<String, String> reportTaskProperties = parseReportTaskProperties(reportTaskContent);
        String ceTaskId = reportTaskProperties.get("ceTaskId");
        boolean qualityGateEnabled = qualityGate != null && !qualityGate.trim().isEmpty();

        if (ceTaskId != null && !ceTaskId.isEmpty() && qualityGateEnabled) {
            String pipelineId = env.getOrDefault("JOB_NAME", "");
            String pipelineRunId = env.getOrDefault("BUILD_NUMBER", "");

            JSONObject pipelineParams = new JSONObject();
            pipelineParams.put("sonar_url", hostUrl);
            pipelineParams.put("sonar_token", sonarToken);
            pipelineParams.put("project_key", reportTaskProperties.get("projectKey"));
            pipelineParams.put("ce_task_id", reportTaskProperties.get("ceTaskId"));
            pipelineParams.put("ce_task_url", reportTaskProperties.get("ceTaskUrl"));

            QualityGateEvaluator.evaluate(listener, normalizedBaseUrl, cavissonToken,
                    pipelineId, pipelineRunId, qualityGate, qualityGateTimeout, pipelineParams);
        }

        String reportUrl = ScanResultDefaults.reportUrl(normalizedBaseUrl, "SAST");
        String message = ScanResultDefaults.completionMessage("SAST");
        String scanId = ceTaskId == null ? "" : ceTaskId;

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("reportUrl", reportUrl);
        result.put("scanId", scanId);
        result.put("status", "COMPLETED");
        result.put("message", message);

        ScanResultPrinter.print(listener, "SAST", "standalone", "SAST", true, "COMPLETED", scanId, reportUrl, message);

        return result;
    }

    private static Map<String, String> parseReportTaskProperties(String content) {
        Map<String, String> properties = new HashMap<>();

        if (content == null || content.isEmpty()) {
            return properties;
        }

        for (String line : content.split("\\r?\\n")) {
            String trimmedLine = line.trim();
            int separatorIndex = trimmedLine.indexOf('=');

            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#") || separatorIndex < 0) {
                continue;
            }

            String key = trimmedLine.substring(0, separatorIndex).trim();
            String value = trimmedLine.substring(separatorIndex + 1).trim();
            properties.put(key, value);
        }

        return properties;
    }

}
