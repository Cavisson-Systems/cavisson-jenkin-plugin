package com.cavisson.jenkins.pushservice;

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

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Pipeline DSL step {@code pushService(service)}: pushes the named service's already-built Docker
 * image and removes the local copy, replacing the undefined Groovy helper of the same name the
 * reference Jenkinsfile called directly, e.g.
 * <pre>
 *   pushService('frontend')
 * </pre>
 */
public class PushServiceStep extends Step {

    private final String service;

    @DataBoundConstructor
    public PushServiceStep(String service) {
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

        private final transient PushServiceStep step;

        StepExecutionImpl(PushServiceStep step, StepContext context) {
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

            PushServiceExecutor.run(workspace, launcher, listener, env, step.getService());
            return null;
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "pushService";
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Cavisson - Push Service Docker Image";
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            return new HashSet<>(Arrays.asList(FilePath.class, Launcher.class, TaskListener.class, EnvVars.class));
        }

        public FormValidation doCheckService(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("service is required.");
            }
            return FormValidation.ok();
        }
    }
}
