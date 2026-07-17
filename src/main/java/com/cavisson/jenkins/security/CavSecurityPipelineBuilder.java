package com.cavisson.jenkins.security;

import com.cavisson.jenkins.connection.CavissonConnection;
import com.cavisson.jenkins.connection.CavissonConnectionResolver;
import com.cavisson.jenkins.connection.CavServiceConnection;
import com.cavisson.jenkins.env.CavissonDescriptionPublisher;
import com.cavisson.jenkins.scriptlog.CavLogger;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.json.JSONObject;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

/**
 * Jenkins equivalent of the ADO extension's "CavSecurityPipelineCP100" task
 * (index.js).
 * Runs exactly one of three Cavisson security scans per build step: Static
 * (SonarQube via
 * the bundled Cavisson Code Analyzer), Container (Trivy), or Dynamic (ZAP).
 */
public class CavSecurityPipelineBuilder extends Builder implements SimpleBuildStep, java.io.Serializable {

    private static final long serialVersionUID = 1L;

    private String cavScanServiceConnection = "";
    private final String scanType;

    private String connectionMode = "serviceConnection";
    private String baseUrl = "";
    private String apiTokenCredentialId = "";

    private String project = "";
    private String targetPath = "";
    private String qualityGate = "";
    private String qualityGateTimeout = "";

    private String runMode = "standalone";
    // private String containerRunMode = "standalone";
    private String trivyMode = "image";
    private String trivyTarget = "eshoponweb:latest";
    private String trivyReportDir = "";

    // Production-friendly aliases used by Jenkins Pipeline users.
    // Old trivy* names are still supported for backward compatibility.
    private String scaMode = "";
    private String scaTarget = "";
    private String scaReportDir = "";

    // Artifact Keeper / Docker registry image inputs.
    // If scaTarget is blank, the plugin composes the image as:
    // <scaRegistryUrl>/<scaRepository>/<scaImageName>:<scaImageTag>
    private String scaRegistryUrl = "";
    private String scaRepository = "";
    private String scaImageName = "";
    private String scaImageTag = "latest";
    private String containerClusterName = "";
    private String containerNamespace = "default";
    private String containerTargetUrl = "";

    // private String dynamicRunMode = "standalone";
    private String zapMode = "baseline";
    private String zapTarget = "";
    private String zapApiFormat = "openapi";
    private String zapReportDir = "";

    // Production-friendly aliases used by Jenkins Pipeline users.
    // Old zap* names are still supported for backward compatibility.
    private String dastMode = "";
    private String dastTarget = "";
    private String dastApiFormat = "";
    private String dastReportDir = "";
    private String dynamicClusterName = "";
    private String dynamicNamespace = "default";
    private String targetUrl = "";
    private String dataSourceName = "Ticket";

    @DataBoundConstructor
    public CavSecurityPipelineBuilder(@Nonnull String scanType) {
        this.scanType = normalizeScanType(scanType);
    }

    public String getCavScanServiceConnection() {
        return cavScanServiceConnection;
    }

    @DataBoundSetter
    public void setCavScanServiceConnection(String cavScanServiceConnection) {
        this.cavScanServiceConnection = cavScanServiceConnection;
    }

    public String getScanType() {
        return scanType;
    }

    public String getConnectionMode() {
        return connectionMode;
    }

