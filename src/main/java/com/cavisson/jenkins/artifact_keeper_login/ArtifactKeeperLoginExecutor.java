package com.cavisson.jenkins.artifact_keeper_login;

import com.cavisson.jenkins.env.CavissonEnvironmentPublisher;
import com.cavisson.jenkins.log.CavLogger;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import hudson.util.Secret;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves docker registry username/token from a plain Jenkins "Username with password"
 * credential and, when a registry is known (the {@code registry} parameter), performs the actual
 * {@code docker login} itself - feeding the token to the login process's stdin directly rather
 * than a shell {@code echo | docker login} pipe, so it never appears on a command line or
 * anywhere in the Jenkins console.
 *
 * <p>No longer talks to the Cavisson server at all for this - credentials are resolved entirely
 * from Jenkins' own credential store via {@code dockerCredentialId}.
 *
 * <p><b>Backward compatible</b>: if no registry is set, this behaves exactly as before it gained
 * login support - it just resolves credentials and returns them as a Map for the caller to log in
 * with itself (e.g. via its own {@code sh "... | docker login ..."}).
 *
 * <p>Used by {@link ArtifactKeeperLoginStep} (Pipeline-only - no Freestyle Builder for this task).
 */
final class ArtifactKeeperLoginExecutor {

    private ArtifactKeeperLoginExecutor() {
    }

    static Map<String, Object> run(Run<?, ?> run,
                                    FilePath workspace,
                                    Launcher launcher,
                                    EnvVars env,
                                    TaskListener listener,
                                    String dockerCredentialId,
                                    String registry) throws IOException, InterruptedException {

        CavLogger log = new CavLogger(listener, env);

        String resolvedCredentialId = expand(env, dockerCredentialId);
        if (resolvedCredentialId == null || resolvedCredentialId.trim().isEmpty()) {
            throw new AbortException("dockerCredentialId is required.");
        }

        StandardUsernamePasswordCredentials credential = CredentialsProvider.findCredentialById(
                resolvedCredentialId, StandardUsernamePasswordCredentials.class, run);
        if (credential == null) {
            throw new AbortException("Jenkins credential '" + resolvedCredentialId
                    + "' was not found (expected a 'Username with password' credential).");
        }

        String username = credential.getUsername();
        String token = Secret.toString(credential.getPassword());

        log.info("========== Artifact Keeper Login ==========");
        log.info("Credential ID : " + resolvedCredentialId);
        log.info("Username      : " + username);
        log.info("=============================================");

        String resolvedRegistry = expand(env, registry);

        if (resolvedRegistry != null && !resolvedRegistry.trim().isEmpty()) {
            dockerLogin(workspace, launcher, listener, log, resolvedRegistry, username, token);
        } else {
            log.info("No registry set - skipping docker login, returning resolved credentials only.");
        }

        // The PAT/token is never logged, in either INFO or DEBUG, and never published as a plain
        // env var (unlike the fields below) - only handed back in the returned Map in case the
        // caller still wants it directly (e.g. a script that logs in itself for backward compat).
        Map<String, String> envVars = new LinkedHashMap<>();
        envVars.put("CAV_ARTIFACT_KEEPER_NAME", resolvedCredentialId);
        envVars.put("CAV_ARTIFACT_KEEPER_USERNAME", username);
        if (resolvedRegistry != null && !resolvedRegistry.isEmpty()) {
            envVars.put("CAV_ARTIFACT_KEEPER_REGISTRY_URL", resolvedRegistry);
        }
        CavissonEnvironmentPublisher.publish(run, envVars);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", resolvedCredentialId);
        result.put("registryUrl", resolvedRegistry);
        result.put("username", username);
        result.put("token", token);
        return result;
    }

    private static void dockerLogin(FilePath workspace, Launcher launcher, TaskListener listener, CavLogger log,
                                     String registry, String username, String token) throws IOException, InterruptedException {

        log.info("Logging in to Docker registry: " + registry);

        // The workspace directory may not exist on disk yet (e.g. this step running before
        // anything else has touched the filesystem) - make sure it's there before using it as a
        // process cwd.
        workspace.mkdirs();

        ArgumentListBuilder args = new ArgumentListBuilder(
                "docker", "login", registry, "-u", username, "--password-stdin");

        int exitCode = launcher.launch()
                .cmds(args)
                // The token is fed straight to the process's stdin, never as a command-line
                // argument and never echoed - it cannot appear in the Jenkins console this way.
                .stdin(new ByteArrayInputStream(token.getBytes(StandardCharsets.UTF_8)))
                .pwd(workspace)
                .stdout(listener)
                .quiet(true)
                .join();

        if (exitCode != 0) {
            // Deliberately excludes the token - only the registry/username (not secret) are named.
            throw new AbortException("docker login failed for registry '" + registry
                    + "' (user '" + username + "', exit code " + exitCode + ").");
        }

        log.info("Docker login succeeded.");
    }

    private static String expand(EnvVars env, String value) {
        return value == null ? null : env.expand(value);
    }
}
