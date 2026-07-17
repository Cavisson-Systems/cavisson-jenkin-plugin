package com.cavisson.jenkins.qualitygate;

import com.cavisson.jenkins.connection.CavissonConnection;
import com.cavisson.jenkins.connection.CavissonConnectionResolver;
import com.cavisson.jenkins.connection.CavServiceConnection;
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
import org.jenkinsci.Symbol;
import org.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Map;

/**
 * Evaluates one combined Cavisson quality gate for a whole pipeline build, covering whatever
 * Static/Container/Dynamic stages already ran under the same JOB_NAME/BUILD_NUMBER — for users
 * who don't set a per-stage {@code qualityGate} field on each cavSecurityPipeline call and
 * instead want a single evaluation at the end of the pipeline.
 */
public class CavQualityGateBuilder extends Builder implements SimpleBuildStep {

    private String cavConnection = "";
    private final String qualityGateName;

    private String connectionMode = "serviceConnection";
    private String baseUrl = "";
    private String apiTokenCredentialId = "";

    private String qualityGateTimeout = "";

    @DataBoundConstructor
    public CavQualityGateBuilder(@Nonnull String qualityGateName) {
        this.qualityGateName = qualityGateName;
    }

    public String getCavConnection() {
        return cavConnection;
    }

    @DataBoundSetter
    public void setCavConnection(String cavConnection) {
        this.cavConnection = cavConnection;
    }

    public String getQualityGateName() {
        return qualityGateName;
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

    public String getQualityGateTimeout() {
        return qualityGateTimeout;
    }

    @DataBoundSetter
    public void setQualityGateTimeout(String qualityGateTimeout) {
        this.qualityGateTimeout = qualityGateTimeout;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run,
                         @Nonnull FilePath workspace,
                         @Nonnull EnvVars env,
                         @Nonnull Launcher launcher,
                         @Nonnull TaskListener listener) throws InterruptedException, IOException {

        CavissonConnection connection = CavissonConnectionResolver.resolve(
                run, env, connectionMode, baseUrl, apiTokenCredentialId, cavConnection);

        String resolvedBaseUrl = connection.getBaseUrl();
        String apiToken = connection.getApiToken();

        String resolvedQualityGateName = expand(env, qualityGateName);
        if (resolvedQualityGateName == null || resolvedQualityGateName.trim().isEmpty()) {
            throw new AbortException("Quality Gate Name is required.");
        }

        CavLogger.debug(listener, "========== Cavisson Quality Gate ==========");
        CavLogger.debug(listener, "Service Base URL  : " + resolvedBaseUrl);
        CavLogger.debug(listener, "Quality Gate Name : " + resolvedQualityGateName);
        CavLogger.debug(listener, "============================================");

        Map<String, String> reportTaskProperties = ReportTaskReader.readProperties(workspace);
        String ceTaskId = reportTaskProperties.get("ceTaskId");

        JSONObject pipelineParams = new JSONObject();
        if (ceTaskId != null && !ceTaskId.isEmpty()) {
            CavLogger.debug(listener, "Found SAST report-task.txt with ceTaskId, correlating Static scan results.");

            SonarTokenExchange.TokenResult tokens = SonarTokenExchange.exchangeToken(listener, resolvedBaseUrl, apiToken);
            String hostUrl = resolvedBaseUrl.replaceAll("/+$", "")
                    + "/cav-analysis/userName/" + tokens.userName + "/cavToken/" + apiToken;

            pipelineParams.put("sonar_url", hostUrl);
            pipelineParams.put("sonar_token", tokens.sonarToken);
            pipelineParams.put("project_key", reportTaskProperties.get("projectKey"));
            pipelineParams.put("ce_task_id", ceTaskId);
            pipelineParams.put("ce_task_url", reportTaskProperties.get("ceTaskUrl"));
        }

        String tsrNumber = env.get("CAV_TSR_NUMBER");
        if (tsrNumber != null && !tsrNumber.isEmpty()) {
            pipelineParams.put("tsrNumber", tsrNumber);
        }
        String coverageUuid = env.get("CAV_CODE_COVERAGE_UUID");
        if (coverageUuid != null && !coverageUuid.isEmpty()) {
            pipelineParams.put("coverageUuid", coverageUuid);
        }

        String coverageAppName = env.get("CAV_CODE_COVERAGE_APP_NAME");
        if (coverageAppName != null && !coverageAppName.isEmpty()) {
            pipelineParams.put("appName", coverageAppName);
        }

        String coverageReportUrl = env.get("CAV_CODE_COVERAGE_REPORT_URL");
        if (coverageReportUrl != null && !coverageReportUrl.isEmpty()) {
            pipelineParams.put("reportUrl", coverageReportUrl);
        }

        String coverageXmlPath = env.get("CAV_CODE_COVERAGE_XML_PATH");
        if (coverageXmlPath != null && !coverageXmlPath.isEmpty()) {
            pipelineParams.put("coverageXmlPath", coverageXmlPath);
        }

        String pipelineId = env.getOrDefault("JOB_NAME", "");
        String pipelineRunId = env.getOrDefault("BUILD_NUMBER", "");

        QualityGateEvaluator.evaluate(listener, resolvedBaseUrl, apiToken, pipelineId, pipelineRunId,
                resolvedQualityGateName, expand(env, qualityGateTimeout), pipelineParams);

        CavLogger.info(listener, "Cavisson Quality Gate evaluation completed.");
    }

    private static String expand(EnvVars env, String value) {
        return value == null ? null : env.expand(value);
    }

    @Symbol("cavQualityGate")
    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Cavisson Quality Gate";
        }

        public ListBoxModel doFillConnectionModeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Existing Service Connection", "serviceConnection");
            items.add("Direct (Base URL + API Token)", "direct");
            return items;
        }

        public ListBoxModel doFillCavConnectionItems(@AncestorInPath Item item,
                                                      @QueryParameter String cavConnection) {
            StandardListBoxModel result = new StandardListBoxModel();

            if (item == null) {
                return result.includeCurrentValue(cavConnection);
            }

            return result
                    .includeEmptyValue()
                    .includeAs(ACL.SYSTEM, item, CavServiceConnection.class)
                    .includeCurrentValue(cavConnection);
        }

        public FormValidation doCheckBaseUrl(@QueryParameter String value, @QueryParameter String connectionMode) {
            if ("direct".equals(connectionMode)) {
                return requireNonEmpty(value, "Base URL is required.");
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
                return requireNonEmpty(value, "API Token credential is required.");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckCavConnection(@QueryParameter String value, @QueryParameter String connectionMode) {
            if ("serviceConnection".equals(connectionMode)) {
                return requireNonEmpty(value, "Service Connection is required.");
            }
            return FormValidation.ok();
        }

        private static FormValidation requireNonEmpty(String value, String message) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error(message);
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckQualityGateName(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Quality Gate Name is required.");
            }
            return FormValidation.ok();
        }
    }
}
