package com.cavisson.jenkins.accessibility;

import org.junit.jupiter.api.BeforeEach;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reproduces the real "Save" flow on a Freestyle job's configure page for the Accessibility
 * Scanner build step (same style as {@code CavissonRunTestBuilderConfigRoundTripTest}):
 * {@link JenkinsRule#configRoundtrip} renders the actual config.jelly, submits the form through
 * a headless browser, and rebuilds the Builder instance from what was submitted.
 */
@WithJenkins
class AccessibilityScannerBuilderConfigRoundTripTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void configSurvivesRoundTrip() throws Exception {
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
