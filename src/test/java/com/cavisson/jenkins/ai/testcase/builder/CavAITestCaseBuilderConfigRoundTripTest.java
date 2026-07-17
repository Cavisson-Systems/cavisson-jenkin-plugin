package com.cavisson.jenkins.ai.testcase.builder;

import org.jvnet.hudson.test.JenkinsRule;
import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

/**
 * Reproduces the real "Save" flow on a Freestyle job's configure page for the merged-in
 * "Cavisson AI Test Case Generation" builder. See CLAUDE.md's Jelly form gotcha: f:radioBlock
 * bundles sibling fields into a wrapped JSON object that does not bind to a plain String
 * property. This config.jelly uses flat f:select/f:textbox fields, but this test exists to
 * confirm the merged-in Jelly/getter/setter wiring survives a real form submit, not just
 * Jelly-parse-only checks.
 */
public class CavAITestCaseBuilderConfigRoundTripTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void localSourceConfigSurvivesRoundTrip() throws Exception {
        CavAITestCaseBuilder before = new CavAITestCaseBuilder("my-ai-service-connection");
        before.setApplicationUrl("https://www.saucedemo.com");
        before.setPrdSourceType("LOCAL");
        before.setPrdParameterName("PRD_FILE_UPLOAD");
        before.setWorkspaceRoot("/home/cavisson/work");
        before.setProject("AI");
        before.setSubProject("demo");
        before.setTestSuiteName("nightly_ai_suite");
        before.setNumberOfTestCases(3);
        before.setControllerName("work");
        before.setUsername("tester");
        before.setPassword("secret");
        before.setAuthenticationPrompt("Login");
        before.setTags(Arrays.asList("app=boutique"));

        CavAITestCaseBuilder after = j.configRoundtrip(before);

        assertEquals("my-ai-service-connection", after.getCavServiceConnectionId());
        assertEquals("https://www.saucedemo.com", after.getApplicationUrl());
        assertEquals("LOCAL", after.getPrdSourceType());
        assertEquals("PRD_FILE_UPLOAD", after.getPrdParameterName());
        assertEquals("/home/cavisson/work", after.getWorkspaceRoot());
        assertEquals("AI", after.getProject());
        assertEquals("demo", after.getSubProject());
        assertEquals("nightly_ai_suite", after.getTestSuiteName());
        assertEquals(3, after.getNumberOfTestCases());
        assertEquals("work", after.getControllerName());
        assertEquals("tester", after.getUsername());
        assertEquals("secret", after.getPassword());
        assertEquals("Login", after.getAuthenticationPrompt());
        assertEquals(Arrays.asList("app=boutique"), after.getTags());
    }

    @Test
    public void gitSourceConfigSurvivesRoundTrip() throws Exception {
        CavAITestCaseBuilder before = new CavAITestCaseBuilder("my-ai-service-connection");
        before.setPrdSourceType("GIT");
        before.setGitRepoUrl("https://github.com/org/repo.git");
        before.setGitBranch("main");
        before.setGitPrdPath("docs/MyPRD.feature");

        CavAITestCaseBuilder after = j.configRoundtrip(before);

        assertEquals("GIT", after.getPrdSourceType());
        assertEquals("https://github.com/org/repo.git", after.getGitRepoUrl());
        assertEquals("main", after.getGitBranch());
        assertEquals("docs/MyPRD.feature", after.getGitPrdPath());
    }
}
