package com.cavisson.jenkins.runtest;

import com.cavisson.jenkins.connection.CavissonConnection;
import com.cavisson.jenkins.connection.CavissonConnectionResolver;
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
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Map;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.verb.POST;

/**
 * Freestyle/general build-step equivalent of the ADO "CavissonRunTest" task: triggers a
 * Cavisson TestSuite or Load Test scenario and waits for it to finish. Deliberately has no
 * {@code @Symbol}, so it does not also expose itself as a Pipeline DSL function -
 * {@link CavissonRunTestStep} owns the "cavissonRunTest" Pipeline step (with a return value),
 * while this class covers Freestyle jobs.
 */
public class CavissonRunTestBuilder extends Builder implements SimpleBuildStep {

    private String testType = "TestSuite";

    private String connectionMode = "serviceConnection";
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
    public void perform(@NonNull Run<?, ?> run,
                         @NonNull FilePath workspace,
                         @NonNull EnvVars env,
                         @NonNull Launcher launcher,
                         @NonNull TaskListener listener) throws InterruptedException, IOException {

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

        @NonNull
        @Override
        public String getDisplayName() {
            return "Cavisson - Run Test";
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public ListBoxModel doFillTestTypeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Test Suite", "TestSuite");
            items.add("Load Test", "LoadTest");
            return items;
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

        @POST
        public ListBoxModel doFillCavServiceConnectionIdItems(@AncestorInPath Item item,
                                                               @QueryParameter String cavServiceConnectionId) {
            StandardListBoxModel result = new StandardListBoxModel();

            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(cavServiceConnectionId);
                }
            } else if (!item.hasPermission(Item.EXTENDED_READ)
                    && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                return result.includeCurrentValue(cavServiceConnectionId);
            }

            return result
                    .includeEmptyValue()
                    .includeAs(ACL.SYSTEM, item, CavServiceConnection.class)
                    .includeCurrentValue(cavServiceConnectionId);
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckCavServiceConnectionId(@QueryParameter String value, @QueryParameter String connectionMode) {
            if ("serviceConnection".equals(connectionMode)) {
                return requireNonEmpty(value, "Service Connection ID is required.");
            }
            return FormValidation.ok();
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckProject(@QueryParameter String value) {
            return requireNonEmpty(value, "Project is required.");
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckSubProject(@QueryParameter String value) {
            return requireNonEmpty(value, "Sub Project is required.");
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckUsername(@QueryParameter String value) {
            return requireNonEmpty(value, "User Name is required.");
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckTestSuiteName(@QueryParameter String value, @QueryParameter String testType) {
            if ("TestSuite".equals(testType)) {
                return requireNonEmpty(value, "TestSuite Name is required when Test Type is Test Suite.");
            }
            return FormValidation.ok();
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
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
