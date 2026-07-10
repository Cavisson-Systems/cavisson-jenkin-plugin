package com.cavisson.jenkins.stopcodecoverage;

import org.jvnet.hudson.test.JenkinsRule;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Reproduces the real "Save" flow on a Freestyle job's configure page. See CLAUDE.md's Jelly
 * form gotcha: f:radioBlock bundles sibling fields into a wrapped JSON object that does not
 * bind to a plain String property, which is why this config.jelly uses flat f:select/f:textbox
 * fields from the start. This test exists to keep it that way as fields are added.
 */
public class CavissonStopCodeCoverageBuilderConfigRoundTripTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void directModeConfigSurvivesRoundTrip() throws Exception {
        CavissonStopCodeCoverageBuilder before = new CavissonStopCodeCoverageBuilder();
        before.setConnectionMode("direct");
        before.setBaseUrl("https://10.10.70.105:4444");
        before.setApiTokenCredentialId("70-105-pat");
        before.setApplicationName("myApp");

        CavissonStopCodeCoverageBuilder after = j.configRoundtrip(before);

        assertEquals("direct", after.getConnectionMode());
        assertEquals("https://10.10.70.105:4444", after.getBaseUrl());
        assertEquals("70-105-pat", after.getApiTokenCredentialId());
        assertEquals("myApp", after.getApplicationName());
    }

    @Test
    public void serviceConnectionModeConfigSurvivesRoundTrip() throws Exception {
        CavissonStopCodeCoverageBuilder before = new CavissonStopCodeCoverageBuilder();
        before.setConnectionMode("serviceConnection");
        before.setCavServiceConnectionId("my-service-connection");
        before.setApplicationName("myApp");

        CavissonStopCodeCoverageBuilder after = j.configRoundtrip(before);

        assertEquals("serviceConnection", after.getConnectionMode());
        assertEquals("my-service-connection", after.getCavServiceConnectionId());
        assertEquals("myApp", after.getApplicationName());
    }
}
