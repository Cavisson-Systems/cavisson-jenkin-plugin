package com.cavisson.jenkins.accessibility;

import com.cavisson.jenkins.connection.CavServiceConnection;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
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
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.verb.POST;

/**
 * Freestyle/general build-step equivalent of the Cav Accessibility Scanner Azure DevOps task:
 * runs the bundled Playwright + axe-core accessibility scan against the configured Application
 * URL, generates the JSON/HTML reports, and uploads them to the Cavisson server resolved from the
 * existing {@link CavServiceConnection}. Deliberately has no {@code @Symbol}, so it does not also
 * expose itself as a Pipeline DSL function - {@link AccessibilityScannerStep} owns the
 * {@code cavAccessibilityScanner} Pipeline step (with a return value), while this class covers
 * Freestyle jobs. Both share their scan/report/upload logic via
 * {@link AccessibilityScannerExecutor}, matching the pattern already used by every other task
 * pair in this plugin (e.g. {@code CavissonRunTestBuilder}/{@code CavissonRunTestStep}).
 *
 * <p>Unlike some other tasks in this plugin, this one intentionally supports only the "Cavisson
 * Service Connection" connection mode (no inline Base URL + API Token fields) - the Accessibility
 * scan/report upload both need exactly one Cavisson server, and the Service Connection is already
 * the single source of truth for it.
 */
public class AccessibilityScannerBuilder extends Builder implements SimpleBuildStep {

    private String cavConnection = "";
    private String controller = "/home/cavisson/work";
    private String applicationUrl = "";
    private String logLevel = "INFO";

    @DataBoundConstructor
    public AccessibilityScannerBuilder() {
    }

    public String getCavConnection() {
        return cavConnection;
    }

    @DataBoundSetter
    public void setCavConnection(String cavConnection) {
        this.cavConnection = cavConnection;
    }

    public String getController() {
        return controller;
    }

    /**
     * Controller path on the Cavisson server. Defaults to {@code /home/cavisson/work} - both here
     * (so a Pipeline script that omits {@code controller} entirely gets this default) and in the
     * Freestyle form's Advanced Options section (so a fresh job shows it pre-filled). Blank/null
     * falls back to the same default rather than being sent through empty.
     */
    @DataBoundSetter
    public void setController(String controller) {
        this.controller = (controller != null && !controller.trim().isEmpty())
                ? controller.trim() : "/home/cavisson/work";
    }

    public String getApplicationUrl() {
        return applicationUrl;
    }

    @DataBoundSetter
    public void setApplicationUrl(String applicationUrl) {
        this.applicationUrl = applicationUrl;
    }

    public String getLogLevel() {
        return logLevel;
    }

    /**
     * Console "Log Mode" - INFO (default): only the Application URL + completion milestones and
     * any [ERROR] lines. DEBUG: full scanner narration and raw npm/Playwright/scan output.
     * ERROR: [ERROR] lines only. Forwarded to {@link AccessibilityScannerExecutor}, which feeds
     * it into the existing {@link com.cavisson.jenkins.log.CavLogger} via a per-run
     * {@code LOG_LEVEL} override - no separate logger/log file is introduced.
     */
    @DataBoundSetter
    public void setLogLevel(String logLevel) {
        this.logLevel = (logLevel != null && !logLevel.trim().isEmpty())
                ? logLevel.trim().toUpperCase(java.util.Locale.ROOT) : "INFO";
    }

    @Override
    public void perform(@NonNull Run<?, ?> run,
                         @NonNull FilePath workspace,
                         @NonNull EnvVars env,
                         @NonNull Launcher launcher,
                         @NonNull TaskListener listener) throws InterruptedException, IOException {

        AccessibilityScannerExecutor.run(
                run, workspace, launcher, env, listener, cavConnection, controller, applicationUrl, logLevel);
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Cavisson - Accessibility Scanner";
        }

        @POST
        public ListBoxModel doFillCavConnectionItems(@AncestorInPath Item item,
                                                      @QueryParameter String cavConnection) {
            StandardListBoxModel result = new StandardListBoxModel();

            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(cavConnection);
                }
            } else if (!item.hasPermission(Item.EXTENDED_READ)
                    && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                return result.includeCurrentValue(cavConnection);
            }

            return result
                    .includeEmptyValue()
                    .includeAs(ACL.SYSTEM, item, CavServiceConnection.class)
                    .includeCurrentValue(cavConnection);
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckCavConnection(@QueryParameter String value) {
            return requireNonEmpty(value, "Cavisson Service Connection is required.");
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckController(@QueryParameter String value) {
            return requireNonEmpty(value, "Controller is required.");
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckApplicationUrl(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Application URL is required.");
            }
            String trimmed = value.trim();
            boolean looksLikeVariable = trimmed.startsWith("$");
            if (!looksLikeVariable && !trimmed.matches("(?i)^https?://.+")) {
                return FormValidation.error("Application URL must start with http:// or https://.");
            }
            return FormValidation.ok();
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public ListBoxModel doFillLogLevelItems() {
            ListBoxModel m = new ListBoxModel();
            m.add("INFO (Default)", "INFO");
            m.add("DEBUG", "DEBUG");
            m.add("ERROR", "ERROR");
            return m;
        }

        private static FormValidation requireNonEmpty(String value, String message) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error(message);
            }
            return FormValidation.ok();
        }
    }
}
