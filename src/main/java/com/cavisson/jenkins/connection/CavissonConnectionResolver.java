package com.cavisson.jenkins.connection;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.Run;
import hudson.util.Secret;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/**
 * Resolves the Cavisson DashboardServer connection details for a task, either directly from
 * inline parameters (base URL + a "Secret text" credential holding the API token), or by looking
 * up an existing {@link CavServiceConnection} ("Cavisson Service Connection") credential.
 *
 * <p>Free-text fields (base URL, service connection ID) are expanded against the build's
 * environment variables (e.g. a value of {@code ${CAV_BASE_URL}} is resolved) - everything except
 * pickers/dropdowns (connection mode, credential ID) supports this, matching every other task
 * input in this plugin.
 */
public final class CavissonConnectionResolver {

    private CavissonConnectionResolver() {
    }

    public static CavissonConnection resolve(Run<?, ?> run,
                                              EnvVars env,
                                              String connectionMode,
                                              String baseUrl,
                                              String apiTokenCredentialId,
                                              String cavServiceConnectionId) throws AbortException {

        String expandedBaseUrl = expand(env, baseUrl);
        String expandedServiceConnectionId = expand(env, cavServiceConnectionId);

        if ("serviceConnection".equals(connectionMode)) {
            return resolveViaServiceConnection(run, expandedServiceConnectionId);
        }
        return resolveDirect(run, expandedBaseUrl, apiTokenCredentialId);
    }

    private static String expand(EnvVars env, String value) {
        return value == null ? null : env.expand(value);
    }

    private static CavissonConnection resolveDirect(Run<?, ?> run, String baseUrl, String apiTokenCredentialId) throws AbortException {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new AbortException("Base URL is required when Connection Mode is Direct.");
        }
        if (apiTokenCredentialId == null || apiTokenCredentialId.trim().isEmpty()) {
            throw new AbortException("API Token credential is required when Connection Mode is Direct.");
        }

        StringCredentials credential = CredentialsProvider.findCredentialById(apiTokenCredentialId, StringCredentials.class, run);
        if (credential == null) {
            throw new AbortException("API Token credential '" + apiTokenCredentialId + "' was not found.");
        }

        String normalizedBaseUrl = baseUrl.trim().replaceAll("/+$", "");
        return new CavissonConnection(normalizedBaseUrl, Secret.toString(credential.getSecret()));
    }

    private static CavissonConnection resolveViaServiceConnection(Run<?, ?> run, String cavServiceConnectionId) throws AbortException {
        if (cavServiceConnectionId == null || cavServiceConnectionId.trim().isEmpty()) {
            throw new AbortException("Service Connection ID is required when Connection Mode is Service Connection.");
        }

        CavServiceConnection credential = CredentialsProvider.findCredentialById(
                cavServiceConnectionId, CavServiceConnection.class, run);

        if (credential == null) {
            throw new AbortException("Cavisson Service Connection '" + cavServiceConnectionId + "' was not found.");
        }

        return new CavissonConnection(credential.getBaseUrl(), Secret.toString(credential.getApiToken()));
    }
}
