package com.cavisson.jenkins.buildservice;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Pipeline DSL step {@code buildService(service)}: builds the named service's Docker image,
 * replacing the undefined Groovy helper of the same name the reference Jenkinsfile called
 * directly, e.g.
 * <pre>
 *   buildService('frontend')
 * </pre>
 * Has exactly one mandatory parameter, so it supports the bare single-argument call form above,
 * not just {@code buildService(service: 'frontend')}.
 */
public class BuildServiceStep extends Step {

    private final String service;

    @DataBoundConstructor
    public BuildServiceStep(String service) {
        this.service = service;
    }

    public String getService() {
        return service;
    }

    @Override
    public StepExecutionImpl start(StepContext context) {
        return new StepExecutionImpl(this, context);
    }

    private static final class StepExecutionImpl extends SynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1L;

        private final transient BuildServiceStep step;

        StepExecutionImpl(BuildServiceStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        protected Void run() throws Exception {
            StepContext context = getContext();
            FilePath workspace = context.get(FilePath.class);
            Launcher launcher = context.get(Launcher.class);
            TaskListener listener = context.get(TaskListener.class);
            EnvVars env = context.get(EnvVars.class);

            BuildServiceExecutor.run(workspace, launcher, listener, env, step.getService());
            return null;
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "buildService";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Cavisson - Build Service Docker Image";
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            return new HashSet<>(Arrays.asList(FilePath.class, Launcher.class, TaskListener.class, EnvVars.class));
        }

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"}) // side-effect-free form helper, exposes no data
        public hudson.util.FormValidation doCheckService(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return hudson.util.FormValidation.error("service is required.");
            }
            return hudson.util.FormValidation.ok();
        }
    }
}
