package com.cavisson.jenkins.ai.testcase.client;

import com.cavisson.jenkins.ai.testcase.exception.CavAIApiException;
import com.cavisson.jenkins.ai.testcase.model.UserNameResponse;
import com.cavisson.jenkins.log.CavLogger;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Resolves "userName" dynamically from the Cav Token stored in the Service
 * Connection, via GET .../getUserNameFromToken on the Dashboard Server. This
 * is the single source of truth for userName - the pipeline step has no
 * userName input at all (see {@code CavAITestCaseBuilder}).
 *
 * Failure handling:
 *   - HTTP error or unparsable JSON  -> throws {@link CavAIApiException},
 *     the caller aborts the build before any further REST calls
 *     (upload / trigger) are made. This indicates the authentication call
 *     itself is broken and should not proceed silently.
 *   - Call succeeds but the response has no username (blank/missing field)
 *     -> does NOT throw; falls back to {@link #FALLBACK_USER_NAME} and logs
 *     a WARNING. This is the only case where the fallback applies - it is
 *     not used to paper over connectivity or parsing failures.
 *
 * {@code PayloadBuilder} never calls this service or performs REST calls
 * itself - {@code CavAITestCaseBuilder.perform()} calls
 * {@link #fetchUserName(String, String, CavLogger)} once, up front, and
 * passes the resolved value into {@code PayloadBuilder} as plain data.
 */
public class UserNameService {

    /**
     * Used only when the Dashboard Server responds successfully but returns
     * no username. Never used to mask a real API/connectivity failure.
     */
    static final String FALLBACK_USER_NAME = "cavisson";

    private final CavAIRestClient restClient;

    public UserNameService() {
        this(new CavAIRestClient());
    }

    /** Constructor for injecting a custom/stub {@link CavAIRestClient} in tests. */
    public UserNameService(CavAIRestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Fetches the username for the given Cav Token from the Dashboard Server.
     *
     * @param dashboardUrl Dashboard Server URL (from the Service Connection)
     * @param cavToken     Cav Token (from the Service Connection)
     * @param log          shared build logger
     * @return the resolved username - never null or blank. Either the real
     *         username from the Dashboard Server, or {@link #FALLBACK_USER_NAME}
     *         if the call succeeded but returned no username.
     * @throws CavAIApiException if the HTTP call itself fails or the response
     *                           cannot be parsed as JSON. The message is safe
     *                           to log directly - it never contains the Cav Token.
     */
    public String fetchUserName(String dashboardUrl, String cavToken, CavLogger log)
            throws CavAIApiException {

        log.debug("Fetching username using Cav Token...");

        String body = restClient.fetchUserNameFromToken(dashboardUrl, cavToken, log);

        UserNameResponse parsed;
        try {
            parsed = parse(body);
        } catch (JSONException e) {
            throw new CavAIApiException("Invalid JSON response - " + e.getMessage(), e);
        }

        if (!parsed.hasUserName()) {
            String detail = notBlank(parsed.getStatusMessage())
                    ? " (" + parsed.getStatusMessage() + ")" : "";
            log.warn("Cav Token authentication succeeded but returned no username"
                    + detail + ". Falling back to \"" + FALLBACK_USER_NAME + "\".");
            return FALLBACK_USER_NAME;
        }

        log.debug("Username fetched successfully.");
        log.debug("Username : " + parsed.getUserName());

        return parsed.getUserName();
    }

    // -- Parsing -----------------------------------------------------------

    UserNameResponse parse(String body) {
        JSONObject root = new JSONObject(body);

        String userName = root.optString("userName", "");

        int        statusCode    = -1;
        String     statusMessage = "";
        JSONObject status        = root.optJSONObject("status");
        if (status != null) {
            statusCode    = status.optInt("code", -1);
            statusMessage = status.optString("msg", "");
        }

        return new UserNameResponse(userName, statusCode, statusMessage);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
