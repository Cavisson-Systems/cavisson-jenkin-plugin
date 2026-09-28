package com.cavisson.jenkins.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class CavissonGlobalConfigurationTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void insecureSslIsOffByDefault() {
        assertFalse(CavissonGlobalConfiguration.get().isAllowInsecureSSL());
        assertFalse(CavissonGlobalConfiguration.insecureSslAllowed());
    }

    @Test
    void allowInsecureSslSurvivesConfigRoundTrip() throws Exception {
        CavissonGlobalConfiguration.get().setAllowInsecureSSL(true);
        j.configRoundtrip();
        assertTrue(CavissonGlobalConfiguration.get().isAllowInsecureSSL());
        assertTrue(CavissonGlobalConfiguration.insecureSslAllowed());
    }
}
