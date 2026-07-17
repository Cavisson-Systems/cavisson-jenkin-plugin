package com.cavisson.jenkins.security;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Real Pipeline Step wrapper around {@link CavSecurityPipelineBuilder}'s scan logic, registered
 * under the same {@code @Symbol("cavSecurityPlugin")} function name the classic Builder used to
 * own (see CavSecurityPipelineBuilder.DescriptorImpl, which no longer carries @Symbol so this is
 * the sole Pipeline-visible provider of that function name). Unlike the classic Builder + generic
 * step-wrapping metastep path (whose perform() returns void, so `def r = cavSecurityPlugin(...)`
 * always yielded null), this Step's StepExecution returns the scan result
 * Map&lt;String,Object&gt; (success/status/scanId/reportUrl/message) directly to the Jenkinsfile.
 * The generic `step([$class: 'CavSecurityPipelineBuilder', ...])` form still returns null — that
 * is Jenkins core's own generic wrapping and is not something a plugin can change.
 *
 * All configuration fields are forwarded to a {@link CavSecurityPipelineBuilder} delegate instead
 * of being duplicated here, so the ~30 fields and their resolution logic (field aliasing,
 * defaults, validation) stay defined in exactly one place.
 */
public class CavSecurityPipelineStep extends Step {

    private final CavSecurityPipelineBuilder delegate;

    @DataBoundConstructor
    public CavSecurityPipelineStep(@Nonnull String scanType) {
        this.delegate = new CavSecurityPipelineBuilder(scanType);
    }

    public String getCavScanServiceConnection() {
        return delegate.getCavScanServiceConnection();
    }

    @DataBoundSetter
    public void setCavScanServiceConnection(String cavScanServiceConnection) {
        delegate.setCavScanServiceConnection(cavScanServiceConnection);
    }

    public String getScanType() {
        return delegate.getScanType();
    }

    public String getConnectionMode() {
        return delegate.getConnectionMode();
    }

    @DataBoundSetter
    public void setConnectionMode(String connectionMode) {
        delegate.setConnectionMode(connectionMode);
    }

    public String getBaseUrl() {
        return delegate.getBaseUrl();
    }

    @DataBoundSetter
    public void setBaseUrl(String baseUrl) {
        delegate.setBaseUrl(baseUrl);
    }

    public String getApiTokenCredentialId() {
        return delegate.getApiTokenCredentialId();
    }

    @DataBoundSetter
    public void setApiTokenCredentialId(String apiTokenCredentialId) {
        delegate.setApiTokenCredentialId(apiTokenCredentialId);
    }

    public String getProject() {
        return delegate.getProject();
    }

    @DataBoundSetter
    public void setProject(String project) {
        delegate.setProject(project);
    }

    public String getTargetPath() {
        return delegate.getTargetPath();
    }

    @DataBoundSetter
    public void setTargetPath(String targetPath) {
        delegate.setTargetPath(targetPath);
    }

    public String getQualityGate() {
        return delegate.getQualityGate();
    }

    @DataBoundSetter
    public void setQualityGate(String qualityGate) {
        delegate.setQualityGate(qualityGate);
    }

    public String getQualityGateTimeout() {
        return delegate.getQualityGateTimeout();
    }

    @DataBoundSetter
    public void setQualityGateTimeout(String qualityGateTimeout) {
        delegate.setQualityGateTimeout(qualityGateTimeout);
    }

    public String getTrivyMode() {
        return delegate.getTrivyMode();
    }

    @DataBoundSetter
    public void setTrivyMode(String trivyMode) {
        delegate.setTrivyMode(trivyMode);
    }

    public String getTrivyTarget() {
        return delegate.getTrivyTarget();
    }

    @DataBoundSetter
    public void setTrivyTarget(String trivyTarget) {
        delegate.setTrivyTarget(trivyTarget);
    }

    public String getTrivyReportDir() {
        return delegate.getTrivyReportDir();
    }

    @DataBoundSetter
    public void setTrivyReportDir(String trivyReportDir) {
        delegate.setTrivyReportDir(trivyReportDir);
    }

    public String getScaMode() {
        return delegate.getScaMode();
    }

    @DataBoundSetter
    public void setScaMode(String scaMode) {
        delegate.setScaMode(scaMode);
    }

    public String getScaTarget() {
        return delegate.getScaTarget();
    }

    @DataBoundSetter
    public void setScaTarget(String scaTarget) {
        delegate.setScaTarget(scaTarget);
    }

    public String getScaReportDir() {
        return delegate.getScaReportDir();
    }

    @DataBoundSetter
    public void setScaReportDir(String scaReportDir) {
        delegate.setScaReportDir(scaReportDir);
    }

    public String getScaRegistryUrl() {
        return delegate.getScaRegistryUrl();
    }

    @DataBoundSetter
    public void setScaRegistryUrl(String scaRegistryUrl) {
        delegate.setScaRegistryUrl(scaRegistryUrl);
    }

    public String getScaRepository() {
        return delegate.getScaRepository();
    }

    @DataBoundSetter
    public void setScaRepository(String scaRepository) {
        delegate.setScaRepository(scaRepository);
    }

    public String getScaImageName() {
        return delegate.getScaImageName();
    }

