package com.cavisson.jenkins.createtestsuite;

import com.cavisson.jenkins.connection.CavissonConnection;
import com.cavisson.jenkins.connection.CavissonConnectionResolver;
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

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.verb.POST;

/**
 * Pipeline equivalent of the Scenario Service "Create Test Suite" API, exposed as the
 * {@code cavissonCreateTestSuite} step. Unlike {@link CreateTestSuiteBuilder} (used for
 * Freestyle jobs), this returns the result ({@code status}, {@code testsuite}) as a Map so
 * Pipeline scripts can do: {@code def result = cavissonCreateTestSuite(...); echo result.testsuite}.
 */
public class CreateTestSuiteStep extends Step {

    private String connectionMode = "serviceConnection";
    private String baseUrl = "";
    private String apiTokenCredentialId = "";
    private String cavServiceConnectionId = "";

    private String project = "default";
    private String subProject = "default";
    private String workspace = "admin";
    private String profile = "system";
    private String name = "";
    private String tags = "";
    private boolean automatedOnly = true;
    private String gitIntegration = "";
    private String commitId = "";
    private String mergeId = "";
    private String codeMappingMode = "";

    @DataBoundConstructor
    public CreateTestSuiteStep() {
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

    public String getProject() {
        return project;
    }

    @DataBoundSetter
    public void setProject(String project) {
        this.project = project;
    }

    public String getSubProject() {
        return subProject;
    }

    @DataBoundSetter
    public void setSubProject(String subProject) {
        this.subProject = subProject;
    }

    public String getWorkspace() {
        return workspace;
    }

    @DataBoundSetter
    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public String getProfile() {
        return profile;
    }

    @DataBoundSetter
    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getName() {
        return name;
    }

    @DataBoundSetter
    public void setName(String name) {
        this.name = name;
    }

    public String getTags() {
        return tags;
    }

    @DataBoundSetter
    public void setTags(String tags) {
        this.tags = tags;
    }

    public boolean isAutomatedOnly() {
        return automatedOnly;
    }

    @DataBoundSetter
    public void setAutomatedOnly(boolean automatedOnly) {
        this.automatedOnly = automatedOnly;
    }

    public String getGitIntegration() {
        return gitIntegration;
    }

    @DataBoundSetter
    public void setGitIntegration(String gitIntegration) {
        this.gitIntegration = gitIntegration;
    }

    public String getCommitId() {
        return commitId;
    }

    @DataBoundSetter
    public void setCommitId(String commitId) {
        this.commitId = commitId;
    }

    public String getMergeId() {
        return mergeId;
    }

    @DataBoundSetter
    public void setMergeId(String mergeId) {
        this.mergeId = mergeId;
    }

    public String getCodeMappingMode() {
        return codeMappingMode;
    }

    @DataBoundSetter
    public void setCodeMappingMode(String codeMappingMode) {
        this.codeMappingMode = codeMappingMode;
    }

    @Override
    public StepExecutionImpl start(StepContext context) {
        return new StepExecutionImpl(this, context);
    }

    private static final class StepExecutionImpl extends SynchronousNonBlockingStepExecution<Map<String, Object>> {

        private static final long serialVersionUID = 1L;

        private final transient CreateTestSuiteStep step;

        StepExecutionImpl(CreateTestSuiteStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        protected Map<String, Object> run() throws Exception {
            StepContext context = getContext();
            Run<?, ?> run = context.get(Run.class);
            TaskListener listener = context.get(TaskListener.class);
            EnvVars env = context.get(EnvVars.class);
            // Optional (not in getRequiredContext): only present inside node{}, used to
            // auto-detect the Merge ID from the workspace's latest merge commit.
            FilePath workspaceDir = context.get(FilePath.class);
            Launcher launcher = context.get(Launcher.class);

            CavissonConnection connection = CavissonConnectionResolver.resolve(run, env, step.getConnectionMode(),
                    step.getBaseUrl(), step.getApiTokenCredentialId(), step.getCavServiceConnectionId());

            return CreateTestSuiteExecutor.run(run, env, listener, workspaceDir, launcher, connection, step.getProject(), step.getSubProject(),
                    step.getWorkspace(), step.getProfile(), step.getName(), step.getTags(), step.isAutomatedOnly(),
                    step.getGitIntegration(), step.getCommitId(), step.getMergeId(), step.getCodeMappingMode());
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "cavissonCreateTestSuite";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Cavisson - Create Functional Test Suite";
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
        public ListBoxModel doFillCodeMappingModeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Default (package + class + method)", "");
            items.add("Package + Class + Method (matchPCM)", "matchPCM");
            items.add("Package + Class (matchPC)", "matchPC");
            items.add("Package Only (matchP)", "matchP");
            return items;
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckTags(@QueryParameter String value,
                                           @QueryParameter String gitIntegration,
                                           @QueryParameter String commitId,
                                           @QueryParameter String mergeId) {
            boolean hasTags = value != null && !value.trim().isEmpty();
            boolean hasDiffSource = gitIntegration != null && !gitIntegration.trim().isEmpty()
                    && ((commitId != null && !commitId.trim().isEmpty())
                            || (mergeId != null && !mergeId.trim().isEmpty()));
            if (!hasTags && !hasDiffSource && (gitIntegration == null || gitIntegration.trim().isEmpty())) {
                return FormValidation.error("Provide tags, or a Git Integration with a Commit ID/Merge ID.");
            }
            boolean hasGitIntegration = gitIntegration != null && !gitIntegration.trim().isEmpty();
            if (!hasTags && hasGitIntegration && !hasDiffSource) {
                return FormValidation.ok("No Commit ID/Merge ID given: the Merge ID will be auto-detected"
                        + " from the latest merge commit in the workspace.");
            }
            return FormValidation.ok();
        }

        private static FormValidation requireNonEmpty(String value, String message) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error(message);
            }
            return FormValidation.ok();
        }
    }
}
