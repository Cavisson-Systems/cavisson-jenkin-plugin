package com.cavisson.jenkins.connection;

/**
 * Resolved Cavisson DashboardServer connection details (base URL + plaintext API token),
 * regardless of whether they came from inline build-step parameters or from an existing
 * Cavisson Service Connection credential. Shared by every task in this plugin.
 */
public final class CavissonConnection {

    private final String baseUrl;
    private final String apiToken;

    public CavissonConnection(String baseUrl, String apiToken) {
        this.baseUrl = baseUrl;
        this.apiToken = apiToken;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiToken() {
        return apiToken;
    }
}