    @DataBoundSetter
    public void setScaImageName(String scaImageName) {
        delegate.setScaImageName(scaImageName);
    }

    public String getScaImageTag() {
        return delegate.getScaImageTag();
    }

    @DataBoundSetter
    public void setScaImageTag(String scaImageTag) {
        delegate.setScaImageTag(scaImageTag);
    }

    public String getContainerClusterName() {
        return delegate.getContainerClusterName();
    }

    @DataBoundSetter
    public void setContainerClusterName(String containerClusterName) {
        delegate.setContainerClusterName(containerClusterName);
    }

    public String getContainerNamespace() {
        return delegate.getContainerNamespace();
    }

    @DataBoundSetter
    public void setContainerNamespace(String containerNamespace) {
        delegate.setContainerNamespace(containerNamespace);
    }

    public String getContainerTargetUrl() {
        return delegate.getContainerTargetUrl();
    }

    @DataBoundSetter
    public void setContainerTargetUrl(String containerTargetUrl) {
        delegate.setContainerTargetUrl(containerTargetUrl);
    }

    public String getRunMode() {
        return delegate.getRunMode();
    }

    @DataBoundSetter
    public void setRunMode(String runMode) {
        delegate.setRunMode(runMode);
    }

    public String getZapMode() {
        return delegate.getZapMode();
    }

    @DataBoundSetter
    public void setZapMode(String zapMode) {
        delegate.setZapMode(zapMode);
    }

    public String getZapTarget() {
        return delegate.getZapTarget();
    }

    @DataBoundSetter
    public void setZapTarget(String zapTarget) {
        delegate.setZapTarget(zapTarget);
    }

    public String getZapApiFormat() {
        return delegate.getZapApiFormat();
    }

    @DataBoundSetter
    public void setZapApiFormat(String zapApiFormat) {
        delegate.setZapApiFormat(zapApiFormat);
    }

    public String getZapReportDir() {
        return delegate.getZapReportDir();
    }

    @DataBoundSetter
    public void setZapReportDir(String zapReportDir) {
        delegate.setZapReportDir(zapReportDir);
    }

    public String getDastMode() {
        return delegate.getDastMode();
    }

    @DataBoundSetter
    public void setDastMode(String dastMode) {
        delegate.setDastMode(dastMode);
    }

    public String getDastTarget() {
        return delegate.getDastTarget();
    }

    @DataBoundSetter
    public void setDastTarget(String dastTarget) {
        delegate.setDastTarget(dastTarget);
    }

    public String getDastApiFormat() {
        return delegate.getDastApiFormat();
    }

    @DataBoundSetter
    public void setDastApiFormat(String dastApiFormat) {
        delegate.setDastApiFormat(dastApiFormat);
    }

    public String getDastReportDir() {
        return delegate.getDastReportDir();
    }

    @DataBoundSetter
    public void setDastReportDir(String dastReportDir) {
        delegate.setDastReportDir(dastReportDir);
    }

    public String getDynamicClusterName() {
        return delegate.getDynamicClusterName();
    }

    @DataBoundSetter
    public void setDynamicClusterName(String dynamicClusterName) {
        delegate.setDynamicClusterName(dynamicClusterName);
    }

    public String getDynamicNamespace() {
        return delegate.getDynamicNamespace();
    }

    @DataBoundSetter
    public void setDynamicNamespace(String dynamicNamespace) {
        delegate.setDynamicNamespace(dynamicNamespace);
    }

    public String getTargetUrl() {
        return delegate.getTargetUrl();
    }

    @DataBoundSetter
    public void setTargetUrl(String targetUrl) {
        delegate.setTargetUrl(targetUrl);
    }

    public String getDataSourceName() {
        return delegate.getDataSourceName();
    }

    @DataBoundSetter
    public void setDataSourceName(String dataSourceName) {
        delegate.setDataSourceName(dataSourceName);
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, delegate);
    }

    private static final class Execution extends SynchronousNonBlockingStepExecution<Map<String, Object>> {

        private static final long serialVersionUID = 1L;

        // Not transient: StepExecutions can be serialized into program.dat for pipeline
        // durability, and this delegate must survive that round-trip.
        private final CavSecurityPipelineBuilder delegate;

        Execution(StepContext context, CavSecurityPipelineBuilder delegate) {
            super(context);
            this.delegate = delegate;
        }

        @Override
        protected Map<String, Object> run() throws Exception {
            StepContext context = getContext();
            Run<?, ?> run = context.get(Run.class);
            FilePath workspace = context.get(FilePath.class);
            EnvVars env = context.get(EnvVars.class);
            Launcher launcher = context.get(Launcher.class);
            TaskListener listener = context.get(TaskListener.class);
            return delegate.run(run, workspace, env, launcher, listener);
        }
    }

    @Symbol("cavSecurityPlugin")
    @Extension(optional = true)
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public Set<Class<?>> getRequiredContext() {
            Set<Class<?>> context = new HashSet<>();
            Collections.addAll(context, Run.class, FilePath.class, Launcher.class, TaskListener.class, EnvVars.class);
            return context;
        }

        @Override
        public String getFunctionName() {
            return "cavSecurityPlugin";
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "CavSecurityPlugin";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return false;
        }
    }
}
