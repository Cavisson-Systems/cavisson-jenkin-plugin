package com.cavisson.jenkins.security;

import org.jvnet.hudson.test.JenkinsRule;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Reproduces the real "Save" flow on a Freestyle job's configure page for the merged-in
 * security scan builder. Confirms the scanType f:select (replacing the original f:radioBlock,
 * which per CLAUDE.md's documented gotcha bundles sibling fields into a JSON object that does
 * not bind to a plain String property) survives a real form submit for all three scan types.
 */
public class CavSecurityPipelineBuilderConfigRoundTripTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void sastConfigSurvivesRoundTrip() throws Exception {
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
    public void scaConfigSurvivesRoundTrip() throws Exception {
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
    public void dastConfigSurvivesRoundTrip() throws Exception {
        CavSecurityPipelineBuilder before = new CavSecurityPipelineBuilder("DAST");
        before.setCavScanServiceConnection("my-service-connection");
        before.setProject("demo-project");
        before.setDastTarget("http://target.example.com:8080");

        CavSecurityPipelineBuilder after = j.configRoundtrip(before);

        assertEquals("DAST", after.getScanType());
        assertEquals("http://target.example.com:8080", after.getDastTarget());
    }
}
