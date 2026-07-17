package com.cavisson.jenkins.ai.testcase.source;

import com.cavisson.jenkins.connection.CavServiceConnection;
import hudson.FilePath;
import hudson.model.Run;

import java.io.IOException;
import java.io.PrintStream;

/**
 * Decides which PRD acquisition strategy to use and executes it.
 *
 * The rest of the plugin never needs to know whether the PRD came from
 * a Git repository, a local file upload, or a JIRA integration.
 * SourceManager handles all routing.
 *
 * Source types:
 *   "GIT"   - clone from Git repository         -> returns FilePath
 *   "LOCAL" - browser file upload or workspace  -> returns FilePath
 *   "JIRA"  - JIRA epic via AI server           -> returns null FilePath +
 *                                                   caller reads JiraSourceRequest
 *
 * For GIT and LOCAL, SourceManager.acquire() returns a FilePath and the
 * execution layer uploads the file and uses source.fileRef in the payload.
 *
 * For JIRA, SourceManager.acquire() returns null. The execution layer must
 * call SourceManager.buildJiraRequest() to get the JiraSourceRequest and
 * then call PayloadBuilder.buildJira() instead of PayloadBuilder.build().
 */
public final class SourceManager {

    /** Source type constant for Git repository acquisition. */
    public static final String GIT   = "GIT";

    /** Source type constant for local file upload (default). */
    public static final String LOCAL = "LOCAL";

    /** Source type constant for JIRA epic-based generation. */
    public static final String JIRA  = "JIRA";

    private SourceManager() {}

    /**
     * Returns true if the given source type requires a file upload.
     * Returns false for JIRA (no file upload needed).
     */
    public static boolean requiresFileUpload(String prdSourceType) {
        return !JIRA.equalsIgnoreCase(prdSourceType);
    }

    /**
     * Acquires the PRD file using the appropriate source provider.
     * Returns null when prdSourceType = "JIRA" -- caller must use
     * buildJiraRequest() and PayloadBuilder.buildJira() instead.
     *
     * @param prdSourceType      "GIT", "LOCAL", or "JIRA"
     * @param credential         Service connection (provides Git credentials)
     * @param gitRepoUrl         Git repository URL (used when prdSourceType=GIT)
     * @param gitBranch          Git branch (used when prdSourceType=GIT)
     * @param gitPrdPath         Relative path to PRD in the repo (prdSourceType=GIT)
     * @param prdParameterName   Jenkins file parameter name (prdSourceType=LOCAL)
     * @param prdFileFallback    Legacy workspace path fallback (prdSourceType=LOCAL)
     * @param run                current Jenkins build
     * @param workspace          Jenkins workspace
     * @param log                console output
     * @return                   FilePath of the acquired PRD file, or null for JIRA
     */
    public static FilePath acquire(String                 prdSourceType,
                                   CavServiceConnection credential,
                                   String                 gitRepoUrl,
                                   String                 gitBranch,
                                   String                 gitPrdPath,
                                   String                 prdParameterName,
                                   String                 prdFileFallback,
                                   Run<?, ?>              run,
                                   FilePath               workspace,
                                   PrintStream            log)
            throws IOException, InterruptedException {

        // JIRA source: no file acquisition needed
        // Caller must call buildJiraRequest() separately
        if (JIRA.equalsIgnoreCase(prdSourceType)) {
            return null;
        }

        PrdSource source;

        if (GIT.equalsIgnoreCase(prdSourceType)
                && gitRepoUrl != null
                && !gitRepoUrl.trim().isEmpty()) {

            source = new GitSource(gitRepoUrl, gitBranch, gitPrdPath, credential);

        } else {
            // Default: LOCAL file upload or workspace file
            source = new LocalFileSource(prdParameterName, prdFileFallback);
        }

        return source.acquire(run, workspace, log);
    }

    /**
     * Builds a JiraSourceRequest for JIRA-based pipeline execution.
     * Only call this when prdSourceType = "JIRA".
     *
     * @param epicPattern     JIRA epic key or pattern, e.g. "EM-527"
     * @param integrationName Saved JIRA integration name in Cavisson, e.g. "JIRA_CONFIG_TEST"
     * @param log             Jenkins console PrintStream
     * @return                JiraSourceRequest with validated fields
     * @throws IOException    if epicPattern or integrationName is blank
     */
    public static JiraSourceRequest buildJiraRequest(String      epicPattern,
                                                     String      integrationName,
                                                     PrintStream log)
            throws IOException {
        return new JiraSource(epicPattern, integrationName).buildRequest(log);
    }
}
