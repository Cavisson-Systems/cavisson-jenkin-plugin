package com.cavisson.jenkins.ai.testcase.source;

/**
 * Value object returned by JiraSource to signal that the pipeline should be
 * triggered using a JIRA source rather than an uploaded file.
 *
 * When prdSourceType = "JIRA":
 *   - No file is uploaded to the Cavisson server.
 *   - No fileRef is generated.
 *   - The pipeline trigger payload uses source.type = "JIRA"
 *     and source.epicPattern instead of source.fileRef.
 *
 * The AI server reads the JIRA integration named by integrationName,
 * fetches the epic identified by epicPattern, and generates test cases
 * from the JIRA stories. The plugin never communicates with JIRA directly.
 *
 * Fields:
 *   epicPattern     - JIRA epic key or pattern, e.g. "EM-527"
 *   integrationName - Name of the saved JIRA integration in Cavisson, e.g. "JIRA_CONFIG_TEST"
 */
public final class JiraSourceRequest {

    private final String epicPattern;
    private final String integrationName;

    public JiraSourceRequest(String epicPattern, String integrationName) {
        this.epicPattern     = epicPattern != null     ? epicPattern.trim()     : "";
        this.integrationName = integrationName != null ? integrationName.trim() : "";
    }

    public String getEpicPattern()     { return epicPattern; }
    public String getIntegrationName() { return integrationName; }

    @Override
    public String toString() {
        return "JiraSourceRequest{epicPattern='" + epicPattern
                + "', integrationName='" + integrationName + "'}";
    }
}
