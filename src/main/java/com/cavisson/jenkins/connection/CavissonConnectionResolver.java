package com.cavisson.jenkins.connection;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.IdCredentials;
import hudson.AbortException;
import hudson.model.Run;
import hudson.util.Secret;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

import java.lang.reflect.Method;

/**
 * Resolves the Cavisson DashboardServer connection details for a task, either directly from
 * inline parameters (base URL + a "Secret text" credential holding the API token), or by
 * reflectively looking up an existing "Cavisson Service Connection" credential provided by the
 * (separate, optional) cav-security-pipeline plugin. The second mode uses reflection so this
 * plugin has zero compile-time or Jenkins plugin-manifest dependency on cav-security-pipeline:
 * it works if that plugin happens to be installed, and fails with a clear message if it is not.
 */
public final class CavissonConnectionResolver {

    private static final String SERVICE_CONNECTION_CLASS_NAME = "com.cavisson.jenkins.security.CavServiceConnection";

    private CavissonConnectionResolver() {
    }

    public static CavissonConnection resolve(Run<?, ?> run,
                                              String connectionMode,
                                              String baseUrl,
                                              String apiTokenCredentialId,
                                              String cavServiceConnectionId) throws AbortException {

        if ("serviceConnection".equals(connectionMode)) {
            return resolveViaServiceConnection(run, cavServiceConnectionId);
        }
        return resolveDirect(run, baseUrl, apiTokenCredentialId);
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

        Class<?> serviceConnectionClass;
        try {
            serviceConnectionClass = Class.forName(SERVICE_CONNECTION_CLASS_NAME);
        } catch (ClassNotFoundException e) {
            throw new AbortException("The cav-security-pipeline plugin (which provides Cavisson Service "
                    + "Connection credentials) is not installed on this Jenkins instance. Install it, or "
                    + "switch Connection Mode to Direct.");
        }

        Class<? extends IdCredentials> credentialsClass = serviceConnectionClass.asSubclass(IdCredentials.class);
        IdCredentials credential = CredentialsProvider.findCredentialById(cavServiceConnectionId, credentialsClass, run);

        if (credential == null) {
            throw new AbortException("Cavisson Service Connection '" + cavServiceConnectionId + "' was not found.");
        }

        try {
            Method getBaseUrl = serviceConnectionClass.getMethod("getBaseUrl");
            Method getApiToken = serviceConnectionClass.getMethod("getApiToken");

            String baseUrl = (String) getBaseUrl.invoke(credential);
            Secret apiToken = (Secret) getApiToken.invoke(credential);

            return new CavissonConnection(baseUrl, Secret.toString(apiToken));
        } catch (ReflectiveOperationException e) {
            throw new AbortException("Unable to read Cavisson Service Connection '" + cavServiceConnectionId
                    + "': " + e.getMessage());
        }
    }
}
