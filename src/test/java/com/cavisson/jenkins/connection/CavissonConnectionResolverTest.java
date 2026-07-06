package com.cavisson.jenkins.connection;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.EnvVars;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertEquals;

/**
 * Verifies that Direct-mode's free-text fields (base URL, service connection ID) are expanded
 * against the build's environment variables, e.g. a value of {@code https://${CAV_HOST}} resolves
 * using an env var named CAV_HOST - matching every other free-text task input in this plugin.
 * The credential ID picker itself is intentionally NOT expanded (it's a selector, not free text).
 */
public class CavissonConnectionResolverTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void baseUrlIsExpandedAgainstBuildEnvironment() throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(CredentialsScope.GLOBAL, "test-token", "desc", hudson.util.Secret.fromString("s3cr3t")));
        SystemCredentialsProvider.getInstance().save();

        FreeStyleProject project = j.createFreeStyleProject();
        FreeStyleBuild build = j.buildAndAssertSuccess(project);

        EnvVars env = new EnvVars();
        env.put("CAV_HOST", "10.10.70.105");

        CavissonConnection connection = CavissonConnectionResolver.resolve(
                build, env, "direct", "https://${CAV_HOST}:4444", "test-token", "");

        assertEquals("https://10.10.70.105:4444", connection.getBaseUrl());
    }
}
