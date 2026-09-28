package com.cavisson.jenkins.analysefailure;

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

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Map;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.verb.POST;

/**
 * Freestyle/general build-step equivalent of the "Cav Codefix Agent" failure-analysis API:
 * analyses either one explicit Test Run (trNumber) or every failing testcase inside a Test Suite
 * Run (tsrNumber). Deliberately has no {@code @Symbol}, so it does not also expose itself as a
 * Pipeline DSL function - {@link AnalyseTestFailureStep} owns the "cavAnalyseTestFailure" Pipeline
 * step (with a return value), while this class covers Freestyle jobs.
 */
public class AnalyseTestFailureBuilder extends Builder implements SimpleBuildStep {

    private String connectionMode = "serviceConnection";
    private String baseUrl = "";
    private String apiTokenCredentialId = "";
    private String cavServiceConnectionId = "";

    private String tsrNumber = "";
    private String trNumber = "";
    private String scenario = "";
    private String project = "";
    private String subProject = "";
    private String userName = "";
    private String workProfileName = "";
    private int concurrency = 1;

    @DataBoundConstructor
    public AnalyseTestFailureBuilder() {
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

    public String getTsrNumber() {
        return tsrNumber;
    }

    @DataBoundSetter
    public void setTsrNumber(String tsrNumber) {
        this.tsrNumber = tsrNumber;
    }

    public String getTrNumber() {
        return trNumber;
    }

    @DataBoundSetter
    public void setTrNumber(String trNumber) {
        this.trNumber = trNumber;
    }

    public String getScenario() {
        return scenario;
    }

    @DataBoundSetter
    public void setScenario(String scenario) {
        this.scenario = scenario;
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

    public String getUserName() {
        return userName;
    }

    @DataBoundSetter
    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getWorkProfileName() {
        return workProfileName;
    }

    @DataBoundSetter
    public void setWorkProfileName(String workProfileName) {
        this.workProfileName = workProfileName;
    }

    public int getConcurrency() {
        return concurrency;
    }

    @DataBoundSetter
    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    @Override
    public void perform(@NonNull Run<?, ?> run,
                         @NonNull FilePath workspace,
                         @NonNull EnvVars env,
                         @NonNull Launcher launcher,
                         @NonNull TaskListener listener) throws InterruptedException, IOException {

        CavissonConnection connection = CavissonConnectionResolver.resolve(
                run, env, connectionMode, baseUrl, apiTokenCredentialId, cavServiceConnectionId);

        Map<String, Object> result = AnalyseTestFailureExecutor.run(run, workspace, env, listener, connection,
                tsrNumber, trNumber, scenario, project, subProject, userName, workProfileName, concurrency);

        listener.getLogger().println("Cavisson Analyse Test Failure finished: " + result.get("analyzedCount")
                + " analysed, " + result.get("completedCount") + " completed, " + result.get("failedCount") + " failed.");
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
            return "Cavisson - Analyse Test Failure";
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
        public FormValidation doCheckTsrNumber(@QueryParameter String value, @QueryParameter String trNumber) {
            return checkExactlyOneOf(value, trNumber);
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckTrNumber(@QueryParameter String value, @QueryParameter String tsrNumber) {
            return checkExactlyOneOf(value, tsrNumber);
        }

        private static FormValidation checkExactlyOneOf(String value, String other) {
            boolean hasValue = value != null && !value.trim().isEmpty();
            boolean hasOther = other != null && !other.trim().isEmpty();
            if (hasValue == hasOther) {
                return FormValidation.error("Provide exactly one of Test Suite Run (tsrNumber) or Test Run (trNumber).");
            }
            return FormValidation.ok();
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckScenario(@QueryParameter String value, @QueryParameter String trNumber) {
            return requireWhenTrNumberUsed(value, trNumber, "Scenario is required when Test Run (trNumber) is used directly.");
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckProject(@QueryParameter String value, @QueryParameter String trNumber) {
            return requireWhenTrNumberUsed(value, trNumber, "Project is required when Test Run (trNumber) is used directly.");
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckSubProject(@QueryParameter String value, @QueryParameter String trNumber) {
            return requireWhenTrNumberUsed(value, trNumber, "Sub Project is required when Test Run (trNumber) is used directly.");
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckUserName(@QueryParameter String value, @QueryParameter String trNumber) {
            return requireWhenTrNumberUsed(value, trNumber, "User Name is required when Test Run (trNumber) is used directly.");
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckWorkProfileName(@QueryParameter String value, @QueryParameter String trNumber) {
            return requireWhenTrNumberUsed(value, trNumber, "Work Profile Name is required when Test Run (trNumber) is used directly.");
        }

        private static FormValidation requireWhenTrNumberUsed(String value, String trNumber, String message) {
            boolean trNumberUsed = trNumber != null && !trNumber.trim().isEmpty();
            if (trNumberUsed) {
                return requireNonEmpty(value, message);
            }
            return FormValidation.ok();
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckConcurrency(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok();
            }
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed < 1 || parsed > 8) {
                    return FormValidation.warning("Concurrency will be clamped to the range 1-8.");
                }
            } catch (NumberFormatException e) {
                return FormValidation.error("Concurrency must be a whole number.");
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
