package com.cavisson.jenkins.git;

/**
 * One "Git Integration" entry resolved from the Cavisson server (username + PAT + Repository
 * URL), fetched fresh per build by {@link GitIntegrationClient} - never persisted as a Jenkins
 * Credential. {@code repoUrl} may be blank if the server's record for this integration doesn't
 * have its "Repository URL" field populated - callers should still accept an explicit override
 * or an env var fallback in that case.
 */
public final class GitIntegrationConfig {

    public final String name;
    public final String repoUrl;
    public final String username;
    public final String token;

    public GitIntegrationConfig(String name, String repoUrl, String username, String token) {
        this.name = name;
        this.repoUrl = repoUrl;
        this.username = username;
        this.token = token;
    }
}
