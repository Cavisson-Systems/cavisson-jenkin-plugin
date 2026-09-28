package com.cavisson.jenkins.artifact_keeper_login;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
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
 * Pipeline equivalent of {@code cavArtifactKeeperLogin}: resolves a docker registry
 * username/token from a Jenkins "Username with password" credential and, when a registry is
 * known, performs the actual {@code docker login} itself - no {@code sh}/{@code docker login}
 * needed in the calling script:
 * <pre>
 *   cavArtifactKeeperLogin(dockerCredentialId: 'my-docker-registry-creds',
 *                          registry: 'local-build.cav-test.com')
 * </pre>
 * {@code registry} is optional: if omitted, this falls back to its original behavior - resolve
 * credentials only and return them as a Map for the caller to log in with itself (backward
 * compatible with scripts written before this step could log in on its own).
 * The token is streamed straight into {@code docker login}'s stdin - it is never placed on a
 * command line or printed to the console, and login failures are reported via
 * {@link hudson.AbortException} without including the token.
 * <p>Does not talk to the Cavisson server at all - credentials come entirely from Jenkins' own
 * credential store.
 * <p>Pipeline-only - there is no Freestyle Builder counterpart for this task.
 */
public class ArtifactKeeperLoginStep extends Step {

    private String dockerCredentialId = "";
    private String registry = "";

    @DataBoundConstructor
    public ArtifactKeeperLoginStep() {
    }

    public String getDockerCredentialId() {
        return dockerCredentialId;
    }

    @DataBoundSetter
    public void setDockerCredentialId(String dockerCredentialId) {
        this.dockerCredentialId = dockerCredentialId;
    }

    public String getRegistry() {
        return registry;
    }

    @DataBoundSetter
    public void setRegistry(String registry) {
        this.registry = registry;
    }

    @Override
    public StepExecutionImpl start(StepContext context) {
        return new StepExecutionImpl(this, context);
    }

    private static final class StepExecutionImpl extends SynchronousNonBlockingStepExecution<Map<String, Object>> {

        private static final long serialVersionUID = 1L;

        private final transient ArtifactKeeperLoginStep step;

        StepExecutionImpl(ArtifactKeeperLoginStep step, StepContext context) {
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

            return ArtifactKeeperLoginExecutor.run(run, workspace, launcher, env, listener,
                    step.getDockerCredentialId(), step.getRegistry());
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "cavArtifactKeeperLogin";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Cavisson - Artifact Keeper Login";
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            return new HashSet<>(Arrays.asList(Run.class, FilePath.class, Launcher.class, TaskListener.class, EnvVars.class));
        }

        @POST
        public ListBoxModel doFillDockerCredentialIdItems(@AncestorInPath Item item,
                                                           @QueryParameter String dockerCredentialId) {
            StandardListBoxModel result = new StandardListBoxModel();

            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(dockerCredentialId);
                }
            } else if (!item.hasPermission(Item.EXTENDED_READ)
                    && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                return result.includeCurrentValue(dockerCredentialId);
            }

            return result
                    .includeEmptyValue()
                    .includeAs(ACL.SYSTEM, item, StandardUsernamePasswordCredentials.class)
                    .includeCurrentValue(dockerCredentialId);
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public FormValidation doCheckDockerCredentialId(@QueryParameter String value) {
            return requireNonEmpty(value, "Docker credential is required.");
        }

        private static FormValidation requireNonEmpty(String value, String message) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error(message);
            }
            return FormValidation.ok();
        }
    }
}
