package com.cavisson.jenkins.env;

import hudson.EnvVars;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.TaskListener;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Verifies the actual end-to-end wiring: a value published via
 * {@link CavissonEnvironmentPublisher#publish} is visible through {@code Run#getEnvironment},
 * the same call every later build/Pipeline step uses to read environment variables. This is the
 * mechanism both CavissonRunTest (CAV_TSR_*) and CreateTestSuite (CAV_NEW_TESTSUITE_NAME) rely on.
 */
public class CavissonEnvironmentContributorTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void publishedVarsAreExposedThroughRunGetEnvironment() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        FreeStyleBuild build = j.buildAndAssertSuccess(project);

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("CAV_TSR_NUMBER", "1059");
        vars.put("CAV_TSR_STATUS", "pass");
        vars.put("CAV_TSR_REPORT_URL", "https://example.com/report?tsr=1059");
        CavissonEnvironmentPublisher.publish(build, vars);

        EnvVars env = build.getEnvironment(TaskListener.NULL);
        assertEquals("1059", env.get("CAV_TSR_NUMBER"));
        assertEquals("pass", env.get("CAV_TSR_STATUS"));
        assertEquals("https://example.com/report?tsr=1059", env.get("CAV_TSR_REPORT_URL"));
    }

    @Test
    public void multiplePublishCallsOnTheSameRunAreAllMerged() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        FreeStyleBuild build = j.buildAndAssertSuccess(project);

        Map<String, String> runVars = new LinkedHashMap<>();
        runVars.put("CAV_TSR_NUMBER", "42");
        CavissonEnvironmentPublisher.publish(build, runVars);

        Map<String, String> suiteVars = new LinkedHashMap<>();
        suiteVars.put("CAV_NEW_TESTSUITE_NAME", "myProject/mySubProject/nightly_regression_suite");
        CavissonEnvironmentPublisher.publish(build, suiteVars);

        EnvVars env = build.getEnvironment(TaskListener.NULL);
        assertEquals("42", env.get("CAV_TSR_NUMBER"));
        assertEquals("myProject/mySubProject/nightly_regression_suite", env.get("CAV_NEW_TESTSUITE_NAME"));
    }
}
