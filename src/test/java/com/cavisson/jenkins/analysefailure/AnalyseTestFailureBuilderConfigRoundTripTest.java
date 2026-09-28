package com.cavisson.jenkins.analysefailure;

import org.junit.jupiter.api.BeforeEach;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reproduces the real "Save" flow on a Freestyle job's configure page. See CLAUDE.md's Jelly
 * form gotcha: f:radioBlock bundles sibling fields into a wrapped JSON object that does not bind
 * to a plain String property. This config.jelly uses flat f:select/f:textbox fields from the
 * start, but this test exists to keep it that way as fields are added.
 */
@WithJenkins
class AnalyseTestFailureBuilderConfigRoundTripTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void tsrNumberModeConfigSurvivesRoundTrip() throws Exception {
        AnalyseTestFailureBuilder before = new AnalyseTestFailureBuilder();
        before.setConnectionMode("direct");
        before.setBaseUrl("https://10.10.70.105:4444");
        before.setApiTokenCredentialId("70-105-pat");
        before.setTsrNumber("1061");
        before.setConcurrency(4);

        AnalyseTestFailureBuilder after = j.configRoundtrip(before);

        assertEquals("direct", after.getConnectionMode());
        assertEquals("https://10.10.70.105:4444", after.getBaseUrl());
        assertEquals("70-105-pat", after.getApiTokenCredentialId());
        assertEquals("1061", after.getTsrNumber());
        assertEquals(4, after.getConcurrency());
    }

    @Test
    void trNumberDirectModeConfigSurvivesRoundTrip() throws Exception {
        AnalyseTestFailureBuilder before = new AnalyseTestFailureBuilder();
        before.setConnectionMode("serviceConnection");
        before.setCavServiceConnectionId("my-service-connection");
        before.setTrNumber("1753");
        before.setScenario("twoproducts_EUR_SAVE10DiscountCoupon");
        before.setProject("AI");
        before.setSubProject("demo");
        before.setUserName("cavisson");
        before.setWorkProfileName("system");

        AnalyseTestFailureBuilder after = j.configRoundtrip(before);

        assertEquals("serviceConnection", after.getConnectionMode());
        assertEquals("my-service-connection", after.getCavServiceConnectionId());
        assertEquals("1753", after.getTrNumber());
        assertEquals("twoproducts_EUR_SAVE10DiscountCoupon", after.getScenario());
        assertEquals("AI", after.getProject());
        assertEquals("demo", after.getSubProject());
        assertEquals("cavisson", after.getUserName());
        assertEquals("system", after.getWorkProfileName());
    }
}
