package com.cavisson.jenkins.accessibility;

import com.cavisson.jenkins.connection.CavServiceConnection;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.verb.POST;

/**
 * Pipeline equivalent of the Cav Accessibility Scanner Azure DevOps task, exposed as the
 * {@code cavAccessibilityScanner} step:
 *
 * <pre>
 * cavAccessibilityScanner(
 *     cavConnection: 'cavisson-connection',
 *     controller: 'work',
 *     applicationUrl: 'https://example.com'
 * )
 * </pre>
 *
 * <p>All configuration fields are forwarded to an {@link AccessibilityScannerBuilder} delegate
 * instead of being duplicated here (same approach as {@code CavSecurityPipelineStep}), so the
 * field resolution/validation logic stays defined in exactly one place. Returns the scan result
 * Map (success/reportId/highestSeverity/violationsCount/...) so Pipeline scripts can do
 * {@code def r = cavAccessibilityScanner(...); echo r.highestSeverity}.
 */
public class AccessibilityScannerStep extends Step {

    private final AccessibilityScannerBuilder delegate = new AccessibilityScannerBuilder();

    @DataBoundConstructor
    public AccessibilityScannerStep() {
    }

    public String getCavConnection() {
        return delegate.getCavConnection();
    }

    @DataBoundSetter
    public void setCavConnection(String cavConnection) {
        delegate.setCavConnection(cavConnection);
    }

    public String getController() {
        return delegate.getController();
    }

    @DataBoundSetter
    public void setController(String controller) {
        delegate.setController(controller);
    }

    public String getApplicationUrl() {
        return delegate.getApplicationUrl();
    }

    @DataBoundSetter
    public void setApplicationUrl(String applicationUrl) {
        delegate.setApplicationUrl(applicationUrl);
    }

    public String getLogLevel() {
        return delegate.getLogLevel();
    }

    /**
     * Console "Log Mode" - see {@link AccessibilityScannerBuilder#setLogLevel(String)}. Example:
     * {@code cavAccessibilityScanner(..., logLevel: 'DEBUG')}.
     */
    @DataBoundSetter
    public void setLogLevel(String logLevel) {
        delegate.setLogLevel(logLevel);
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, delegate.getCavConnection(), delegate.getController(),
                delegate.getApplicationUrl(), delegate.getLogLevel());
    }

    private static final class Execution extends SynchronousNonBlockingStepExecution<Map<String, Object>> {

        private static final long serialVersionUID = 1L;

        private final String cavConnection;
        private final String controller;
        private final String applicationUrl;
        private final String logLevel;

        Execution(StepContext context, String cavConnection, String controller, String applicationUrl, String logLevel) {
            super(context);
            this.cavConnection = cavConnection;
            this.controller = controller;
            this.applicationUrl = applicationUrl;
            this.logLevel = logLevel;
        }

        @Override
        protected Map<String, Object> run() throws Exception {
            StepContext context = getContext();
            Run<?, ?> run = context.get(Run.class);
            FilePath workspace = context.get(FilePath.class);
            EnvVars env = context.get(EnvVars.class);
            Launcher launcher = context.get(Launcher.class);
            TaskListener listener = context.get(TaskListener.class);

            return AccessibilityScannerExecutor.run(
                    run, workspace, launcher, env, listener, cavConnection, controller, applicationUrl, logLevel);
        }
    }

    @Symbol("cavAccessibilityScanner")
    @Extension(optional = true)
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public Set<Class<?>> getRequiredContext() {
            Set<Class<?>> context = new HashSet<>();
            Collections.addAll(context, Run.class, FilePath.class, Launcher.class, TaskListener.class, EnvVars.class);
            return context;
        }

        @Override
        public String getFunctionName() {
            return "cavAccessibilityScanner";
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Cavisson - Accessibility Scanner";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return false;
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
