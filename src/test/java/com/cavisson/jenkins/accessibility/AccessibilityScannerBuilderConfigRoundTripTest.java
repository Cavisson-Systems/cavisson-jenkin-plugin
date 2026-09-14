package com.cavisson.jenkins.accessibility;

import org.jvnet.hudson.test.JenkinsRule;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Reproduces the real "Save" flow on a Freestyle job's configure page for the Accessibility
 * Scanner build step (same style as {@code CavissonRunTestBuilderConfigRoundTripTest}):
 * {@link JenkinsRule#configRoundtrip} renders the actual config.jelly, submits the form through
 * a headless browser, and rebuilds the Builder instance from what was submitted.
 */
public class AccessibilityScannerBuilderConfigRoundTripTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void configSurvivesRoundTrip() throws Exception {
        AccessibilityScannerBuilder before = new AccessibilityScannerBuilder();
        before.setCavConnection("my-cavisson-connection");
        before.setController("work");
        before.setApplicationUrl("https://example.com");

        AccessibilityScannerBuilder after = j.configRoundtrip(before);

        assertEquals("my-cavisson-connection", after.getCavConnection());
        assertEquals("work", after.getController());
        assertEquals("https://example.com", after.getApplicationUrl());
    }
}
