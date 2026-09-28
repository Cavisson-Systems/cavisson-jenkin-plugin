package com.cavisson.jenkins.discoverservices;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pipeline DSL step {@code discoverServices()}: auto-discovers service names from Dockerfile
 * locations under {@code src/*}, replacing the undefined Groovy helper of the same name the
 * reference Jenkinsfile called directly, e.g.
 * <pre>
 *   def services = discoverServices()
 * </pre>
 */
public class DiscoverServicesStep extends Step {

    @DataBoundConstructor
    public DiscoverServicesStep() {
    }

    @Override
    public StepExecutionImpl start(StepContext context) {
        return new StepExecutionImpl(context);
    }

    private static final class StepExecutionImpl extends SynchronousNonBlockingStepExecution<List<String>> {

        private static final long serialVersionUID = 1L;

        StepExecutionImpl(StepContext context) {
            super(context);
        }

        @Override
        protected List<String> run() throws Exception {
            StepContext context = getContext();
            FilePath workspace = context.get(FilePath.class);
            TaskListener listener = context.get(TaskListener.class);
            EnvVars env = context.get(EnvVars.class);

            return DiscoverServicesExecutor.run(workspace, listener, env);
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "discoverServices";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Cavisson - Discover Services";
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            return new HashSet<>(Arrays.asList(FilePath.class, TaskListener.class, EnvVars.class));
        }
    }
}
