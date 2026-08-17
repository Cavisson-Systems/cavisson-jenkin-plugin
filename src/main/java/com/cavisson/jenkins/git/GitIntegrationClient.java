package com.cavisson.jenkins.git;

import com.cavisson.jenkins.http.HttpUtil;
import com.cavisson.jenkins.log.CavLogger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;

/**
 * Resolves a named "Git Integration" (username + PAT + Repository URL, stored in the Cavisson
 * application's own Extension Config list, type {@code gitsourcecode}) via REST, instead of
 * Jenkins storing or managing that credential itself. The resolved username/token live only as
 * local variables for the duration of one build - never added to Jenkins' CredentialsStore, so
 * there is nothing to clean up afterward and no risk of leaking into other jobs' credential
 * pickers.
 *
 * {@code cavToken} is sent as a query parameter here (not the {@code cavToken} header convention
 * every other task in this plugin uses) - this endpoint's own contract, confirmed against a
 * working reference call.
 */
public final class GitIntegrationClient {

    private static final String CONFIG_LIST_PATH = "/tomcat/master/DashboardServer/v2/extension/config/list";
    private static final String EXTENSION_TYPE   = "gitsourcecode";

    /** Candidate JSON keys tried, in order, for each piece of data - the server's exact field
     * naming isn't documented, so the first non-blank match wins. */
    private static final String[] NAME_KEYS     = {"name", "configName", "extensionName", "integrationName", "label"};
    private static final String[] REPO_URL_KEYS = {"repoUrl", "repositoryUrl", "repositoryURL", "repoURL",
            "gitRepoUrl", "gitUrl", "url", "repository"};
    private static final String[] USERNAME_KEYS = {"uname", "username", "userName", "gitUsername", "user", "gitUser"};
    private static final String[] TOKEN_KEYS    = {"pwd", "token", "pat", "personalAccessToken", "accessToken",
            "password", "secret", "gitToken", "credential"};
    private static final String[] LIST_KEYS     = {"data", "list", "configs", "result", "results", "items", "extensions"};

    private GitIntegrationClient() {
    }

    public static GitIntegrationConfig fetch(String baseUrl, String cavToken, String configName,
                                              boolean allowInsecureSSL, CavLogger log) throws IOException {
        String url = baseUrl.replaceAll("/+$", "") + CONFIG_LIST_PATH
                + "?extensionType=" + EXTENSION_TYPE + "&cavToken=" + urlEncode(cavToken);

        HttpUtil.HttpResult response = HttpUtil.getJson(url, new HashMap<String, String>(), allowInsecureSSL);
        log.debug("Git Integration config list response: " + response.body);

        JSONArray entries = extractEntries(response.body);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String entryName = firstNonBlank(entry, NAME_KEYS);
            if (configName.equalsIgnoreCase(entryName)) {
                String repoUrl = firstNonBlank(entry, REPO_URL_KEYS);
                String username = firstNonBlank(entry, USERNAME_KEYS);
                String token = firstNonBlank(entry, TOKEN_KEYS);
                if (username.isEmpty() || token.isEmpty()) {
                    throw new IOException("Git Integration '" + configName
                            + "' was found but its username/token fields could not be read from the response.");
                }
                return new GitIntegrationConfig(entryName, repoUrl, username, token);
            }
        }

        throw new IOException("Could not find a Git Integration named '" + configName
                + "' in the server's gitsourcecode extension config list.");
    }

    /** The list may be a bare JSON array, or an object wrapping it under one of {@link #LIST_KEYS}. */
    private static JSONArray extractEntries(String body) throws IOException {
        String trimmed = body == null ? "" : body.trim();
        try {
            if (trimmed.startsWith("[")) {
                return new JSONArray(trimmed);
            }
            JSONObject root = new JSONObject(trimmed);
            for (String key : LIST_KEYS) {
                if (root.optJSONArray(key) != null) {
                    return root.getJSONArray(key);
                }
            }
        } catch (Exception e) {
            throw new IOException("Could not parse Git Integration config list response: " + e.getMessage(), e);
        }
        throw new IOException("Git Integration config list response did not contain a recognizable list of configs.");
    }

    private static String firstNonBlank(JSONObject entry, String... keys) {
        for (String key : keys) {
            String value = entry.optString(key, "");
            if (!value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
