package com.cavisson.jenkins.qualitygate;

import com.cavisson.jenkins.scriptlog.CavLogger;

import com.cavisson.jenkins.http.HttpUtil;

import hudson.model.TaskListener;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Exchanges the Cavisson API token for a short-lived SonarQube admin token + user name, the
 * same token exchange the Static scan does — needed here to rebuild the SonarQube-specific
 * {@code sonar_url}/{@code sonar_token} evaluate fields, since those values are ephemeral and
 * aren't persisted anywhere after the Static stage finishes.
 */
final class SonarTokenExchange {

    static final class TokenResult {
        final String sonarToken;
        final String userName;

        TokenResult(String sonarToken, String userName) {
            this.sonarToken = sonarToken;
            this.userName = userName;
        }
    }

    private SonarTokenExchange() {
    }

    static TokenResult exchangeToken(TaskListener listener, String baseUrl, String apiToken) throws IOException {
        String normalizedBaseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        String tokenUrl = normalizedBaseUrl + "/DashboardServer/v2/web/common/getToken";

        JSONObject tokenRequest = new JSONObject();
        tokenRequest.put("token", apiToken);
        tokenRequest.put("validity_ms", 0);
        tokenRequest.put("generated_time", 0);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("cavToken", apiToken);

        CavLogger.debug(listener, "Requesting SAST tokens from: " + tokenUrl);

        HttpUtil.HttpResult tokenResponse = HttpUtil.postJson(tokenUrl, tokenRequest.toString(), headers, true);

        JSONObject responseData = new JSONObject(tokenResponse.body);
        if (!responseData.has("tokens")) {
            throw new IOException("Failed to get token: " + tokenResponse.body);
        }

        JSONObject parsedData = new JSONObject(responseData.getString("tokens"));

        String sonarToken = parsedData.optString("adminToken", null);
        String userName = parsedData.optString("userName", null);

        if (sonarToken == null || userName == null || sonarToken.isEmpty() || userName.isEmpty()) {
            throw new IOException("Invalid token response from Cavisson. adminToken/userName missing.");
        }

        return new TokenResult(sonarToken, userName);
    }
}
