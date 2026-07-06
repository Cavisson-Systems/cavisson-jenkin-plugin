package com.cavisson.jenkins.runtest;

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
 * Freestyle/general build-step equivalent of the ADO "CavissonRunTest" task: triggers a
 * Cavisson TestSuite or Load Test scenario and waits for it to finish. Deliberately has no
 * {@code @Symbol}, so it does not also expose itself as a Pipeline DSL function -
 * {@link CavissonRunTestStep} owns the "cavissonRunTest" Pipeline step (with a return value),
 * while this class covers Freestyle jobs.
 */
public class CavissonRunTestBuilder extends Builder implements SimpleBuildStep {

    private String testType = "TestSuite";

    private String connectionMode = "direct";
    private String baseUrl = "";
    private String apiTokenCredentialId = "";
    private String cavServiceConnectionId = "";

    private String project = "default";
    private String subProject = "default";
    private String username = "Cavisson";
    private String profile = "default";
    private String testSuiteName = "";
    private String scenarioName = "";

    @DataBoundConstructor
    public CavissonRunTestBuilder() {
    }

    public String getTestType() {
        return testType;
    }

    @DataBoundSetter
    public void setTestType(String testType) {
        this.testType = "LoadTest".equalsIgnoreCase(testType) ? "LoadTest" : "TestSuite";
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

    public String getUsername() {
        return username;
    }

    @DataBoundSetter
    public void setUsername(String username) {
        this.username = username;
    }

    public String getProfile() {
        return profile;
    }

    @DataBoundSetter
    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getTestSuiteName() {
        return testSuiteName;
    }

    @DataBoundSetter
    public void setTestSuiteName(String testSuiteName) {
        this.testSuiteName = testSuiteName;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    @DataBoundSetter
    public void setScenarioName(String scenarioName) {
        this.scenarioName = scenarioName;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run,
                         @Nonnull FilePath workspace,
                         @Nonnull EnvVars env,
                         @Nonnull Launcher launcher,
                         @Nonnull TaskListener listener) throws InterruptedException, IOException {

        CavissonConnection connection = CavissonConnectionResolver.resolve(
                run, env, connectionMode, baseUrl, apiTokenCredentialId, cavServiceConnectionId);

        Map<String, Object> result = CavissonRunTestExecutor.run(run, workspace, launcher, env, listener,
                connection, testType, project, subProject, username, profile, testSuiteName, scenarioName);

        listener.getLogger().println("Cavisson test finished with status: " + result.get("testStatus"));
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
            return "Cavisson - Run Test";
        }

        public ListBoxModel doFillTestTypeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Test Suite", "TestSuite");
            items.add("Load Test", "LoadTest");
            return items;
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

        public FormValidation doCheckProject(@QueryParameter String value) {
            return requireNonEmpty(value, "Project is required.");
        }

        public FormValidation doCheckSubProject(@QueryParameter String value) {
            return requireNonEmpty(value, "Sub Project is required.");
        }

        public FormValidation doCheckUsername(@QueryParameter String value) {
            return requireNonEmpty(value, "User Name is required.");
        }

        public FormValidation doCheckTestSuiteName(@QueryParameter String value, @QueryParameter String testType) {
            if ("TestSuite".equals(testType)) {
                return requireNonEmpty(value, "TestSuite Name is required when Test Type is Test Suite.");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckScenarioName(@QueryParameter String value, @QueryParameter String testType) {
            if ("LoadTest".equals(testType)) {
                return requireNonEmpty(value, "Test Name is required when Test Type is Load Test.");
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
