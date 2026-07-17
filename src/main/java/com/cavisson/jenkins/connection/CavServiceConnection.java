package com.cavisson.jenkins.connection;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.Base64;

/**
 * Single reusable Cavisson Dashboard Server connection credential, shared by every task in this
 * plugin: CavissonRunTest/CreateTestSuite/AnalyseTestFailure/StartCodeCoverage/StopCodeCoverage
 * (via CavissonConnectionResolver's "serviceConnection" mode), the security scan task, the
 * quality gate task, and the AI Test Case Generation task. Previously each of the latter three
 * defined its own near-identical (or, for AI Test Case, a superset) credential type; unifying
 * them means one credential to create per Dashboard Server instead of up to three.
 *
 * <p>{@code publishUserStories}/{@code integrationName}/{@code publishTrackerType}/
 * {@code publishEpicName}/{@code gitProvider}/{@code gitUsername}/{@code gitCredential} are
 * AI Test Case-specific and optional - unused (left blank) by every other task.
 */
public class CavServiceConnection extends BaseStandardCredentials implements StandardCredentials {

    private final String baseUrl;
    private final Secret apiToken;

    private boolean publishUserStories;
    private String integrationName = "";
    private String publishTrackerType = "";
    private String publishEpicName = "";
    private String gitProvider = "";
    private String gitUsername = "";
    private Secret gitCredential;

    @DataBoundConstructor
    public CavServiceConnection(@CheckForNull CredentialsScope scope,
                                 @CheckForNull String id,
                                 @CheckForNull String description,
                                 @Nonnull String baseUrl,
                                 @Nonnull Secret apiToken) {
        super(scope, id, description);
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.apiToken = apiToken;
    }

    @Nonnull
    public String getBaseUrl() {
        return baseUrl;
    }

    @Nonnull
    public Secret getApiToken() {
        return apiToken;
    }

    @DataBoundSetter
    public void setPublishUserStories(boolean publishUserStories) {
        this.publishUserStories = publishUserStories;
    }

    @DataBoundSetter
    public void setIntegrationName(String integrationName) {
        this.integrationName = integrationName == null ? "" : integrationName.trim();
    }

    @DataBoundSetter
    public void setPublishTrackerType(String publishTrackerType) {
        this.publishTrackerType = publishTrackerType == null ? "" : publishTrackerType.trim();
    }

    @DataBoundSetter
    public void setPublishEpicName(String publishEpicName) {
        this.publishEpicName = publishEpicName == null ? "" : publishEpicName.trim();
    }

    @DataBoundSetter
    public void setGitProvider(String gitProvider) {
        this.gitProvider = gitProvider == null ? "" : gitProvider.trim();
    }

    @DataBoundSetter
    public void setGitUsername(String gitUsername) {
        this.gitUsername = gitUsername == null ? "" : gitUsername.trim();
    }

    @DataBoundSetter
    public void setGitCredential(Secret gitCredential) {
        this.gitCredential = gitCredential;
    }

    public boolean isPublishUserStories() {
        return publishUserStories;
    }

    public String getIntegrationName() {
        return integrationName;
    }

    public String getPublishTrackerType() {
        return publishTrackerType;
    }

    public String getPublishEpicName() {
        return publishEpicName;
    }

    public String getGitProvider() {
        return gitProvider;
    }

    public String getGitUsername() {
        return gitUsername;
    }

    /** Returns the Git PAT or SSH key in plain text. Use only in git operations. */
    public String getGitCredential() {
        return gitCredential == null ? "" : gitCredential.getPlainText();
    }

    @Extension
    public static class DescriptorImpl extends BaseStandardCredentials.BaseStandardCredentialsDescriptor {

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Cavisson Service Connection";
        }

        public FormValidation doCheckBaseUrl(@QueryParameter String baseUrl) {
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                return FormValidation.error("API Base URL is required. Example: https://demo-cicd.cav-test.com:4444");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckApiToken(@QueryParameter Secret apiToken) {
            if (apiToken == null || apiToken.getPlainText().trim().isEmpty()) {
                return FormValidation.error("API Token is required.");
            }
            String raw = apiToken.getPlainText().trim();
            if (!raw.contains("[") && !raw.contains("{")) {
                return raw.length() >= 8
                        ? FormValidation.ok()
                        : FormValidation.warning("Token looks short. Verify it is correct.");
            }
            try {
                byte[] decoded = Base64.getDecoder().decode(
                        raw.replaceAll("\\s", "").replace('-', '+').replace('_', '/'));
                String json = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                if (json.trim().startsWith("[") && json.contains("cavToken")) {
                    return FormValidation.ok("Valid Cavisson token format.");
                }
                return FormValidation.warning("Token decoded but did not match expected Cavisson format.");
            } catch (IllegalArgumentException e) {
                return raw.length() >= 8
                        ? FormValidation.ok()
                        : FormValidation.warning("Token appears invalid. Please verify.");
            }
        }

        @POST
        public FormValidation doCheckIntegrationName(
                @QueryParameter String integrationName,
                @QueryParameter boolean publishUserStories) {
            if (publishUserStories && (integrationName == null || integrationName.trim().isEmpty())) {
                return FormValidation.error("Integration Name is required when Publish User Stories is enabled.");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckPublishEpicName(
                @QueryParameter String publishEpicName,
                @QueryParameter boolean publishUserStories) {
            if (publishUserStories && (publishEpicName == null || publishEpicName.trim().isEmpty())) {
                return FormValidation.error("Publish Epic Name is required when Publish User Stories is enabled.");
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillPublishTrackerTypeItems() {
            ListBoxModel m = new ListBoxModel();
            m.add("-- Select --", "");
            m.add("JIRA", "JIRA");
            m.add("AZURE", "AZURE");
            return m;
        }

        public ListBoxModel doFillGitProviderItems() {
            ListBoxModel m = new ListBoxModel();
            m.add("-- Select (optional) --", "");
            m.add("GitHub", "GITHUB");
            m.add("GitLab", "GITLAB");
            m.add("Azure Repos", "AZURE_REPOS");
            return m;
        }

        @POST
        public FormValidation doCheckGitUsername(
                @QueryParameter String gitUsername,
                @QueryParameter String gitCredential) {
            boolean hasCredential = gitCredential != null && !gitCredential.trim().isEmpty();
            boolean hasUsername = gitUsername != null && !gitUsername.trim().isEmpty();
            if (hasCredential && !hasUsername) {
                return FormValidation.warning(
                        "A PAT is set but no Git Username is provided. "
                        + "Username is required for private HTTPS repositories.");
            }
            return FormValidation.ok();
        }
    }
}
