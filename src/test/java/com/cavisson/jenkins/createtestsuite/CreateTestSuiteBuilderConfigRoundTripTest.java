package com.cavisson.jenkins.createtestsuite;

import org.jvnet.hudson.test.JenkinsRule;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Reproduces the real "Save" flow on a Freestyle job's configure page. See CLAUDE.md's Jelly
 * form gotcha: f:radioBlock bundles sibling fields into a wrapped JSON object that does not
 * bind to a plain String property (constructor or setter), which crashed CavissonRunTestBuilder
 * twice before f:select was used instead. This config.jelly uses flat f:select/f:textbox
 * fields from the start, but this test exists to keep it that way as fields are added.
 */
public class CreateTestSuiteBuilderConfigRoundTripTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void directModeConfigSurvivesRoundTrip() throws Exception {
        CreateTestSuiteBuilder before = new CreateTestSuiteBuilder();
        before.setConnectionMode("direct");
        before.setBaseUrl("https://10.10.70.105:4444");
        before.setApiTokenCredentialId("70-105-pat");
        before.setProject("AI");
        before.setSubProject("demo");
        before.setTags("smoke,regression");
        before.setName("nightly_regression_suite");
        before.setWorkspace("admin");
        before.setProfile("system");
        before.setAutomatedOnly(false);

        CreateTestSuiteBuilder after = j.configRoundtrip(before);

        assertEquals("direct", after.getConnectionMode());
        assertEquals("https://10.10.70.105:4444", after.getBaseUrl());
        assertEquals("70-105-pat", after.getApiTokenCredentialId());
        assertEquals("AI", after.getProject());
        assertEquals("demo", after.getSubProject());
        assertEquals("smoke,regression", after.getTags());
        assertEquals("nightly_regression_suite", after.getName());
        assertEquals("admin", after.getWorkspace());
        assertEquals("system", after.getProfile());
        assertFalse(after.isAutomatedOnly());
    }

    @Test
    public void serviceConnectionModeConfigSurvivesRoundTrip() throws Exception {
        CreateTestSuiteBuilder before = new CreateTestSuiteBuilder();
        before.setConnectionMode("serviceConnection");
        before.setCavServiceConnectionId("my-service-connection");
        before.setTags("api");

        CreateTestSuiteBuilder after = j.configRoundtrip(before);

        assertEquals("serviceConnection", after.getConnectionMode());
        assertEquals("my-service-connection", after.getCavServiceConnectionId());
        assertEquals("api", after.getTags());
    }
}
