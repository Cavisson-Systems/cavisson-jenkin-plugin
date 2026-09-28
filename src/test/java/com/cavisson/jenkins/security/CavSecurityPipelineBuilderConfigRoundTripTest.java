package com.cavisson.jenkins.security;

import org.junit.jupiter.api.BeforeEach;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reproduces the real "Save" flow on a Freestyle job's configure page for the merged-in
 * security scan builder. Confirms the scanType f:select (replacing the original f:radioBlock,
 * which per CLAUDE.md's documented gotcha bundles sibling fields into a JSON object that does
 * not bind to a plain String property) survives a real form submit for all three scan types.
 */
@WithJenkins
class CavSecurityPipelineBuilderConfigRoundTripTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void sastConfigSurvivesRoundTrip() throws Exception {
        CavSecurityPipelineBuilder before = new CavSecurityPipelineBuilder("SAST");
        before.setCavScanServiceConnection("my-service-connection");
        before.setProject("demo-project");
        before.setTargetPath("/workspace/src");
        before.setQualityGate("gate1");
        before.setQualityGateTimeout("60");

        CavSecurityPipelineBuilder after = j.configRoundtrip(before);

        assertEquals("my-service-connection", after.getCavScanServiceConnection());
        assertEquals("SAST", after.getScanType());
        assertEquals("demo-project", after.getProject());
        assertEquals("/workspace/src", after.getTargetPath());
        assertEquals("gate1", after.getQualityGate());
        assertEquals("60", after.getQualityGateTimeout());
    }

    @Test
    void scaConfigSurvivesRoundTrip() throws Exception {
        CavSecurityPipelineBuilder before = new CavSecurityPipelineBuilder("SCA");
        before.setCavScanServiceConnection("my-service-connection");
        before.setProject("demo-project");
        before.setScaRegistryUrl("registry.example.com");
        before.setScaRepository("myrepo");
        before.setScaImageName("myimage");
        before.setScaImageTag("latest");

        CavSecurityPipelineBuilder after = j.configRoundtrip(before);

        assertEquals("SCA", after.getScanType());
        assertEquals("registry.example.com", after.getScaRegistryUrl());
        assertEquals("myrepo", after.getScaRepository());
        assertEquals("myimage", after.getScaImageName());
        assertEquals("latest", after.getScaImageTag());
    }

    @Test
    void dastConfigSurvivesRoundTrip() throws Exception {
        CavSecurityPipelineBuilder before = new CavSecurityPipelineBuilder("DAST");
        before.setCavScanServiceConnection("my-service-connection");
        before.setProject("demo-project");
        before.setDastTarget("http://target.example.com:8080");

        CavSecurityPipelineBuilder after = j.configRoundtrip(before);

        assertEquals("DAST", after.getScanType());
        assertEquals("http://target.example.com:8080", after.getDastTarget());
    }
}
