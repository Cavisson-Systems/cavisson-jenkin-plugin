package com.cavisson.jenkins.security;

import com.cavisson.jenkins.scriptlog.CavLogger;

import com.cavisson.jenkins.http.HttpUtil;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
// import hudson.tasks.ArtifactArchiver;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
// import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Java port of the report-handling half of index.js: saveSecurityReportToMongo() and
 * collectAndPublishSecurityReports(). Reports are collected under
 * workspace/security-reports/&lt;project&gt;/&lt;build&gt;/... and published through
 * {@link ArtifactArchiver}, which is the Jenkins-native equivalent of ADO's
 * ##vso[artifact.upload] command used in the original extension.
 */
final class SecurityReportPublisher {

    // private static final String REPORTS_ROOT_DIR_NAME = "security-reports";

    private SecurityReportPublisher() {
    }

    // private static String sanitizePathPart(String value) {
    //     String source = (value == null || value.trim().isEmpty()) ? "unknown" : value.trim();
    //     return source.replaceAll("[^a-zA-Z0-9._-]", "_");
    // }

    private static FilePath getLatestFileByExtension(FilePath dir, String extension, String prefix) throws IOException, InterruptedException {
        if (dir == null || !dir.exists()) {
            return null;
        }

        FilePath[] files = dir.list("**/*" + extension);
        FilePath latest = null;
        long latestModified = -1;

        for (FilePath file : files) {
            String name = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (!name.endsWith(extension.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            if (prefix != null && !prefix.isEmpty() && !name.startsWith(prefix.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }

            long modified = file.lastModified();
            if (modified > latestModified) {
                latestModified = modified;
                latest = file;
            }
        }

        return latest;
    }

    private static FilePath generateTrivyHtmlReport(Launcher launcher,
                                                      TaskListener listener,
                                                      FilePath workspace,
                                                      EnvVars env,
                                                      FilePath trivyJsonFile) throws IOException, InterruptedException {
        String jsonPath = trivyJsonFile.getRemote();
        String htmlPath = jsonPath.replaceAll("(?i)\\.json$", ".html");

        int exitCode = ScriptRunner.runPythonConverter(launcher, listener, workspace, env, jsonPath, htmlPath);

        FilePath htmlFile = trivyJsonFile.getParent().child(trivyJsonFile.getName().replaceAll("(?i)\\.json$", ".html"));

        if (exitCode != 0 || !htmlFile.exists()) {
            throw new IOException("Trivy HTML report was not generated: " + htmlPath);
        }

        return htmlFile;
    }

    private static String getSecurityReportModuleName(String toolName) throws IOException {
        if ("trivy".equals(toolName)) {
            return "vulnerabilities_reports_nonK8s";
        }
        if ("zap".equals(toolName)) {
            return "dynamic_report_nonK8s";
        }
        throw new IOException("Unsupported security report tool: " + toolName);
    }

    static String readTextFile(FilePath file) throws IOException, InterruptedException {
        if (file == null || !file.exists()) {
            return "";
        }
        return file.readToString();
    }

//     static void saveSecurityReportToMongo(Launcher launcher,
//                                            TaskListener listener,
//                                            FilePath workspace,
//                                            EnvVars env,
//                                            Run<?, ?> run,
//                                            String endpointUrl,
//                                            String apiToken,
//                                            boolean allowInsecureSSL,
//                                            String toolName,
//                                            FilePath reportDir,
//                                            String scanMode,
//                                            String target) throws IOException, InterruptedException {

//         if (reportDir == null || !reportDir.exists()) {
//             listener.getLogger().println(toolName + " report directory not found, Mongo save skipped: " + reportDir);
//             return;
//         }

//         String reportUuid = UUID.randomUUID().toString();
//         String moduleName = getSecurityReportModuleName(toolName);

//         FilePath htmlReportFile = null;
//         FilePath jsonReportFile = null;

//         if ("trivy".equals(toolName)) {
//             jsonReportFile = getLatestFileByExtension(reportDir, ".json", "trivy");
//             if (jsonReportFile == null) {
//                 jsonReportFile = getLatestFileByExtension(reportDir, ".json", null);
//             }
//             if (jsonReportFile != null) {
//                 htmlReportFile = generateTrivyHtmlReport(launcher, listener, workspace, env, jsonReportFile);
//             }
//         }

//         if ("zap".equals(toolName)) {
//             htmlReportFile = getLatestFileByExtension(reportDir, ".html", "zap");
//             jsonReportFile = getLatestFileByExtension(reportDir, ".json", "zap");
//         }

//         listener.getLogger().println("========== Security Report Mongo Save Selection ==========");
//         listener.getLogger().println("Tool        : " + toolName);
//         listener.getLogger().println("Module Name : " + moduleName);
//         listener.getLogger().println("Mode        : " + scanMode);
//         listener.getLogger().println("Target      : " + target);
//         listener.getLogger().println("Report Dir  : " + reportDir);
//         listener.getLogger().println("HTML Report : " + (htmlReportFile != null ? htmlReportFile.getRemote() : "not found"));
//         listener.getLogger().println("JSON Report : " + (jsonReportFile != null ? jsonReportFile.getRemote() : "not found"));
//         listener.getLogger().println("=========================================================");

//         if (htmlReportFile == null && jsonReportFile == null) {
//             listener.getLogger().println(toolName + " HTML/JSON report files not found, Mongo save skipped.");
//             return;
//         }

//         String htmlContent = readTextFile(htmlReportFile);
//         String jsonContent = readTextFile(jsonReportFile);

//         String now = isoTimestamp();

//         JSONObject document = new JSONObject();
//         document.put("uuid", reportUuid);
//         document.put("reportId", reportUuid);
//         document.put("reportName", toolName + "-" + scanMode + "-" + reportUuid);
//         document.put("reportType", moduleName);
//         document.put("toolName", toolName);
//         document.put("scanType", "trivy".equals(toolName) ? "container" : "dynamic");
//         document.put("scanMode", scanMode);
//         document.put("target", target);

//         JSONObject htmlReport = new JSONObject();
//         htmlReport.put("fileName", htmlReportFile != null ? htmlReportFile.getName() : "");
//         htmlReport.put("filePath", htmlReportFile != null ? htmlReportFile.getRemote() : "");
//         htmlReport.put("contentType", "text/html");
//         htmlReport.put("content", htmlContent);
//         document.put("htmlReport", htmlReport);

//         JSONObject jsonReport = new JSONObject();
//         jsonReport.put("fileName", jsonReportFile != null ? jsonReportFile.getName() : "");
//         jsonReport.put("filePath", jsonReportFile != null ? jsonReportFile.getRemote() : "");
//         jsonReport.put("contentType", "application/json");
//         jsonReport.put("content", jsonContent.isEmpty() ? JSONObject.NULL : safeJson(jsonContent));
//         document.put("jsonReport", jsonReport);

//         // document.put("pipeline", buildPipelineInfo(run, env));
//         String pipelineId = env.getOrDefault("JOB_NAME", "");
// String pipelineRunId = env.getOrDefault("BUILD_NUMBER", String.valueOf(run.getNumber()));

// document.put("pipelineId", pipelineId);
// document.put("pipelineRunId", pipelineRunId);
// document.put("pipeline", buildPipelineInfo(run, env));

//         document.put("status", "COMPLETED");
//         document.put("createdAt", now);
//         document.put("updatedAt", now);

//         String tokenQuery = (apiToken == null || apiToken.isEmpty()) ? "" : ("?cavToken=" + urlEncode(apiToken));
//         String mongoUrl = endpointUrl.replaceAll("/+$", "")
//                 + "/tomcat/master/DashboardServer/v2/config/module/" + moduleName + "/objects" + tokenQuery;

//         Map<String, String> headers = new HashMap<>();
//         headers.put("Content-Type", "application/json");
//         headers.put("Accept", "application/json");

//         if (apiToken != null && !apiToken.isEmpty() && !"none".equals(apiToken)) {
//             headers.put("Authorization", "Bearer " + apiToken);
//         }

//         String safeMongoUrl = (apiToken == null || apiToken.isEmpty())
//                 ? mongoUrl
//                 : mongoUrl.replace(urlEncode(apiToken), "***");

//         listener.getLogger().println("========== Save Security Report To Mongo ==========");
//         listener.getLogger().println("Mongo URL   : " + safeMongoUrl);
//         listener.getLogger().println("Module Name : " + moduleName);
//         listener.getLogger().println("Report UUID : " + reportUuid);
//         listener.getLogger().println("Token Found : " + (apiToken != null && !apiToken.isEmpty() ? "yes" : "no"));
//         listener.getLogger().println("==================================================");

//         HttpUtil.HttpResult response = HttpUtil.postJson(mongoUrl, document.toString(), headers, allowInsecureSSL);

//         listener.getLogger().println("Security report saved to Mongo successfully.");
//         listener.getLogger().println(response.body);

//         String scanTypeDisplay = "trivy".equals(toolName) ? "SCA" : "DAST";
//         String reportUrl = safeMongoUrl;
//         String scanId = reportUuid;
//         String status = "COMPLETED";

//         ScanResultPrinter.print(
//                 listener,
//                 scanTypeDisplay,
//                 "standalone",
//                 toolName,
//                 true,
//                 status,
//                 scanId,
//                 reportUrl,
//                 "Security report saved to Mongo successfully."
//         );

//     }

    static Map<String, Object> saveSecurityReportToMongo(Launcher launcher,
                                                     TaskListener listener,
                                                     FilePath workspace,
                                                     EnvVars env,
                                                     Run<?, ?> run,
                                                     String endpointUrl,
                                                     String apiToken,
                                                     boolean allowInsecureSSL,
                                                     String toolName,
                                                     FilePath reportDir,
                                                     String scanMode,
                                                     String target,
                                                     String services) throws IOException, InterruptedException {

    String scanTypeDisplay = "trivy".equalsIgnoreCase(toolName) ? "SCA" : "DAST";
    String toolDisplay = scanTypeDisplay;

    if (reportDir == null || !reportDir.exists()) {
        String message = toolDisplay + " report directory not found, Mongo save skipped: " + reportDir;

        listener.getLogger().println(message);

        // Map<String, Object> result = new HashMap<>();
        // result.put("success", false);
        // result.put("reportUrl", "");
        // result.put("scanId", "");
        // result.put("status", "SKIPPED");
        // result.put("message", message);

        // Map<String, Object> result = new HashMap<>();
        // result.put("success", true);
        // result.put("reportUrl", reportUrl);
        // result.put("scanId", scanId);
        // result.put("status", "COMPLETED");
        // result.put("message", "Cavisson security scan initiated, scanning will take 5-10 minutes to generate the report.");

        // SecurityBuildAction.addFromResult(result, run);

        // return result;

        // ScanResultPrinter.print(
        //         listener,
        //         scanTypeDisplay,
        //         "standalone",
        //         toolDisplay,
        //         false,
        //         "SKIPPED",
        //         "",
        //         "",
        //         message
        // );

        String reportUrl = ScanResultDefaults.reportUrl(endpointUrl, scanTypeDisplay);

        Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("reportUrl", reportUrl);
            result.put("scanId", "");
            result.put("status", "SKIPPED");
            result.put("message", message);

            // SecurityBuildAction.addFromResult(result, run);

            ScanResultPrinter.print(
                    listener,
                    scanTypeDisplay,
                    "standalone",
                    toolDisplay,
                    false,
                    "SKIPPED",
                    "",
                    reportUrl,
                    message
            );

        return result;
    }

    String reportUuid = UUID.randomUUID().toString();
    String moduleName = getSecurityReportModuleName(toolName);

    FilePath htmlReportFile = null;
    FilePath jsonReportFile = null;

    if ("trivy".equalsIgnoreCase(toolName)) {
        jsonReportFile = getLatestFileByExtension(reportDir, ".json", "trivy");
        if (jsonReportFile == null) {
            jsonReportFile = getLatestFileByExtension(reportDir, ".json", null);
        }
        if (jsonReportFile != null) {
            htmlReportFile = generateTrivyHtmlReport(launcher, listener, workspace, env, jsonReportFile);
        }
    }

    if ("zap".equalsIgnoreCase(toolName)) {
    htmlReportFile = getLatestFileByExtension(reportDir, ".html", "zap");
    if (htmlReportFile == null) {
        htmlReportFile = getLatestFileByExtension(reportDir, ".html", null);
    }

    jsonReportFile = getLatestFileByExtension(reportDir, ".json", "zap");
    if (jsonReportFile == null) {
        jsonReportFile = getLatestFileByExtension(reportDir, ".json", null);
    }
}

    CavLogger.debug(listener, "========== Security Report Mongo Save Selection ==========");
    CavLogger.debug(listener, "Tool        : " + toolDisplay);
    CavLogger.debug(listener, "Module Name : " + moduleName);
    CavLogger.debug(listener, "Mode        : " + scanMode);
    CavLogger.debug(listener, "Target      : " + target);
    CavLogger.debug(listener, "Report Dir  : " + reportDir);
    CavLogger.debug(listener, "HTML Report : " + (htmlReportFile != null ? htmlReportFile.getRemote() : "not found"));
    CavLogger.debug(listener, "JSON Report : " + (jsonReportFile != null ? jsonReportFile.getRemote() : "not found"));
    CavLogger.debug(listener, "=========================================================");

    if (htmlReportFile == null && jsonReportFile == null) {
        String message = toolDisplay + " HTML/JSON report files not found, Mongo save skipped.";

        CavLogger.info(listener, message);

        String reportUrl = ScanResultDefaults.reportUrl(endpointUrl, scanTypeDisplay);

        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("reportUrl", reportUrl);
        result.put("scanId", reportUuid);
        result.put("status", "SKIPPED");
        result.put("message", message);

        ScanResultPrinter.print(
                listener,
                scanTypeDisplay,
                "standalone",
                toolDisplay,
                false,
                "SKIPPED",
                reportUuid,
                reportUrl,
                message
        );

        return result;
    }

    String htmlContent = readTextFile(htmlReportFile);
    String jsonContent = readTextFile(jsonReportFile);

    String now = isoTimestamp();

    JSONObject document = new JSONObject();
    document.put("uuid", reportUuid);
    document.put("reportId", reportUuid);
    document.put("reportName", scanTypeDisplay + "-" + scanMode + "-" + reportUuid);
    document.put("reportType", moduleName);
    document.put("toolName", toolDisplay);
    document.put("scanType", scanTypeDisplay);
    document.put("scanMode", scanMode);
    document.put("target", target);
    if (services != null && !services.trim().isEmpty()) {
        document.put("services", services.trim());
    }

    JSONObject htmlReport = new JSONObject();
    htmlReport.put("fileName", htmlReportFile != null ? htmlReportFile.getName() : "");
    htmlReport.put("filePath", htmlReportFile != null ? htmlReportFile.getRemote() : "");
    htmlReport.put("contentType", "text/html");
    htmlReport.put("content", htmlContent);
    document.put("htmlReport", htmlReport);

    JSONObject jsonReport = new JSONObject();
    jsonReport.put("fileName", jsonReportFile != null ? jsonReportFile.getName() : "");
    jsonReport.put("filePath", jsonReportFile != null ? jsonReportFile.getRemote() : "");
    jsonReport.put("contentType", "application/json");
    jsonReport.put("content", jsonContent.isEmpty() ? JSONObject.NULL : safeJson(jsonContent));
    document.put("jsonReport", jsonReport);

    String pipelineId = env.getOrDefault("JOB_NAME", "");
    String pipelineRunId = env.getOrDefault("BUILD_NUMBER", String.valueOf(run.getNumber()));

    document.put("pipelineId", pipelineId);
    document.put("pipelineRunId", pipelineRunId);
    document.put("pipeline", buildPipelineInfo(run, env));

    document.put("status", "COMPLETED");
    document.put("createdAt", now);
    document.put("updatedAt", now);

    String tokenQuery = (apiToken == null || apiToken.isEmpty()) ? "" : ("?cavToken=" + urlEncode(apiToken));
    String mongoUrl = endpointUrl.replaceAll("/+$", "")
            + "/tomcat/master/DashboardServer/v2/config/module/" + moduleName + "/objects" + tokenQuery;

    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("Accept", "application/json");

    if (apiToken != null && !apiToken.trim().isEmpty() && !"none".equalsIgnoreCase(apiToken.trim())) {
        headers.put("cavtoken", apiToken.trim());
    }

    String safeMongoUrl = (apiToken == null || apiToken.isEmpty())
            ? mongoUrl
            : mongoUrl.replace(urlEncode(apiToken), "***");

    CavLogger.debug(listener, "========== Save Security Report To Mongo ==========");
    CavLogger.debug(listener, "Mongo URL   : " + safeMongoUrl);
    CavLogger.debug(listener, "Module Name : " + moduleName);
    CavLogger.debug(listener, "Report UUID : " + reportUuid);
    CavLogger.debug(listener, "Token Found : " + (apiToken != null && !apiToken.isEmpty() ? "yes" : "no"));
    CavLogger.debug(listener, "==================================================");

    HttpUtil.HttpResult response = HttpUtil.postJson(mongoUrl, document.toString(), headers, allowInsecureSSL);

    CavLogger.debug(listener, "Mongo response code : " + response.statusCode);
    CavLogger.debug(listener, "Mongo response body : " + response.body);

    if (response.statusCode < 200 || response.statusCode >= 300) {
        throw new IOException("Security report Mongo save failed. HTTP "
                + response.statusCode + " - " + response.body);
    }
    CavLogger.debug(listener, "Security report saved to Mongo successfully.");

    String reportUrl = ScanResultDefaults.reportUrl(endpointUrl, scanTypeDisplay);
    String scanId = reportUuid;
    String status = "COMPLETED";
    String message = ScanResultDefaults.completionMessage(scanTypeDisplay);

    Map<String, Object> result = new HashMap<>();
    result.put("success", true);
    result.put("reportUrl", reportUrl);
    result.put("scanId", scanId);
    result.put("status", status);
    result.put("message", message);

    ScanResultPrinter.print(
            listener,
            scanTypeDisplay,
            "standalone",
            toolDisplay,
            true,
            status,
            scanId,
            reportUrl,
            message
    );

    return result;
}    

    private static Object safeJson(String jsonContent) {
        try {
            return new JSONObject(jsonContent);
        } catch (Exception notAnObject) {
            return jsonContent;
        }
    }

    static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String isoTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static JSONObject buildPipelineInfo(Run<?, ?> run, EnvVars env) {
        JSONObject pipeline = new JSONObject();
        pipeline.put("pipelineName", env.getOrDefault("JOB_NAME", ""));
        pipeline.put("pipelineRunId", String.valueOf(run.getNumber()));
        pipeline.put("pipelineRunNumber", env.getOrDefault("BUILD_NUMBER", ""));
        pipeline.put("buildId", env.getOrDefault("BUILD_ID", ""));
        pipeline.put("buildNumber", env.getOrDefault("BUILD_NUMBER", ""));
        pipeline.put("buildUrl", env.getOrDefault("BUILD_URL", ""));
        pipeline.put("jobUrl", env.getOrDefault("JOB_URL", ""));
        pipeline.put("nodeName", env.getOrDefault("NODE_NAME", ""));
        pipeline.put("sourceBranch", env.getOrDefault("GIT_BRANCH", ""));
        pipeline.put("sourceVersion", env.getOrDefault("GIT_COMMIT", ""));
        pipeline.put("repositoryUri", env.getOrDefault("GIT_URL", ""));
        pipeline.put("jenkinsUrl", env.getOrDefault("JENKINS_URL", ""));
        return pipeline;
    }

    static final class ReportSummary {
        final FilePath reportRoot;
        final int fileCount;

        ReportSummary(FilePath reportRoot, int fileCount) {
            this.reportRoot = reportRoot;
            this.fileCount = fileCount;
        }
    }

    // static ReportSummary collectAndPublishSecurityReports(Run<?, ?> run,
    //                                                         Launcher launcher,
    //                                                         TaskListener listener,
    //                                                         FilePath workspace,
    //                                                         EnvVars env,
    //                                                         String projectName,
    //                                                         FilePath trivyReportDir,
    //                                                         FilePath zapReportDir) throws IOException, InterruptedException {

    //     listener.getLogger().println("========== Collect and Publish Security Reports ==========");

    //     String buildNumber = env.getOrDefault("BUILD_NUMBER", "local-build");
    //     String buildId = env.getOrDefault("BUILD_ID", buildNumber);

    //     String safeProject = sanitizePathPart(projectName);
    //     String safeBuild = sanitizePathPart(buildNumber);
    //     String safeBuildId = sanitizePathPart(buildId);
    //     String reportRunId = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'").format(new Date());

    //     FilePath reportRoot = workspace.child(REPORTS_ROOT_DIR_NAME)
    //             .child(safeProject)
    //             .child(safeBuild)
    //             .child(safeBuildId)
    //             .child(reportRunId);

    //     FilePath sonarDestinationDir = reportRoot.child("SAST");
    //     FilePath trivyDestinationDir = reportRoot.child("SCA");
    //     FilePath zapDestinationDir = reportRoot.child("DAST");

    //     listener.getLogger().println("Workspace               : " + workspace.getRemote());
    //     listener.getLogger().println("Final Report Root       : " + reportRoot.getRemote());

    //     int sonarCopied = collectSonarQubeFiles(workspace, sonarDestinationDir);

    //     int trivyCopied = 0;
    //     if (trivyReportDir != null && trivyReportDir.exists()) {
    //         trivyCopied = trivyReportDir.copyRecursiveTo("**/*", trivyDestinationDir);
    //     } else {
    //         listener.getLogger().println("SCA report directory not available, skipping SCA report collection.");
    //     }

    //     int zapCopied = 0;
    //     if (zapReportDir != null && zapReportDir.exists()) {
    //         zapCopied = zapReportDir.copyRecursiveTo("**/*", zapDestinationDir);
    //     } else {
    //         listener.getLogger().println("DAST report directory not available, skipping DAST report collection.");
    //     }

    //     JSONObject summary = new JSONObject();
    //     summary.put("projectName", projectName);
    //     summary.put("buildNumber", buildNumber);
    //     summary.put("buildId", buildId);
    //     summary.put("generatedAt", isoTimestamp());
    //     summary.put("reportRoot", reportRoot.getRemote());
    //     summary.put("sonarQubeCopiedFiles", sonarCopied);
    //     summary.put("trivyCopiedFiles", trivyCopied);
    //     summary.put("zapCopiedFiles", zapCopied);

    //     int totalCopied = sonarCopied + trivyCopied + zapCopied;

    //     if (totalCopied == 0) {
    //         listener.getLogger().println("No report files were collected. Artifact publishing skipped.");
    //         listener.getLogger().println("=========================================================");
    //         return new ReportSummary(reportRoot, 0);
    //     }

    //     reportRoot.child("security-report-summary.json").write(summary.toString(2), "UTF-8");

    //     // String relativeIncludes = REPORTS_ROOT_DIR_NAME + "/" + safeProject + "/" + safeBuild + "/" + safeBuildId + "/" + reportRunId + "/**";

    //     // ArtifactArchiver archiver = new ArtifactArchiver(relativeIncludes);
    //     // archiver.setAllowEmptyArchive(true);
    //     // archiver.setOnlyIfSuccessful(false);
    //     // archiver.perform(run, workspace, env, launcher, listener);

    //     // listener.getLogger().println("Reports published as Jenkins build artifacts under: " + relativeIncludes);
    //     // listener.getLogger().println("=========================================================");

    //     // return new ReportSummary(reportRoot, totalCopied);
    // }
    static ReportSummary collectAndPublishSecurityReports(Run<?, ?> run,
                                                        Launcher launcher,
                                                        TaskListener listener,
                                                        FilePath workspace,
                                                        EnvVars env,
                                                        String projectName,
                                                        FilePath trivyReportDir,
                                                        FilePath zapReportDir) throws IOException, InterruptedException {

    // CavLogger.info(listener, "Security report collection and artifact publishing is disabled.");
    // CavLogger.info(listener, "Reports will not be saved as Jenkins build artifacts.");
    // CavLogger.info(listener, "Reports will not be shown under Jenkins Last Successful Artifacts.");

    return new ReportSummary(workspace, 0);
}

}