    @DataBoundSetter
    public void setConnectionMode(String connectionMode) {
        this.connectionMode = "direct".equals(connectionMode) ? "direct" : "serviceConnection";
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    @DataBoundSetter
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiTokenCredentialId() {
        return apiTokenCredentialId;
    }

    @DataBoundSetter
    public void setApiTokenCredentialId(String apiTokenCredentialId) {
        this.apiTokenCredentialId = apiTokenCredentialId;
    }

    public String getProject() {
        return project;
    }

    @DataBoundSetter
    public void setProject(String project) {
        this.project = project;
    }

    public String getTargetPath() {
        return targetPath;
    }

    @DataBoundSetter
    public void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    public String getQualityGate() {
        return qualityGate;
    }

    @DataBoundSetter
    public void setQualityGate(String qualityGate) {
        this.qualityGate = qualityGate;
    }

    public String getQualityGateTimeout() {
        return qualityGateTimeout;
    }

    @DataBoundSetter
    public void setQualityGateTimeout(String qualityGateTimeout) {
        this.qualityGateTimeout = qualityGateTimeout;
    }

    // public String getContainerRunMode() {
    // return containerRunMode;
    // }

    // @DataBoundSetter
    // public void setContainerRunMode(String containerRunMode) {
    // this.containerRunMode = containerRunMode;
    // }

    public String getTrivyMode() {
        return trivyMode;
    }

    @DataBoundSetter
    public void setTrivyMode(String trivyMode) {
        this.trivyMode = trivyMode;
    }

    public String getTrivyTarget() {
        return trivyTarget;
    }

    @DataBoundSetter
    public void setTrivyTarget(String trivyTarget) {
        this.trivyTarget = trivyTarget;
    }

    public String getTrivyReportDir() {
        return trivyReportDir;
    }

    @DataBoundSetter
    public void setTrivyReportDir(String trivyReportDir) {
        this.trivyReportDir = trivyReportDir;
    }

    public String getScaMode() {
        return scaMode;
    }

    @DataBoundSetter
    public void setScaMode(String scaMode) {
        this.scaMode = scaMode;
    }

    public String getScaTarget() {
        return scaTarget;
    }

    @DataBoundSetter
    public void setScaTarget(String scaTarget) {
        this.scaTarget = scaTarget;
    }

    public String getScaReportDir() {
        return scaReportDir;
    }

    @DataBoundSetter
    public void setScaReportDir(String scaReportDir) {
        this.scaReportDir = scaReportDir;
    }

    public String getScaRegistryUrl() {
        return scaRegistryUrl;
    }

    @DataBoundSetter
    public void setScaRegistryUrl(String scaRegistryUrl) {
        this.scaRegistryUrl = scaRegistryUrl;
    }

    public String getScaRepository() {
        return scaRepository;
    }

    @DataBoundSetter
    public void setScaRepository(String scaRepository) {
        this.scaRepository = scaRepository;
    }

    public String getScaImageName() {
        return scaImageName;
    }

    @DataBoundSetter
    public void setScaImageName(String scaImageName) {
        this.scaImageName = scaImageName;
    }

    public String getScaImageTag() {
        return scaImageTag;
    }

    @DataBoundSetter
    public void setScaImageTag(String scaImageTag) {
        this.scaImageTag = scaImageTag;
    }

    public String getContainerClusterName() {
        return containerClusterName;
    }

    @DataBoundSetter
    public void setContainerClusterName(String containerClusterName) {
        this.containerClusterName = containerClusterName;
    }

    public String getContainerNamespace() {
        return containerNamespace;
    }

    @DataBoundSetter
    public void setContainerNamespace(String containerNamespace) {
        this.containerNamespace = containerNamespace;
    }

    public String getContainerTargetUrl() {
        return containerTargetUrl;
    }

    @DataBoundSetter
    public void setContainerTargetUrl(String containerTargetUrl) {
        this.containerTargetUrl = containerTargetUrl;
    }

    // public String getDynamicRunMode() {
    // return dynamicRunMode;
    // }

    // @DataBoundSetter
    // public void setDynamicRunMode(String dynamicRunMode) {
    // this.dynamicRunMode = dynamicRunMode;
    // }

    public String getRunMode() {
        return runMode;
    }

    @DataBoundSetter
    public void setRunMode(String runMode) {
        this.runMode = runMode;
    }

    public String getZapMode() {
        return zapMode;
    }

    @DataBoundSetter
    public void setZapMode(String zapMode) {
        this.zapMode = zapMode;
    }

    public String getZapTarget() {
        return zapTarget;
    }

    @DataBoundSetter
    public void setZapTarget(String zapTarget) {
        this.zapTarget = zapTarget;
    }

    public String getZapApiFormat() {
        return zapApiFormat;
    }

    @DataBoundSetter
    public void setZapApiFormat(String zapApiFormat) {
        this.zapApiFormat = zapApiFormat;
    }

    public String getZapReportDir() {
        return zapReportDir;
    }

    @DataBoundSetter
    public void setZapReportDir(String zapReportDir) {
        this.zapReportDir = zapReportDir;
    }

    public String getDastMode() {
        return dastMode;
    }

    @DataBoundSetter
    public void setDastMode(String dastMode) {
        this.dastMode = dastMode;
    }

    public String getDastTarget() {
        return dastTarget;
    }

    @DataBoundSetter
    public void setDastTarget(String dastTarget) {
        this.dastTarget = dastTarget;
    }

    public String getDastApiFormat() {
        return dastApiFormat;
    }

    @DataBoundSetter
    public void setDastApiFormat(String dastApiFormat) {
        this.dastApiFormat = dastApiFormat;
    }

    public String getDastReportDir() {
        return dastReportDir;
    }

    @DataBoundSetter
    public void setDastReportDir(String dastReportDir) {
        this.dastReportDir = dastReportDir;
    }

    public String getDynamicClusterName() {
        return dynamicClusterName;
    }

    @DataBoundSetter
    public void setDynamicClusterName(String dynamicClusterName) {
        this.dynamicClusterName = dynamicClusterName;
    }

    public String getDynamicNamespace() {
        return dynamicNamespace;
    }

    @DataBoundSetter
    public void setDynamicNamespace(String dynamicNamespace) {
        this.dynamicNamespace = dynamicNamespace;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    @DataBoundSetter
    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public String getDataSourceName() {
        return dataSourceName;
    }

    @DataBoundSetter
    public void setDataSourceName(String dataSourceName) {
        this.dataSourceName = dataSourceName;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run,
            @Nonnull FilePath workspace,
            @Nonnull EnvVars env,
            @Nonnull Launcher launcher,
            @Nonnull TaskListener listener) throws InterruptedException, IOException {
        run(run, workspace, env, launcher, listener);
    }

    /**
     * Runs the configured scan and returns the result map
     * (success/status/scanId/reportUrl/message).
     * Shared by the classic Builder's perform(...) above (which discards the return
     * value — required
     * by the void SimpleBuildStep contract) and CavSecurityPipelineStep's
     * StepExecution (which returns
     * it to the Jenkinsfile). Throws AbortException on scan failure
     * (SAST/standalone-SCA/standalone-DAST)
     * or on any unexpected error, matching the classic Builder's existing
     * abort-on-failure behavior.
     * Kubernetes-mode SCA/DAST intentionally does NOT abort on a non-success result
     * map — it's a
     * fire-and-forget trigger ("scanning will take 5-10 minutes").
     */
    public Map<String, Object> run(@Nonnull Run<?, ?> run,
            @Nonnull FilePath workspace,
            @Nonnull EnvVars env,
            @Nonnull Launcher launcher,
            @Nonnull TaskListener listener) throws InterruptedException, IOException {
        CavLogger.configure(env);
        try {
            CavissonConnection connection = CavissonConnectionResolver.resolve(
                    run, env, connectionMode, baseUrl, apiTokenCredentialId, cavScanServiceConnection);

            String resolvedBaseUrl = connection.getBaseUrl();
            String apiToken = connection.getApiToken();
            boolean allowInsecureSSL = true;

            String resolvedDataSourceName = trimToEmpty(expand(env, dataSourceName));

            if (resolvedDataSourceName.isEmpty()) {
                resolvedDataSourceName = "Ticket";
            }

            // CavLogger.info(listener, "Data Source Name : " + resolvedDataSourceName);

            String resolvedProject = expand(env, project);
            if (resolvedProject == null || resolvedProject.trim().isEmpty()) {
                resolvedProject = env.getOrDefault("JOB_NAME", "unknown-project");
            }

            FilePath trivyReportDirForPublish = null;
            FilePath zapReportDirForPublish = null;

            Map<String, Object> result;

            try {
                if ("SAST".equals(scanType)) {
                    result = CodeAnalyzerRunner.run(launcher, listener, workspace, env, resolvedBaseUrl, apiToken,
                            resolvedProject, expand(env, targetPath), expand(env, qualityGate),
                            expand(env, qualityGateTimeout));

                    if (!Boolean.TRUE.equals(result.get("success"))) {
                        throw new AbortException("SAST scan failed: " + result.get("message"));
                    }

                } else if ("SCA".equals(scanType)) {
                    // String runMode = lower(containerRunMode, "standalone");
                    String effectiveRunMode = lower(runMode, "standalone");
                    // validateKubernetesInputs(runMode, containerClusterName, containerNamespace,
                    // "Container Scan");
                    String resolvedContainerClusterName = expand(env, containerClusterName);
                    String resolvedContainerNamespace = expand(env, containerNamespace);
                    String resolvedContainerTargetUrl = expand(env, containerTargetUrl);

                    if ("standalone".equals(effectiveRunMode)) {
                        CavLogger.debug(listener, "SCA Run Mode is standalone. Running Trivy shell scan only.");

                        FilePath trivyResultDir = runTrivyStandalone(launcher, listener, workspace, env);
                        trivyReportDirForPublish = trivyResultDir;

                        result = SecurityReportPublisher.saveSecurityReportToMongo(launcher, listener, workspace, env,
                                run,
                                resolvedBaseUrl, apiToken, allowInsecureSSL, "trivy", trivyResultDir,
                                lower(firstNonBlank(scaMode, trivyMode), "image"),
                                expand(env, firstNonBlank(scaTarget, trivyTarget)), "");

                        if (!Boolean.TRUE.equals(result.get("success"))) {
                            throw new AbortException("SCA standalone Mongo save failed/skipped: "
                                    + result.get("message"));
                        }

                    } else if ("kubernetes".equals(effectiveRunMode)) {
                        // String resolvedContainerTargetUrl = expand(env, containerTargetUrl);
                        // requireNonEmpty(resolvedContainerTargetUrl,
                        // "Container Scan: Application Target URL is required when Container Run Mode
                        // is kubernetes.");

                        CavLogger.debug(listener, "SCA Run Mode is kubernetes. Calling SCA REST API.");
                        CavLogger.debug(listener,
                                "Kubernetes Cluster Name : " + optionalLogValue(resolvedContainerClusterName));
                        CavLogger.debug(listener,
                                "Kubernetes Namespace    : " + optionalLogValue(resolvedContainerNamespace));
                        CavLogger.debug(listener,
                                "Application Target URL  : " + optionalLogValue(resolvedContainerTargetUrl));

                        result = SecurityScanApiClient.callSecurityScanApi(
                                listener,
                                resolvedBaseUrl,
                                apiToken,
                                allowInsecureSSL,
                                "SCA Scan",
                                false,
                                true,
                                resolvedDataSourceName);

                    } else {
                        throw new AbortException("Unsupported SCA run mode: " + runMode);
                    }

                    maybeEvaluateQualityGate(listener, env, resolvedBaseUrl, apiToken);

                } else if ("DAST".equals(scanType)) {
                    // String runMode = lower(dynamicRunMode, "standalone");
                    String effectiveRunMode = lower(runMode, "standalone");
                    // validateKubernetesInputs(runMode, dynamicClusterName, dynamicNamespace,
                    // "Dynamic Scan");

                    String resolvedDynamicClusterName = expand(env, dynamicClusterName);
                    String resolvedDynamicNamespace = expand(env, dynamicNamespace);
                    String resolvedTargetUrl = trimToEmpty(expand(
                            env,
                            firstNonBlank(
                                    targetUrl,
                                    firstNonBlank(dastTarget, zapTarget))));

                    if ("standalone".equals(effectiveRunMode)) {
                        CavLogger.debug(listener, "DAST Run Mode is standalone. Running DAST shell scan only.");

                        requireNonEmpty(
                                resolvedTargetUrl,
                                "DAST Target URL is required when DAST Run Mode is standalone.");

                        String resolvedServices = resolveDastService(resolvedTargetUrl);

                        requireNonEmpty(
                                resolvedServices,
                                "Service name is required. Provide the services parameter or include the service name in the target URL, for example: http://host:port/checkout");

                        CavLogger.debug(listener, "DAST Service : " + resolvedServices);

                        FilePath zapResultDir = runZapStandalone(launcher, listener, workspace, env);
                        zapReportDirForPublish = zapResultDir;

                        result = SecurityReportPublisher.saveSecurityReportToMongo(launcher, listener, workspace, env,
                                run,
                                resolvedBaseUrl, apiToken, allowInsecureSSL, "zap", zapResultDir,
                                lower(firstNonBlank(dastMode, zapMode), "baseline"),
                                resolvedTargetUrl,
                                resolvedServices);
                        // trimToEmpty(expand(env,
                        // firstNonBlank(targetUrl, firstNonBlank(dastTarget, zapTarget))
                        // )));

                        if (!Boolean.TRUE.equals(result.get("success"))) {
                            throw new AbortException("DAST standalone Mongo save failed/skipped: "
                                    + result.get("message"));
                        }

                    } else if ("kubernetes".equals(effectiveRunMode)) {
                        // String resolvedTargetUrl = expand(env, targetUrl);
                        // requireNonEmpty(resolvedTargetUrl,
                        // "Dynamic Scan: Application Target URL is required when Dynamic Run Mode is
                        // kubernetes.");

                        // listener.getLogger().println("Dynamic Run Mode is kubernetes. Calling ZAP
                        // REST API.");

                        CavLogger.debug(listener, "DAST Run Mode is kubernetes. Calling ZAP REST API.");
                        CavLogger.debug(listener,
                                "Kubernetes Cluster Name : " + optionalLogValue(resolvedDynamicClusterName));
                        CavLogger.debug(listener,
                                "Kubernetes Namespace    : " + optionalLogValue(resolvedDynamicNamespace));
                        CavLogger.debug(listener, "Application Target URL  : " + optionalLogValue(resolvedTargetUrl));

                        // SecurityScanApiClient.callSecurityScanApi(listener, baseUrl, apiToken,
                        // allowInsecureSSL,
                        // "ZAP DAST Scan", true, false);
                        result = SecurityScanApiClient.callSecurityScanApi(
                                listener,
                                resolvedBaseUrl,
                                apiToken,
                                allowInsecureSSL,
                                "DAST Scan",
                                true,
                                false,
                                resolvedDataSourceName);

                    } else {
                        throw new AbortException("Unsupported DAST run mode: " + runMode);
                    }

                    maybeEvaluateQualityGate(listener, env, resolvedBaseUrl, apiToken);

                } else {
                    // throw new AbortException("Invalid Scan Type '" + scanType + "'. Allowed
                    // values: SAST/static, SCA/container, DAST/dynamic.");
                    throw new AbortException("Invalid Scan Type '" + scanType + "'. Allowed values: SAST, SCA, DAST.");
                }

            } catch (IOException | InterruptedException | RuntimeException error) {
                if (error instanceof InterruptedException) {
                    throw (InterruptedException) error;
                }

                // String message = error.getMessage() != null ? error.getMessage() :
                // error.toString();
                // throw new AbortException("Cavisson security scan failed: " + message);
                String message = error.getMessage() != null ? error.getMessage() : error.toString();

                String resultScanType = displayScanType(scanType);
                String resultRunMode = "";

                if ("SCA".equals(scanType) || "DAST".equals(scanType)) {
                    resultRunMode = lower(runMode, "standalone");
                }

                if ("SAST".equals(scanType) || "SCA".equals(scanType) || "DAST".equals(scanType)) {
                    ScanResultPrinter.print(
                            listener,
                            resultScanType,
                            resultRunMode,
                            "",
                            false,
                            "FAILED",
                            "",
                            "",
                            message);
                }

                throw new AbortException("Cavisson security scan failed: " + message);
            }

            // listener.getLogger().println("Cavisson security scan completed
            // successfully.");
            String effectiveFinalRunMode = lower(expand(env, runMode), "standalone");

            if ("SAST".equals(scanType)) {
                CavLogger.info(listener, "Cavisson security SAST scan completed successfully.");

            } else if ("SCA".equals(scanType) && "kubernetes".equals(effectiveFinalRunMode)) {
                CavLogger.info(listener,
                        "Cavisson security SCA scan initiated, scanning will take 5-10 minutes to generate the report.");

            } else if ("SCA".equals(scanType) && "standalone".equals(effectiveFinalRunMode)) {
                CavLogger.info(listener, "Cavisson security SCA scan completed successfully.");

            } else if ("DAST".equals(scanType) && "kubernetes".equals(effectiveFinalRunMode)) {
                CavLogger.info(listener,
                        "Cavisson security DAST scan initiated, scanning will take 5-10 minutes to generate the report.");

            } else if ("DAST".equals(scanType) && "standalone".equals(effectiveFinalRunMode)) {
                CavLogger.info(listener, "Cavisson security DAST scan completed successfully.");
            }

            Object reportUrl = result.get("reportUrl");
            if (reportUrl instanceof String && !((String) reportUrl).trim().isEmpty()) {
                try {
                    CavissonDescriptionPublisher.appendReportRow(run, env, (String) reportUrl,
                            "Cavisson - Security Scan (" + displayScanType(scanType) + ")");
                } catch (IOException e) {
                    CavLogger.error(listener, "Unable to append report link to build description: " + e.getMessage());
                }
            }

            return result;
        } finally {
            CavLogger.clearConfiguration();
        }
    }

    private FilePath runTrivyStandalone(Launcher launcher, TaskListener listener, FilePath workspace, EnvVars env)
            throws IOException, InterruptedException {

        String mode = lower(firstNonBlank(scaMode, trivyMode), "image");
        String target = resolveScaTarget(env);
        requireNonEmpty(target,
                "SCA Docker image is required. Provide scaTarget or scaRegistryUrl/scaRepository/scaImageName/scaImageTag.");

        if (!Arrays.asList("image", "container", "fs", "repo").contains(mode)) {
            throw new AbortException("Invalid Trivy Mode '" + mode + "'. Allowed values: image, container, fs, repo");
        }

        FilePath reportDir = resolveReportDir(workspace, expand(env, firstNonBlank(scaReportDir, trivyReportDir)),
                "sca-reports");

        prepareStandaloneReportDirectoryAndDocker(launcher, listener, workspace, env,
                reportDir, "SCA / Trivy", "image".equals(mode) ? target : null);

        CavLogger.debug(listener, "========== SCA Standalone Scan ==========");
        CavLogger.debug(listener, "Type       : SCA");
        CavLogger.debug(listener, "Mode       : " + mode);
        CavLogger.debug(listener, "Target     : " + target);
        CavLogger.debug(listener, "Report Dir : " + reportDir.getRemote());
        CavLogger.debug(listener, "==========================================");

        List<String> args = new ArrayList<>();
        Collections.addAll(args, "--mode", mode, "--target", target, "--report-dir", reportDir.getRemote());

        int exitCode = ScriptRunner.runBashScript(launcher, listener, workspace, env,
                "trivy-standalone-wrapper.sh", args, "Run Trivy Standalone Wrapper");

        if (exitCode != 0) {
            throw new AbortException("trivy-standalone-wrapper.sh exited with code " + exitCode);
        }

        return reportDir;
    }

    private FilePath runZapStandalone(Launcher launcher, TaskListener listener, FilePath workspace, EnvVars env)
            throws IOException, InterruptedException {

        String mode = lower(firstNonBlank(dastMode, zapMode), "baseline");
        String target = trimToEmpty(expand(env,
                firstNonBlank(targetUrl, firstNonBlank(dastTarget, zapTarget))));
        String apiFormat = lower(firstNonBlank(dastApiFormat, zapApiFormat), "openapi");

        requireNonEmpty(target, "DAST Target URL is required when Dynamic Scan Run Mode is standalone.");

        if (!Arrays.asList("baseline", "full", "api").contains(mode)) {
            throw new AbortException("Invalid ZAP Mode '" + mode + "'. Allowed values: baseline, full, api");
        }

        if ("api".equals(mode) && !Arrays.asList("openapi", "graphql", "soap").contains(apiFormat)) {
            throw new AbortException(
                    "Invalid ZAP API Format '" + apiFormat + "'. Allowed values: openapi, graphql, soap");
        }

        FilePath reportDir = resolveReportDir(workspace, expand(env, firstNonBlank(dastReportDir, zapReportDir)),
                "dast-reports");

        prepareStandaloneReportDirectoryAndDocker(launcher, listener, workspace, env,
                reportDir, "DAST / ZAP", null);

        List<String> args = new ArrayList<>();
        Collections.addAll(args, "--mode", mode, "--target", target, "--report-dir", reportDir.getRemote());

        if ("api".equals(mode)) {
            Collections.addAll(args, "--api-format", apiFormat);
        }

        CavLogger.debug(listener, "========== DAST Standalone Scan ==========");
        CavLogger.debug(listener, "Type       : DAST");
        CavLogger.debug(listener, "Mode       : " + mode);
        CavLogger.debug(listener, "Target     : " + target);
        CavLogger.debug(listener, "Report Dir : " + reportDir.getRemote());
        CavLogger.debug(listener, "========================================");

        int exitCode = ScriptRunner.runBashScript(launcher, listener, workspace, env,
                "zap-standalone-wrapper.sh", args, "Run ZAP Standalone Wrapper");

        if (exitCode != 0) {
            throw new AbortException("zap-standalone-wrapper.sh exited with code " + exitCode);
        }

        return reportDir;
    }

    private static void prepareStandaloneReportDirectoryAndDocker(Launcher launcher,
            TaskListener listener,
            FilePath workspace,
            EnvVars env,
            FilePath reportDir,
            String stageLabel,
            String dockerImageToPull)
            throws IOException, InterruptedException {

        CavLogger.debug(listener, "========== " + stageLabel + " Workspace Preparation ==========");
        CavLogger.debug(listener, "Jenkins Workspace : " + workspace.getRemote());
        CavLogger.debug(listener, "Report Directory  : " + reportDir.getRemote());
        if (dockerImageToPull != null && !dockerImageToPull.trim().isEmpty()) {
            CavLogger.debug(listener, "Docker Image      : " + dockerImageToPull);
        }
        CavLogger.debug(listener, "=============================================================");

        // Clean only the scan report directory, not the full Jenkins workspace.
        reportDir.deleteRecursive();
        reportDir.mkdirs();
        reportDir.chmod(0777);

        StringBuilder script = new StringBuilder();
        script.append("set -e\n");
        // script.append("echo 'Checking Docker access...'\n");
        script.append("docker version >/dev/null 2>&1\n");
        script.append("mkdir -p '").append(shellQuote(reportDir.getRemote())).append("'\n");
        script.append("chmod 777 '").append(shellQuote(reportDir.getRemote())).append("' >/dev/null 2>&1 || true\n");
        script.append("if command -v setfacl >/dev/null 2>&1; then\n");
        script.append("  setfacl -m u:$(whoami):rwx '").append(shellQuote(reportDir.getRemote()))
                .append("' >/dev/null 2>&1 || true\n");
        script.append("  setfacl -d -m u:$(whoami):rwX '").append(shellQuote(reportDir.getRemote()))
                .append("' >/dev/null 2>&1 || true\n");
        script.append("fi\n");
        // script.append("ls -ld
        // '").append(shellQuote(reportDir.getRemote())).append("'\n");

        if (dockerImageToPull != null && !dockerImageToPull.trim().isEmpty()) {
            String image = shellQuote(dockerImageToPull.trim());
            // script.append("echo 'Pulling/checking Docker image directly...'\n");
            script.append("if ! docker image inspect '").append(image).append("' >/dev/null 2>&1; then\n");
            script.append("  docker pull '").append(image).append("' >/dev/null 2>&1\n");
            script.append("fi\n");
            script.append("docker image inspect '").append(image).append("' >/dev/null 2>&1\n");
        }

        int exitCode = runShellSnippet(launcher, listener, workspace, env, script.toString(),
                stageLabel + " Pre-check Docker and Report Directory");

        if (exitCode != 0) {
            throw new AbortException(stageLabel + " workspace/Docker preparation failed with exit code " + exitCode);
        }
    }

    private static int runShellSnippet(Launcher launcher,
            TaskListener listener,
            FilePath workspace,
            EnvVars env,
            String scriptContent,
            String title) throws IOException, InterruptedException {
        FilePath script = workspace.child(".cav-security-pipeline").child("run-" + sanitizeForFile(title) + ".sh");
        script.getParent().mkdirs();
        script.write(scriptContent, "UTF-8");
        script.chmod(0755);

        CavLogger.debug(listener, "========== " + title + " ==========");
        CavLogger.debug(listener, "Script: " + script.getRemote());
        CavLogger.debug(listener, "===============================================");

        return launcher.launch()
                .cmds("bash", script.getRemote())
                .envs(new HashMap<String, String>(env))
                .pwd(workspace)
                .quiet(true)
                .stdout(listener)
                .join();
    }

    private static String shellQuote(String value) {
        return value == null ? "" : value.replace("'", "'\"'\"'");
    }

    private static String sanitizeForFile(String value) {
        String source = value == null ? "script" : value;
        return source.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String resolveScaTarget(EnvVars env) {
        String directTarget = expand(env, firstNonBlank(scaTarget, trivyTarget));

        // The old default eshoponweb:latest should not override registry fields.
        boolean directTargetIsOldDefault = "eshoponweb:latest".equals(directTarget);
        if (directTarget != null && !directTarget.trim().isEmpty() && !directTargetIsOldDefault) {
            return directTarget.trim();
        }

        String registry = trimToEmpty(expand(env, scaRegistryUrl));
        String repository = trimToEmpty(expand(env, scaRepository));
        String imageName = trimToEmpty(expand(env, scaImageName));
        String imageTag = trimToEmpty(expand(env, scaImageTag));

        if (imageName.isEmpty()) {
            return directTarget == null ? "" : directTarget.trim();
        }

        if (imageTag.isEmpty()) {
            imageTag = "latest";
        }

        StringBuilder image = new StringBuilder();
        if (!registry.isEmpty()) {
            image.append(stripTrailingSlash(registry)).append("/");
        }
        if (!repository.isEmpty()) {
            image.append(stripBothSlashes(repository)).append("/");
        }
        image.append(stripBothSlashes(imageName)).append(":").append(imageTag);
        return image.toString();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String resolveDastService(String resolvedTargetUrl) {
        return extractServiceFromTargetUrl(resolvedTargetUrl);
    }

    private static String extractServiceFromTargetUrl(String targetUrl) {
    String resolvedUrl = trimToEmpty(targetUrl);

    if (resolvedUrl.isEmpty()) {
        return "";
    }

    try {
        java.net.URI uri = java.net.URI.create(resolvedUrl);

        String path = trimToEmpty(uri.getPath());

        /*
         * If a path is present, use the last non-empty path segment.
         *
         * Example:
         * http://66.220.31.130:30741/frontend
         * service = frontend
         */
        if (!path.isEmpty() && !"/".equals(path)) {
            String[] pathSegments = path.split("/");

            for (int index = pathSegments.length - 1; index >= 0; index--) {
                String segment = trimToEmpty(pathSegments[index]);

                if (!segment.isEmpty()) {
                    return java.net.URLDecoder.decode(
                            segment,
                            java.nio.charset.StandardCharsets.UTF_8.name()
                    );
                }
            }
        }

        /*
         * If no path is present, use the URL host.
         *
         * Example:
         * http://66.220.31.130:30741/
         * service = 66.220.31.130
         */
        return trimToEmpty(uri.getHost());

    } catch (Exception exception) {
        return "";
    }
}

    private static String stripTrailingSlash(String value) {
        String result = trimToEmpty(value);
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String stripBothSlashes(String value) {
        String result = trimToEmpty(value);
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    // private static String normalizeScanType(String value) {
    // String source = (value == null || value.trim().isEmpty()) ? "static" :
    // value.trim().toLowerCase(Locale.ROOT);
    // if ("sast".equals(source)) {
    // return "static";
    // }
    // if ("sca".equals(source)) {
    // return "container";
    // }
    // if ("dast".equals(source)) {
    // return "dynamic";
    // }
    // return source;
    // }
    private static String normalizeScanType(String value) {
        String source = (value == null || value.trim().isEmpty()) ? "" : value.trim();

        if ("SAST".equalsIgnoreCase(source) || "static".equalsIgnoreCase(source)) {
            return "SAST";
        }

        if ("SCA".equalsIgnoreCase(source) || "container".equalsIgnoreCase(source)) {
            return "SCA";
        }

        if ("DAST".equalsIgnoreCase(source) || "dynamic".equalsIgnoreCase(source)) {
            return "DAST";
        }

        return source.toUpperCase(Locale.ROOT);
    }

    private static String displayScanType(String canonicalScanType) {
        if ("SAST".equals(canonicalScanType)) {
            return "SAST";
        }
        if ("SCA".equals(canonicalScanType)) {
            return "SCA";
        }
        if ("DAST".equals(canonicalScanType)) {
            return "DAST";
        }
        return canonicalScanType;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.trim().isEmpty()) {
            return preferred;
        }
        return fallback;
    }

    private static FilePath resolveReportDir(FilePath workspace, String configuredDir, String defaultChildName) {
        if (configuredDir != null && !configuredDir.trim().isEmpty()) {
            return new FilePath(workspace, configuredDir.trim());
        }
        return workspace.child(defaultChildName);
    }

    // private static void validateKubernetesInputs(String runMode, String
    // clusterName, String namespace, String stageName) throws AbortException {
    // if ("kubernetes".equals(runMode)) {
    // if (clusterName == null || clusterName.trim().isEmpty()) {
    // throw new AbortException(stageName + ": Kubernetes Cluster Name is required
    // when Run Mode is kubernetes.");
    // }
    // if (namespace == null || namespace.trim().isEmpty()) {
    // throw new AbortException(stageName + ": Kubernetes Namespace is required when
    // Run Mode is kubernetes.");
    // }
    // }
    // }

    private static String optionalLogValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "(optional - not provided)";
        }
        return value.trim();
    }

    private static void requireNonEmpty(String value, String message) throws AbortException {
        if (value == null || value.trim().isEmpty()) {
            throw new AbortException(message);
        }
    }

    private static String lower(String value, String defaultValue) {
        String source = (value == null || value.trim().isEmpty()) ? defaultValue : value.trim();
        return source.toLowerCase(Locale.ROOT);
    }

    private static String expand(EnvVars env, String value) {
        return value == null ? null : env.expand(value);
    }

    private void maybeEvaluateQualityGate(TaskListener listener, EnvVars env, String baseUrl, String apiToken)
            throws IOException {
        String resolvedQualityGate = expand(env, qualityGate);
        if (resolvedQualityGate == null || resolvedQualityGate.trim().isEmpty()) {
            return;
        }

        QualityGateEvaluator.evaluate(listener, baseUrl, apiToken,
                env.getOrDefault("JOB_NAME", ""), env.getOrDefault("BUILD_NUMBER", ""),
                resolvedQualityGate, expand(env, qualityGateTimeout), new JSONObject());
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "CavSecurityPlugin";
        }

        public ListBoxModel doFillScanTypeItems() {
            ListBoxModel m = new ListBoxModel();
            m.add("SAST (Static / SonarQube)", "SAST");
            m.add("SCA (Container / Trivy)", "SCA");
            m.add("DAST (Dynamic / ZAP)", "DAST");
            return m;
        }

        public ListBoxModel doFillCavScanServiceConnectionItems(@AncestorInPath Item item,
                @QueryParameter String cavScanServiceConnection) {
            StandardListBoxModel result = new StandardListBoxModel();

            if (item == null) {
                return result.includeCurrentValue(cavScanServiceConnection);
            }

            return result
                    .includeEmptyValue()
                    .includeAs(ACL.SYSTEM, item, CavServiceConnection.class)
                    .includeCurrentValue(cavScanServiceConnection);
        }

        public ListBoxModel doFillConnectionModeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Existing Service Connection", "serviceConnection");
            items.add("Direct (Base URL + API Token)", "direct");
            return items;
        }

        public FormValidation doCheckBaseUrl(@QueryParameter String value, @QueryParameter String connectionMode) {
            if ("direct".equals(connectionMode)) {
                return requireNonEmptyValidation(value, "Base URL is required.");
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillApiTokenCredentialIdItems(@AncestorInPath Item item,
                @QueryParameter String apiTokenCredentialId) {
            StandardListBoxModel result = new StandardListBoxModel();

            if (item == null) {
                return result.includeCurrentValue(apiTokenCredentialId);
            }

            return result
                    .includeEmptyValue()
                    .includeAs(ACL.SYSTEM, item, StringCredentials.class)
                    .includeCurrentValue(apiTokenCredentialId);
        }

        public FormValidation doCheckApiTokenCredentialId(@QueryParameter String value, @QueryParameter String connectionMode) {
            if ("direct".equals(connectionMode)) {
                return requireNonEmptyValidation(value, "API Token credential is required.");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckCavScanServiceConnection(@QueryParameter String value, @QueryParameter String connectionMode) {
            if ("serviceConnection".equals(connectionMode)) {
                return requireNonEmptyValidation(value, "Service Connection is required.");
            }
            return FormValidation.ok();
        }

        private static FormValidation requireNonEmptyValidation(String value, String message) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error(message);
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillTrivyModeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Image", "image");
            items.add("Container", "container");
            items.add("Repository", "repo");
            return items;
        }

        public ListBoxModel doFillScaModeItems() {
            return doFillTrivyModeItems();
        }

        public ListBoxModel doFillZapModeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Baseline", "baseline");
            items.add("Full Scan", "full");
            items.add("API Scan", "api");
            return items;
        }

        public ListBoxModel doFillDastModeItems() {
            return doFillZapModeItems();
        }

        public ListBoxModel doFillZapApiFormatItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("OpenAPI", "openapi");
            items.add("GraphQL", "graphql");
            items.add("SOAP", "soap");
            return items;
        }

        public ListBoxModel doFillDastApiFormatItems() {
            return doFillZapApiFormatItems();
        }

        public FormValidation doCheckProject(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Static Scan Project Name is required.");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckTrivyTarget(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Target is required.");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckScaTarget(@QueryParameter String value) {
            return doCheckTrivyTarget(value);
        }

        public FormValidation doCheckZapTarget(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Target URL is required.");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckDastTarget(@QueryParameter String value) {
            return doCheckZapTarget(value);
        }
    }
}
