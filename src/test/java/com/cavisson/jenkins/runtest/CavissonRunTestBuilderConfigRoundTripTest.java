package com.cavisson.jenkins.runtest;

import org.junit.jupiter.api.BeforeEach;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reproduces the real "Save" flow on a Freestyle job's configure page (the exact path that
 * previously crashed with NoStaplerConstructorException / IllegalArgumentException when
 * connectionMode/testType were bound via f:radioBlock). JenkinsRule#configRoundtrip renders the
 * actual config.jelly, submits the form through a headless browser, and rebuilds the Builder
 * instance from what was submitted - so this test fails the same way a real "Save" click would
 * if the Jelly/Descriptor binding is broken again.
 */
@WithJenkins
class CavissonRunTestBuilderConfigRoundTripTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void directModeConfigSurvivesRoundTrip() throws Exception {
        CavissonRunTestBuilder before = new CavissonRunTestBuilder();
        before.setConnectionMode("direct");
        before.setBaseUrl("https://10.10.70.105");
        before.setApiTokenCredentialId("70-105-pat");
        before.setTestType("TestSuite");
        before.setProject("AI");
        before.setSubProject("demo");
        before.setUsername("cavisson");
        before.setProfile("system");
        before.setTestSuiteName("BoutiqueDiscountCouponRegression");

        CavissonRunTestBuilder after = j.configRoundtrip(before);

        assertEquals("direct", after.getConnectionMode());
        assertEquals("https://10.10.70.105", after.getBaseUrl());
        assertEquals("70-105-pat", after.getApiTokenCredentialId());
        assertEquals("TestSuite", after.getTestType());
        assertEquals("AI", after.getProject());
        assertEquals("demo", after.getSubProject());
        assertEquals("cavisson", after.getUsername());
        assertEquals("system", after.getProfile());
        assertEquals("BoutiqueDiscountCouponRegression", after.getTestSuiteName());
    }

    @Test
    void loadTestModeConfigSurvivesRoundTrip() throws Exception {
        CavissonRunTestBuilder before = new CavissonRunTestBuilder();
        before.setConnectionMode("serviceConnection");
        before.setCavServiceConnectionId("my-service-connection");
        before.setTestType("LoadTest");
        before.setScenarioName("MyLoadTestScenario");

        CavissonRunTestBuilder after = j.configRoundtrip(before);

        assertEquals("serviceConnection", after.getConnectionMode());
        assertEquals("my-service-connection", after.getCavServiceConnectionId());
        assertEquals("LoadTest", after.getTestType());
        assertEquals("MyLoadTestScenario", after.getScenarioName());
    }
}
