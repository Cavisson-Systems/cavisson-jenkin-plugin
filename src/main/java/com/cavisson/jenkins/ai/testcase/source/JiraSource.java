package com.cavisson.jenkins.ai.testcase.source;

import com.cavisson.jenkins.ai.testcase.util.PluginLogger;
import hudson.FilePath;
import hudson.model.Run;

import java.io.IOException;
import java.io.PrintStream;

/**
 * Source provider for JIRA-based test case generation.
 *
 * Unlike LocalFileSource and GitSource, JiraSource does NOT produce a
 * FilePath. Instead it validates the JIRA configuration and signals to
 * the execution layer that the pipeline trigger payload should use
 * source.type = "JIRA" with source.epicPattern rather than
 * source.type = "PRD"/"GHERKIN" with source.fileRef.
 *
 * WHAT THIS CLASS DOES
 * ====================
 * 1. Validates that epicPattern and integrationName are not blank.
 * 2. Logs the JIRA source configuration.
 * 3. Returns a JiraSourceRequest object to the caller.
 *
 * WHAT THIS CLASS DOES NOT DO
 * ============================
 * - Does NOT call any JIRA REST API.
 * - Does NOT authenticate to JIRA.
 * - Does NOT fetch stories or epics.
 * - Does NOT upload any file to the Cavisson server.
 *
 * The AI Server handles all JIRA communication using the named integration.
 *
 * HOW IT FITS INTO THE ARCHITECTURE
 * ===================================
 * SourceManager detects prdSourceType = "JIRA" and calls
 * JiraSource.buildRequest() instead of acquire().
 * CavAITestCaseBuilder then calls PayloadBuilder.buildJira() instead of
 * PayloadBuilder.build() to construct the trigger payload without fileRef.
 */
public final class JiraSource {

    private final String epicPattern;
    private final String integrationName;

    public JiraSource(String epicPattern, String integrationName) {
        this.epicPattern     = epicPattern     != null ? epicPattern.trim()     : "";
        this.integrationName = integrationName != null ? integrationName.trim() : "";
    }

    /**
     * Validates JIRA configuration and returns a JiraSourceRequest.
     *
     * @param log Jenkins console PrintStream
     * @return    JiraSourceRequest carrying epicPattern and integrationName
     * @throws IOException if epicPattern or integrationName is blank
     */
    public JiraSourceRequest buildRequest(PrintStream log) throws IOException {
        if (epicPattern.isEmpty()) {
            throw new IOException(
                    "JIRA Epic Pattern is required when Source Type is JIRA. "
                    + "Example: EM-527");
        }
        if (integrationName.isEmpty()) {
            throw new IOException(
                    "Integration Name is required when Source Type is JIRA. "
                    + "Example: JIRA_CONFIG_TEST");
        }

        PluginLogger.logInfo(log, "PRD source: JIRA");
        PluginLogger.logInfo(log, "  Epic Pattern     : " + epicPattern);
        PluginLogger.logInfo(log, "  Integration Name : " + integrationName);
        PluginLogger.logInfo(log, "The AI server will fetch stories from JIRA epic: " + epicPattern);

        return new JiraSourceRequest(epicPattern, integrationName);
    }
}
