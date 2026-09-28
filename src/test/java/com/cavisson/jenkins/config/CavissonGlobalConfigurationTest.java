package com.cavisson.jenkins.config;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CavissonGlobalConfigurationTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void insecureSslIsOffByDefault() {
        assertFalse(CavissonGlobalConfiguration.get().isAllowInsecureSSL());
        assertFalse(CavissonGlobalConfiguration.insecureSslAllowed());
    }

    @Test
    public void allowInsecureSslSurvivesConfigRoundTrip() throws Exception {
        CavissonGlobalConfiguration.get().setAllowInsecureSSL(true);
        j.configRoundtrip();
        assertTrue(CavissonGlobalConfiguration.get().isAllowInsecureSSL());
        assertTrue(CavissonGlobalConfiguration.insecureSslAllowed());
    }
}
