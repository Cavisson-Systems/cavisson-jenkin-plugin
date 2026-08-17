package com.cavisson.jenkins.git;

import com.cavisson.jenkins.connection.CavissonConnection;
import com.cavisson.jenkins.env.CavissonEnvironmentPublisher;
import com.cavisson.jenkins.log.CavLogger;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves a named Cavisson Git Integration (username + PAT + Repository URL) via
 * {@link GitIntegrationClient} and performs the actual {@code git clone} into the workspace - but
 * only once per workspace. If a {@code .git} directory is already present (e.g. a script calls
 * {@code cavGitCheckout} a second time later in the same build just to re-resolve credentials for
 * an authenticated push), the existing checkout is reused as-is instead of being wiped and
 * re-cloned. The resolved username/token/repoUrl/branch are always returned as a Map,
 * clone-skipped or not, so the caller can reuse them either way. {@code gitRepoUrl} is optional -
 * the Git Integration's own "Repository URL" field (resolved from the server) is used when the
 * caller doesn't supply one directly, with a {@code GIT_REPO_URL} build environment variable as a
 * last-resort fallback if the server's record has that field blank too. Used by
 * {@link CavGitCheckoutStep} (Pipeline-only - no Freestyle Builder for this task).
 */
final class CavGitCheckoutExecutor {

    private CavGitCheckoutExecutor() {
    }

    static Map<String, Object> run(Run<?, ?> run,
                                    FilePath workspace,
                                    Launcher launcher,
                                    EnvVars env,
                                    TaskListener listener,
                                    CavissonConnection connection,
                                    String gitIntegrationName,
                                    String gitRepoUrl,
                                    String gitBranch) throws IOException, InterruptedException {

        CavLogger log = new CavLogger(listener, env);

        String resolvedIntegrationName = expand(env, gitIntegrationName);
        String explicitRepoUrl         = expand(env, gitRepoUrl);
        String resolvedBranch          = expand(env, gitBranch);

        if (resolvedIntegrationName == null || resolvedIntegrationName.trim().isEmpty()) {
            throw new AbortException("gitIntegrationName is required.");
        }
        if (resolvedBranch == null || resolvedBranch.trim().isEmpty()) {
            resolvedBranch = "main";
        }

        log.info("========== Cavisson Git Checkout ==========");
        log.info("Service Base URL     : " + connection.getBaseUrl());
        log.info("Git Integration Name : " + resolvedIntegrationName);
        log.info("Git Branch           : " + resolvedBranch);
        log.info("=============================================");

        GitIntegrationConfig gitIntegration = GitIntegrationClient.fetch(
                connection.getBaseUrl(), connection.getApiToken(), resolvedIntegrationName, true, log);

        log.info("Resolved Git Integration '" + resolvedIntegrationName + "' - username: " + gitIntegration.username);

        // Priority: an explicit gitRepoUrl override, then the Git Integration's own "Repository
        // URL" field from the server, then a GIT_REPO_URL env var as a last resort.
        String resolvedRepoUrl = explicitRepoUrl;
        if (resolvedRepoUrl == null || resolvedRepoUrl.trim().isEmpty()) {
            resolvedRepoUrl = gitIntegration.repoUrl;
        }
        if (resolvedRepoUrl == null || resolvedRepoUrl.trim().isEmpty()) {
            resolvedRepoUrl = env.get("GIT_REPO_URL");
        }
        if (resolvedRepoUrl == null || resolvedRepoUrl.trim().isEmpty()) {
            throw new AbortException("Could not determine the repository URL: Git Integration '" + resolvedIntegrationName
                    + "' has no Repository URL configured on the server, no gitRepoUrl was passed directly, "
                    + "and no GIT_REPO_URL environment variable is set.");
        }
        log.info("Git Repository URL   : " + resolvedRepoUrl);

        // The workspace directory may not exist on disk yet (e.g. a brand-new job, or right after
        // a previous build's cleanWs() removed it entirely) - this is the first thing in the
        // build to touch the filesystem, so make sure it's there before using it as a process cwd.
        workspace.mkdirs();

        // Clone only once per workspace - a repo already checked out here (e.g. a script calling
        // cavGitCheckout a second time later in the same build, just to re-resolve credentials for
        // an authenticated push) is reused as-is rather than being wiped and re-cloned.
        if (workspace.child(".git").exists()) {
            log.info("Workspace already contains a git checkout - skipping clone, reusing it as-is.");
        } else {
            String cloneUrl = "https://" + gitIntegration.username + ":" + gitIntegration.token + "@"
                    + resolvedRepoUrl.replaceFirst("^https://", "");

            log.info("Cloning into workspace...");

            workspace.deleteContents();

            ArgumentListBuilder args = new ArgumentListBuilder("git", "clone", "--branch", resolvedBranch, cloneUrl, ".");

            int exitCode = launcher.launch()
                    .cmds(args)
                    .pwd(workspace)
                    .envs(env)
                    .stdout(listener)
                    .quiet(true) // never echo the command line - it embeds the PAT in cloneUrl
                    .join();

            if (exitCode != 0) {
                throw new AbortException("git clone failed for Git Integration '" + resolvedIntegrationName
                        + "' (exit code " + exitCode + ").");
            }
        }

        // The PAT/token is never logged, in either INFO or DEBUG, and never published as a plain
        // env var (unlike the fields below) - only handed back in the returned Map for the
        // Pipeline script to reuse directly (e.g. for an authenticated push later in the build).
        Map<String, String> envVars = new LinkedHashMap<>();
        envVars.put("CAV_GIT_INTEGRATION_NAME", resolvedIntegrationName);
        envVars.put("CAV_GIT_USERNAME", gitIntegration.username);
        envVars.put("CAV_GIT_REPO_URL", resolvedRepoUrl);
        envVars.put("CAV_GIT_BRANCH", resolvedBranch);
        CavissonEnvironmentPublisher.publish(run, envVars);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("integrationName", resolvedIntegrationName);
        result.put("repoUrl", resolvedRepoUrl);
        result.put("branch", resolvedBranch);
        result.put("username", gitIntegration.username);
        result.put("token", gitIntegration.token);
        return result;
    }

    private static String expand(EnvVars env, String value) {
        return value == null ? null : env.expand(value);
    }
}
