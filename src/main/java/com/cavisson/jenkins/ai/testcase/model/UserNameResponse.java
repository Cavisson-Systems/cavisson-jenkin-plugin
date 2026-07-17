package com.cavisson.jenkins.ai.testcase.model;

/**
 * Parsed response from
 * GET {DashboardServerURL}/node/ALL/DashboardServer/v2/web/common/getUserNameFromToken
 *
 * Sample response:
 * <pre>
 * {
 *   "userName": "cavisson",
 *   "status": {
 *     "code": 0,
 *     "msg": "Username fetched successfully from cavToken."
 *   }
 * }
 * </pre>
 *
 * Only userName and the status fields are modeled; any other top-level
 * fields the server returns (e.g. "rsTime") are ignored.
 */
public final class UserNameResponse {

    private final String userName;
    private final int    statusCode;
    private final String statusMessage;

    public UserNameResponse(String userName, int statusCode, String statusMessage) {
        this.userName      = userName;
        this.statusCode    = statusCode;
        this.statusMessage = statusMessage;
    }

    public String getUserName()      { return userName; }
    public int    getStatusCode()    { return statusCode; }
    public String getStatusMessage() { return statusMessage; }

    /** True when userName is present and non-blank. */
    public boolean hasUserName() {
        return userName != null && !userName.trim().isEmpty();
    }
}
