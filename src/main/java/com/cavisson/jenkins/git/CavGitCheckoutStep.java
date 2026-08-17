package com.cavisson.jenkins.git;

import com.cavisson.jenkins.connection.CavissonConnection;
import com.cavisson.jenkins.connection.CavissonConnectionResolver;
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
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pipeline equivalent of {@code cavGitCheckout}: resolves a named Cavisson Git Integration's
 * username/PAT/Repository URL and actually performs the {@code git clone} into the workspace, e.g.
 * <pre>
 *   def git = cavGitCheckout(baseUrl: 'https://demoai-lle.cav-test.com:4444',
 *                             apiTokenCredentialId: 'cav-token-secret',
 *                             gitIntegrationName: 'Boutique_GIT',
 *                             gitBranch: 'main')
 * </pre>
 * {@code gitRepoUrl} does not need to be passed at all in the common case: the named Git
 * Integration's own "Repository URL" field (resolved fresh from the server) is used
 * automatically. It only needs to be supplied explicitly to override that, or as a fallback (a
 * {@code GIT_REPO_URL} build environment variable also works) if the server's record has that
 * field blank - see {@link CavGitCheckoutExecutor}. The resolved username/token/repoUrl/branch
 * are still returned as a Map afterward, in case the script wants to reuse them (e.g. for an
 * authenticated push later in the build).
 * <p>{@code connectionMode} defaults to {@code "direct"} (Base URL + a Jenkins "Secret text"
 * credential holding the cavToken) when omitted from the call - so most scripts never need to
 * declare it - but it can still be set to {@code connectionMode: 'serviceConnection'} to use an
 * existing "Cavisson Service Connection" credential (via {@code cavServiceConnectionId}) instead.
 */
public class CavGitCheckoutStep extends Step {

    private String connectionMode = "direct";
    private String baseUrl = "";
    private String apiTokenCredentialId = "";
    private String cavServiceConnectionId = "";

    private String gitIntegrationName = "";
    private String gitRepoUrl = "";
    private String gitBranch = "main";

    @DataBoundConstructor
    public CavGitCheckoutStep() {
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

    public String getGitIntegrationName() {
        return gitIntegrationName;
    }

    @DataBoundSetter
    public void setGitIntegrationName(String gitIntegrationName) {
        this.gitIntegrationName = gitIntegrationName;
    }

    public String getGitRepoUrl() {
        return gitRepoUrl;
    }

    @DataBoundSetter
    public void setGitRepoUrl(String gitRepoUrl) {
        this.gitRepoUrl = gitRepoUrl;
    }

    public String getGitBranch() {
        return gitBranch;
    }

    @DataBoundSetter
    public void setGitBranch(String gitBranch) {
        this.gitBranch = gitBranch;
    }

    @Override
    public StepExecutionImpl start(StepContext context) {
        return new StepExecutionImpl(this, context);
    }

    private static final class StepExecutionImpl extends SynchronousNonBlockingStepExecution<Map<String, Object>> {

        private static final long serialVersionUID = 1L;

        private final transient CavGitCheckoutStep step;

        StepExecutionImpl(CavGitCheckoutStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        protected Map<String, Object> run() throws Exception {
            StepContext context = getContext();
            Run<?, ?> run = context.get(Run.class);
            FilePath workspace = context.get(FilePath.class);
            Launcher launcher = context.get(Launcher.class);
            TaskListener listener = context.get(TaskListener.class);
            EnvVars env = context.get(EnvVars.class);

            CavissonConnection connection = CavissonConnectionResolver.resolve(run, env, step.getConnectionMode(),
                    step.getBaseUrl(), step.getApiTokenCredentialId(), step.getCavServiceConnectionId());

            return CavGitCheckoutExecutor.run(run, workspace, launcher, env, listener, connection,
                    step.getGitIntegrationName(), step.getGitRepoUrl(), step.getGitBranch());
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "cavGitCheckout";
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Cavisson - Git Checkout";
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            return new HashSet<>(Arrays.asList(Run.class, FilePath.class, Launcher.class, TaskListener.class, EnvVars.class));
        }

        public ListBoxModel doFillConnectionModeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Direct (Base URL + API Token)", "direct");
            items.add("Existing Service Connection", "serviceConnection");
            return items;
        }

        public FormValidation doCheckBaseUrl(@QueryParameter String value, @QueryParameter String connectionMode) {
            if (!"serviceConnection".equals(connectionMode)) {
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
            if (!"serviceConnection".equals(connectionMode)) {
                return requireNonEmpty(value, "API Token credential is required.");
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillCavServiceConnectionIdItems(@AncestorInPath Item item,
                                                               @QueryParameter String cavServiceConnectionId) {
            StandardListBoxModel result = new StandardListBoxModel();

            if (item == null) {
                return result.includeCurrentValue(cavServiceConnectionId);
            }

            return result
                    .includeEmptyValue()
                    .includeAs(ACL.SYSTEM, item, CavServiceConnection.class)
                    .includeCurrentValue(cavServiceConnectionId);
        }

        public FormValidation doCheckCavServiceConnectionId(@QueryParameter String value, @QueryParameter String connectionMode) {
            if ("serviceConnection".equals(connectionMode)) {
                return requireNonEmpty(value, "Service Connection ID is required.");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckGitIntegrationName(@QueryParameter String value) {
            return requireNonEmpty(value, "Git Integration Name is required.");
        }

        private static FormValidation requireNonEmpty(String value, String message) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error(message);
            }
            return FormValidation.ok();
        }
    }
}
