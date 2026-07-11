package com.cavisson.jenkins.createtestsuite;

import com.cavisson.jenkins.connection.CavissonConnection;
import com.cavisson.jenkins.connection.CavissonConnectionResolver;
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
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Map;

/**
 * Freestyle/general build-step equivalent of the Scenario Service "Create Test Suite" API:
 * creates a functional test suite from testcases matching the given tags. Deliberately has no
 * {@code @Symbol}, so it does not also expose itself as a Pipeline DSL function -
 * {@link CreateTestSuiteStep} owns the "cavissonCreateTestSuite" Pipeline step (with a return
 * value), while this class covers Freestyle jobs.
 */
public class CreateTestSuiteBuilder extends Builder implements SimpleBuildStep {

    private String connectionMode = "direct";
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
    public CreateTestSuiteBuilder() {
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
    public void perform(@Nonnull Run<?, ?> run,
                         @Nonnull FilePath workspaceDir,
                         @Nonnull EnvVars env,
                         @Nonnull Launcher launcher,
                         @Nonnull TaskListener listener) throws InterruptedException, IOException {

        CavissonConnection connection = CavissonConnectionResolver.resolve(
                run, env, connectionMode, baseUrl, apiTokenCredentialId, cavServiceConnectionId);

        Map<String, Object> result = CreateTestSuiteExecutor.run(run, env, listener,
                connection, project, subProject, workspace, profile, name, tags, automatedOnly,
                gitIntegration, commitId, mergeId, codeMappingMode);

        listener.getLogger().println("Test suite created: " + result.get("testsuite"));
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
            return "Cavisson - Create Functional Test Suite";
        }

        public ListBoxModel doFillConnectionModeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Direct (Base URL + API Token)", "direct");
            items.add("Existing Service Connection", "serviceConnection");
            return items;
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

        public FormValidation doCheckCavServiceConnectionId(@QueryParameter String value, @QueryParameter String connectionMode) {
            if ("serviceConnection".equals(connectionMode)) {
                return requireNonEmpty(value, "Service Connection ID is required.");
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillCodeMappingModeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Default (package + class + method)", "");
            items.add("Package + Class + Method (matchPCM)", "matchPCM");
            items.add("Package + Class (matchPC)", "matchPC");
            items.add("Package Only (matchP)", "matchP");
            return items;
        }

        public FormValidation doCheckTags(@QueryParameter String value,
                                           @QueryParameter String gitIntegration,
                                           @QueryParameter String commitId,
                                           @QueryParameter String mergeId) {
            boolean hasTags = value != null && !value.trim().isEmpty();
            boolean hasDiffSource = gitIntegration != null && !gitIntegration.trim().isEmpty()
                    && ((commitId != null && !commitId.trim().isEmpty())
                            || (mergeId != null && !mergeId.trim().isEmpty()));
            if (!hasTags && !hasDiffSource) {
                return FormValidation.error("Provide tags, or a Git Integration with a Commit ID/Merge ID.");
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
