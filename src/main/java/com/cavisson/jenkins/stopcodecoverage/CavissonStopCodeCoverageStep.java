package com.cavisson.jenkins.stopcodecoverage;

import com.cavisson.jenkins.connection.CavissonConnection;
import com.cavisson.jenkins.connection.CavissonConnectionResolver;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.verb.POST;

/**
 * Pipeline equivalent of the DashboardServer "Stop Code Coverage" API, exposed as the
 * {@code cavissonStopCodeCoverage} step. Unlike {@link CavissonStopCodeCoverageBuilder} (used
 * for Freestyle jobs), this returns the result ({@code status}, {@code state},
 * {@code applicationName}, {@code reportUrl}) as a Map so Pipeline scripts can do:
 * {@code def result = cavissonStopCodeCoverage(...); echo result.reportUrl}.
 */
public class CavissonStopCodeCoverageStep extends Step {

    private String connectionMode = "serviceConnection";
    private String baseUrl = "";
    private String apiTokenCredentialId = "";
    private String cavServiceConnectionId = "";

    private String applicationName = "";

    @DataBoundConstructor
    public CavissonStopCodeCoverageStep() {
    }

    public String getConnectionMode() {
        return connectionMode;
    }

    @DataBoundSetter
    public void setConnectionMode(String connectionMode) {
        this.connectionMode = "serviceConnection".equals(connectionMode) ? "serviceConnection" : "direct";
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

    public String getCavServiceConnectionId() {
        return cavServiceConnectionId;
    }

    @DataBoundSetter
    public void setCavServiceConnectionId(String cavServiceConnectionId) {
        this.cavServiceConnectionId = cavServiceConnectionId;
    }

    public String getApplicationName() {
        return applicationName;
    }

    @DataBoundSetter
    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    @Override
    public StepExecutionImpl start(StepContext context) {
        return new StepExecutionImpl(this, context);
    }

    private static final class StepExecutionImpl extends SynchronousNonBlockingStepExecution<Map<String, Object>> {

        private static final long serialVersionUID = 1L;

        private final transient CavissonStopCodeCoverageStep step;

        StepExecutionImpl(CavissonStopCodeCoverageStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        protected Map<String, Object> run() throws Exception {
            StepContext context = getContext();
            Run<?, ?> run = context.get(Run.class);
            TaskListener listener = context.get(TaskListener.class);
            EnvVars env = context.get(EnvVars.class);

            CavissonConnection connection = CavissonConnectionResolver.resolve(run, env, step.getConnectionMode(),
                    step.getBaseUrl(), step.getApiTokenCredentialId(), step.getCavServiceConnectionId());

            return CavissonStopCodeCoverageExecutor.run(run, env, listener, connection, step.getApplicationName());
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "cavissonStopCodeCoverage";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Cavisson - Stop Code Coverage";
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            return new HashSet<>(Arrays.asList(Run.class, TaskListener.class, EnvVars.class));
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public ListBoxModel doFillConnectionModeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Existing Service Connection", "serviceConnection");
            items.add("Direct (Base URL + API Token)", "direct");
            return items;
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckBaseUrl(@QueryParameter String value, @QueryParameter String connectionMode) {
            if ("direct".equals(connectionMode)) {
                return requireNonEmpty(value, "Base URL is required.");
            }
            return FormValidation.ok();
        }

        @POST
        public ListBoxModel doFillApiTokenCredentialIdItems(@AncestorInPath Item item,
                                                             @QueryParameter String apiTokenCredentialId) {
            StandardListBoxModel result = new StandardListBoxModel();

            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(apiTokenCredentialId);
                }
            } else if (!item.hasPermission(Item.EXTENDED_READ)
                    && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                return result.includeCurrentValue(apiTokenCredentialId);
            }

            return result
                    .includeEmptyValue()
                    .includeAs(ACL.SYSTEM, item, StringCredentials.class)
                    .includeCurrentValue(apiTokenCredentialId);
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckApiTokenCredentialId(@QueryParameter String value, @QueryParameter String connectionMode) {
            if ("direct".equals(connectionMode)) {
                return requireNonEmpty(value, "API Token credential is required.");
            }
            return FormValidation.ok();
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckCavServiceConnectionId(@QueryParameter String value, @QueryParameter String connectionMode) {
            if ("serviceConnection".equals(connectionMode)) {
                return requireNonEmpty(value, "Service Connection ID is required.");
            }
            return FormValidation.ok();
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckApplicationName(@QueryParameter String value) {
            return requireNonEmpty(value, "Application Name is required.");
        }

        private static FormValidation requireNonEmpty(String value, String message) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error(message);
            }
            return FormValidation.ok();
        }
    }
}
