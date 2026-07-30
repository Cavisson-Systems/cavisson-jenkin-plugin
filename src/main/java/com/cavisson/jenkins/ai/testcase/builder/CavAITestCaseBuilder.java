package com.cavisson.jenkins.ai.testcase.builder;

import com.cavisson.jenkins.ai.testcase.client.CavAIRestClient;
import com.cavisson.jenkins.ai.testcase.client.PipelineState;
import com.cavisson.jenkins.ai.testcase.client.UserNameService;
import com.cavisson.jenkins.ai.testcase.exception.CavAIApiException;
import com.cavisson.jenkins.ai.testcase.source.JiraSourceRequest;
import com.cavisson.jenkins.ai.testcase.source.SourceManager;
import com.cavisson.jenkins.ai.testcase.util.CredentialUtil;
import com.cavisson.jenkins.ai.testcase.util.PayloadBuilder;
import com.cavisson.jenkins.ai.testcase.util.PluginLogger;
import com.cavisson.jenkins.connection.CavServiceConnection;
import com.cavisson.jenkins.log.CavLogger;
import com.cavisson.jenkins.log.CavLogLevel;
import com.cavisson.jenkins.env.CavissonEnvironmentPublisher;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Jenkins Builder and Pipeline DSL step cavAITestCaseGeneration().
 *
 * "userName" has NO pipeline-step input at all - there is no field, setter,
 * or UI control for it. It is always resolved by
 * {@link com.cavisson.jenkins.ai.testcase.client.UserNameService}, which
 * calls the Dashboard Server's getUserNameFromToken API using the Cav Token
 * stored in the Service Connection. The authenticated Cav Token is the
 * single source of truth for userName; the pipeline can never override it.
 *
 * If the Dashboard Server call itself fails (HTTP error, unparsable
 * response) the build aborts before any upload/trigger REST call is made.
 * If the call succeeds but returns no username, UserNameService falls back
 * to "cavisson" and logs a warning - the build is not aborted for that case.
 *
 * Older Jenkinsfiles that still pass a `userName:` argument to
 * cavAITestCaseGeneration(...) will now fail with a Groovy "unexpected
 * named argument" error - this is intentional per the plugin's design:
 * authentication identity must come only from the Cav Token, never from
 * pipeline input.
 *
 * Supports three PRD source types:
 *
 *   prdSourceType = "LOCAL" (default)
 *     - File uploaded via Jenkins Build with Parameters
 *     - OR legacy workspace file path (prdFile field)
 *     - Calls uploadFile() then triggerPipeline() with source.fileRef
 *
 *   prdSourceType = "GIT"
 *     - Clones Git repository, locates PRD at gitPrdPath
 *     - Calls uploadFile() then triggerPipeline() with source.fileRef
 *
 *   prdSourceType = "JIRA" (NEW in v9)
 *     - Skips file acquisition entirely (no clone, no upload)
 *     - Calls triggerPipeline() directly with:
 *         source.type = "JIRA"
 *         source.epicPattern = jiraEpicPattern
 *         integrationName = jiraIntegrationName
 *     - The AI server fetches JIRA stories and generates test cases
 *
 * New fields for JIRA (all optional, backward compatible):
 *   jiraEpicPattern      - JIRA epic key, e.g. "EM-527"
 *   jiraIntegrationName  - Saved JIRA integration in Cavisson, e.g. "JIRA_CONFIG_TEST"
 */
public class CavAITestCaseBuilder extends Builder implements SimpleBuildStep {

    private static final Logger LOGGER =
            Logger.getLogger(CavAITestCaseBuilder.class.getName());

    private static final long POLL_INTERVAL_MS = 10_000L;

    // -- Defaults ---------------------------------------------------------------

    static final String DEFAULT_WORKSPACE_ROOT = "/home/cavisson/work";
    static final String DEFAULT_PROJECT        = "default";
    static final String DEFAULT_SUB_PROJECT    = "default";
    static final String DEFAULT_CONTROLLER     = "work";
    static final int    DEFAULT_TEST_CASES     = 1;

    private static final AtomicLong TEST_SUITE_SEQ = new AtomicLong();

    // -- Connection (required, no default) --------------------------------------

    private String cavServiceConnectionId;

    // -- Application under test (required, no default) --------------------------

    /**
     * URL of the application under test. Supplied per-pipeline since it
     * typically varies per run, unlike the Cavisson connection itself.
     * Required; validated in {@link #validateParameters(CavServiceConnection)}.
     */
    private String applicationUrl = "";

    // -- Fields with plugin-provided defaults ------------------------------------

    private String workspaceRoot     = DEFAULT_WORKSPACE_ROOT;
    private String project           = DEFAULT_PROJECT;
    private String subProject        = DEFAULT_SUB_PROJECT;
    private int    numberOfTestCases = DEFAULT_TEST_CASES;
    private String controllerName    = DEFAULT_CONTROLLER;

    // -- PRD source fields -----------------------------------------------------

    private String prdSourceType    = SourceManager.LOCAL;
    private String gitRepoUrl       = "";
    private String gitBranch        = "main";
    private String gitPrdPath       = "";
    private String prdParameterName = "PRD_FILE_UPLOAD";
    private String prdFile          = "";

    // -- Git credentials (per-job Execution Source fields; override the Service Connection's) --

    private String gitProvider   = "";
    private String gitUsername   = "";
    private Secret gitCredential;

    // -- JIRA source fields (NEW in v9) ----------------------------------------

    /**
     * JIRA epic key or pattern used when prdSourceType = "JIRA".
     * Example: "EM-527"
     * The AI server fetches all stories under this epic from JIRA.
     */
    private String jiraEpicPattern = "";

    /**
     * Name of the saved JIRA integration in Cavisson used when prdSourceType = "JIRA".
     * Example: "JIRA_CONFIG_TEST"
     * This tells the AI server which JIRA connection configuration to use.
     */
    private String jiraIntegrationName = "";

    // -- Other optional fields -------------------------------------------------

    private String       sourceType           = "PRD";
    private String       testSuiteName        = "";
    private String       username             = "";
    private String       password             = "";
    private String       authenticationPrompt = "";
    private List<String> tags                 = new ArrayList<>();
    private String        logLevel             = "INFO";

    // -- Constructor -----------------------------------------------------------

    /**
     * Only the Service Connection is mandatory. Every other pipeline
     * parameter (workspaceRoot, project, subProject, controllerName,
     * numberOfTestCases, testSuiteName, tags, ...) has a plugin-provided
     * default and can be overridden via DataBoundSetters. userName has no
     * pipeline input at all - see the class-level javadoc.
     */
    @DataBoundConstructor
    public CavAITestCaseBuilder(String cavServiceConnectionId) {
        this.cavServiceConnectionId = cavServiceConnectionId;
    }

    // -- DataBoundSetters ------------------------------------------------------

    @DataBoundSetter public void setCavServiceConnectionId(String v)
    { this.cavServiceConnectionId = v; }
    @DataBoundSetter public void setApplicationUrl(String v)
    { this.applicationUrl = v != null ? v.trim() : ""; }
    /** @deprecated use {@link #setCavServiceConnectionId(String)}; kept for backward compatibility. */
    @Deprecated
    @DataBoundSetter public void setCavConnection(String v)
    { this.cavServiceConnectionId = v; }
    @DataBoundSetter public void setWorkspaceRoot(String v)
    { this.workspaceRoot = (v != null && !v.trim().isEmpty()) ? v.trim() : DEFAULT_WORKSPACE_ROOT; }
    @DataBoundSetter public void setProject(String v)
    { this.project = (v != null && !v.trim().isEmpty()) ? v.trim() : DEFAULT_PROJECT; }
    @DataBoundSetter public void setSubProject(String v)
    { this.subProject = (v != null && !v.trim().isEmpty()) ? v.trim() : DEFAULT_SUB_PROJECT; }
    @DataBoundSetter public void setControllerName(String v)
    { this.controllerName = (v != null && !v.trim().isEmpty()) ? v.trim() : DEFAULT_CONTROLLER; }
    @DataBoundSetter public void setNumberOfTestCases(int v)
    { this.numberOfTestCases = v > 0 ? v : DEFAULT_TEST_CASES; }
    @DataBoundSetter public void setPrdSourceType(String v)
    { this.prdSourceType = (v != null && !v.trim().isEmpty()) ? v.trim().toUpperCase() : SourceManager.LOCAL; }
    @DataBoundSetter public void setGitRepoUrl(String v)
    { this.gitRepoUrl = v != null ? v.trim() : ""; }
    @DataBoundSetter public void setGitBranch(String v)
    { this.gitBranch = (v != null && !v.trim().isEmpty()) ? v.trim() : "main"; }
    @DataBoundSetter public void setGitPrdPath(String v)
    { this.gitPrdPath = v != null ? v.trim() : ""; }
    @DataBoundSetter public void setPrdParameterName(String v)
    { this.prdParameterName = (v != null && !v.trim().isEmpty()) ? v.trim() : "PRD_FILE_UPLOAD"; }
    @DataBoundSetter public void setPrdFile(String v)
    { this.prdFile = v != null ? v.trim() : ""; }
    @DataBoundSetter public void setGitProvider(String v)
    { this.gitProvider = v != null ? v.trim() : ""; }
    @DataBoundSetter public void setGitUsername(String v)
    { this.gitUsername = v != null ? v.trim() : ""; }
    @DataBoundSetter public void setGitCredential(Secret v)
    { this.gitCredential = v; }
    @DataBoundSetter public void setJiraEpicPattern(String v)
    { this.jiraEpicPattern = v != null ? v.trim() : ""; }
    @DataBoundSetter public void setJiraIntegrationName(String v)
    { this.jiraIntegrationName = v != null ? v.trim() : ""; }
    @DataBoundSetter public void setSourceType(String v)
    { this.sourceType = v; }
    @DataBoundSetter public void setTestSuiteName(String v)
    { this.testSuiteName = (v != null && !v.trim().isEmpty()) ? v.trim() : ""; }
    @DataBoundSetter public void setUsername(String v)
    { this.username = v; }
    @DataBoundSetter public void setPassword(String v)
    { this.password = v; }
    @DataBoundSetter public void setAuthenticationPrompt(String v)
    { this.authenticationPrompt = v; }
    @DataBoundSetter public void setTags(List<String> v)
    { this.tags = v != null ? v : new ArrayList<>(); }
    /**
     * Console verbosity for the Agentic AI pipeline event stream (INFO/DEBUG/ERROR, default
     * INFO). Distinct from the {@code LOG_LEVEL} environment variable, which continues to
     * govern this plugin's own REST/debug diagnostics unchanged.
     */
    @DataBoundSetter public void setLogLevel(String v)
    { this.logLevel = (v != null && !v.trim().isEmpty()) ? v.trim().toUpperCase() : "INFO"; }

    /**
     * Freestyle-UI-only bridge to {@link #setTags(List)}: f:repeatable does not reliably bind a
     * List&lt;String&gt; (it expects a Describable item type), so the config.jelly renders a
     * plain multi-line textarea instead and this setter splits it into the same {@code tags}
     * list used by the Pipeline API ({@code tags: ['a=b', 'c=d']} still calls
     * {@link #setTags(List)} directly, unaffected by this).
     */
    @DataBoundSetter public void setTagsText(String v) {
        List<String> parsed = new ArrayList<>();
        if (v != null) {
            for (String line : v.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    parsed.add(trimmed);
                }
            }
        }
        this.tags = parsed;
    }

    // -- Getters ---------------------------------------------------------------

    public String       getCavServiceConnectionId() { return cavServiceConnectionId; }
    public String       getApplicationUrl()       { return applicationUrl; }
    /** @deprecated use {@link #getCavServiceConnectionId()}; kept for backward compatibility. */
    @Deprecated
    public String       getCavConnection()        { return cavServiceConnectionId; }
    public String       getWorkspaceRoot()        { return workspaceRoot; }
    public String       getProject()              { return project; }
    public String       getSubProject()           { return subProject; }
    public int          getNumberOfTestCases()    { return numberOfTestCases; }
    public String       getControllerName()       { return controllerName; }
    public String       getPrdSourceType()        { return prdSourceType; }
    public String       getGitRepoUrl()           { return gitRepoUrl; }
    public String       getGitBranch()            { return gitBranch; }
    public String       getGitPrdPath()           { return gitPrdPath; }
    public String       getPrdParameterName()     { return prdParameterName; }
    public String       getPrdFile()              { return prdFile; }
    public String       getGitProvider()          { return gitProvider; }
    public String       getGitUsername()          { return gitUsername; }
    public Secret        getGitCredential()        { return gitCredential; }
    /** Plaintext PAT/SSH key, for passing into GitSource - never logged. */
    public String        getGitCredentialPlain()   { return gitCredential == null ? "" : gitCredential.getPlainText(); }
    public String       getJiraEpicPattern()      { return jiraEpicPattern; }
    public String       getJiraIntegrationName()  { return jiraIntegrationName; }
    public String       getSourceType()           { return sourceType; }
    public String       getTestSuiteName() {
        if (testSuiteName == null || testSuiteName.trim().isEmpty()) {
            testSuiteName = "AI_TestSuite_" + System.currentTimeMillis()
                    + "_" + TEST_SUITE_SEQ.incrementAndGet();
        }
        return testSuiteName;
    }
    public String       getUsername()             { return username; }
    public String       getPassword()             { return password; }
    public String       getAuthenticationPrompt() { return authenticationPrompt; }
    public List<String> getTags()                 { return Collections.unmodifiableList(tags); }
    public String        getTagsText()             { return String.join("\n", tags); }
    public String        getLogLevel()             { return logLevel; }

    public String getUploadDestination() {
        String root = workspaceRoot.endsWith("/")
                ? workspaceRoot.substring(0, workspaceRoot.length() - 1)
                : workspaceRoot;
        return root + "/agenticAi/aiTestCases";
    }

    // -- Core execution --------------------------------------------------------

    @Override
    public void perform(@NonNull Run<?, ?>    run,
                        @NonNull FilePath     workspace,
                        @NonNull EnvVars      env,
                        @NonNull Launcher     launcher,
                        @NonNull TaskListener listener)
            throws InterruptedException, IOException {

        CavLogger   log       = new CavLogger(listener, env);
        long        startTime = System.currentTimeMillis();

        // Resolve credential
        CavServiceConnection credential;
        try {
            credential = CredentialUtil.findById(cavServiceConnectionId, run.getParent());
        } catch (IllegalArgumentException e) {
            log.error(e.getMessage());
            throw new AbortException(e.getMessage());
        }

        String serverUrl = credential.getBaseUrl();
        String cavToken  = credential.getApiToken().getPlainText();
        String buildTag  = env.get("BUILD_TAG",    "jenkins-" + run.getId());
        String jobName   = env.get("JOB_NAME",     "unknown-job");

        log.debug("Job: " + jobName);
        log.debug("Server: " + serverUrl);
        log.debug("Source Type: " + sourceType);
        log.debug("Epic Pattern: " + jiraEpicPattern);
        log.debug("Integration Name: " + jiraIntegrationName);
        log.debug("Idempotency Key: " + buildTag);
        log.debug("PRD Source Type: " + prdSourceType);

        // -- Validate ----------------------------------------------------------
        try {
            validateParameters(credential);
        } catch (IllegalArgumentException e) {
            log.error("Validation failed: " + e.getMessage());
            throw new AbortException("Validation failed: " + e.getMessage());
        }
        log.debug("Validation passed");

        // -- Resolve userName dynamically from Cav Token -----------------------
        // Lookup failure is non-fatal: falls back to the default userName
        // "cavisson" and the pipeline continues (upload / trigger / poll /
        // download proceed normally). UserNameService.fetchUserName() itself
        // is unchanged - this only changes how its failure is handled here.
        String userName;
        try {
            userName = new UserNameService().fetchUserName(serverUrl, cavToken, log);
        } catch (CavAIApiException e) {
            log.warn("Unable to retrieve username from Cav Token (" + e.getMessage() + ").");
            log.warn("Falling back to default userName: cavisson.");
            userName = "cavisson";
            log.info("Continuing pipeline execution...");
        }

        CavAIRestClient client = new CavAIRestClient();
        String payload;

        // ======================================================================
        // JIRA SOURCE: skip file acquisition and upload entirely
        // ======================================================================
        if (SourceManager.JIRA.equalsIgnoreCase(prdSourceType)) {

            // Build JiraSourceRequest (validates epicPattern and integrationName)
            JiraSourceRequest jiraRequest;
            try {
                jiraRequest = SourceManager.buildJiraRequest(
                        jiraEpicPattern, jiraIntegrationName, log);
            } catch (IOException e) {
                log.error("JIRA source error: " + e.getMessage());
                throw new AbortException("JIRA source error: " + e.getMessage());
            }

            // Epic Integration shows no source-specific fields - skips file acquisition and
            // upload entirely, same as the original implementation.
            try {
                payload = PayloadBuilder.buildJira(this, credential, jiraRequest, buildTag, userName);
            } catch (Exception e) {
                log.error("Payload error: " + e.getMessage());
                throw new AbortException("Payload error: " + e.getMessage());
            }

        // ======================================================================
        // LOCAL / GIT SOURCE: acquire file, upload, build standard payload
        // ======================================================================
        } else {

            // Acquire PRD file via SourceManager (LOCAL or GIT)
            FilePath resolvedFilePath;
            try {
                resolvedFilePath = SourceManager.acquire(
                        prdSourceType, credential,
                        gitRepoUrl, gitBranch, gitPrdPath,
                        gitUsername, getGitCredentialPlain(),
                        prdParameterName, prdFile,
                        run, workspace, log);
            } catch (IOException e) {
                log.error("PRD acquisition failed: " + e.getMessage());
                throw new AbortException("PRD acquisition failed: " + e.getMessage());
            }

            // Upload PRD to Cavisson server
            File localFile = new File(resolvedFilePath.getRemote());
            log.info("Uploading PRD: " + localFile.getName());
            log.debug("Upload destination: " + getUploadDestination());

            String uploadedFilename;
            try {
                uploadedFilename = client.uploadFile(
                        serverUrl, cavToken, localFile, getUploadDestination(), log);
            } catch (CavAIApiException e) {
                LOGGER.log(Level.SEVERE, "Upload failed", e);
                log.error("Upload failed: " + e.getMessage());
                throw new AbortException("PRD upload failed: " + e.getMessage());
            }
            log.info("Upload Successful  -  " + uploadedFilename);

            // Build standard payload with fileRef
            try {
                payload = PayloadBuilder.build(this, credential, uploadedFilename, buildTag, userName);
            } catch (Exception e) {
                log.error("Payload error: " + e.getMessage());
                throw new AbortException("Payload error: " + e.getMessage());
            }
        }

        // ======================================================================
        // COMMON: Trigger, Poll, Stream Events, Summary
        // Identical for all three source types
        // ======================================================================

        log.debug("Triggering AI Pipeline...");
        log.debug("Test Suite: " + testSuiteName
                + "  Test Cases: " + numberOfTestCases
                + "  Project: " + project + "/" + subProject);

        String pipelineId;
        try {
            pipelineId = client.triggerPipeline(serverUrl, cavToken, payload, log);
        } catch (CavAIApiException e) {
            LOGGER.log(Level.SEVERE, "Trigger failed", e);
            log.error("Trigger failed: " + e.getMessage());
            throw new AbortException("Trigger failed: " + e.getMessage());
        }

        // Export the Pipeline ID as an env var too (existing mechanism, reused unchanged) so
        // later Pipeline/Freestyle steps can reference ${CAV_AI_PIPELINE_ID}.
        Map<String, String> envVars = new LinkedHashMap<>();
        envVars.put("CAV_AI_PIPELINE_ID", pipelineId);
        CavissonEnvironmentPublisher.publish(run, envVars);

        // Console narration of the trigger itself is intentionally DEBUG-only: the backend's
        // own "Pipeline started (...)" event (streamed via pollAndStream -> streamNewEvents)
        // is the INFO-level line the Jenkins console shows for this milestone.
        log.debug("Pipeline ID: " + pipelineId);


        CavLogLevel selectedLevel = CavLogLevel.fromString(logLevel);

        Set<String> printedLines = new LinkedHashSet<>();
        String      progressJson = null;

        try {
            progressJson = pollAndStream(client, serverUrl, pipelineId, log, printedLines, selectedLevel);
        } catch (AbortException ae) {
            try {
                String failJson = client.downloadProgress(serverUrl, pipelineId, log);
                PluginLogger.printFailureSummary(log,
                        jf(failJson, "errorStage"),
                        jf(failJson, "error"));
            } catch (CavAIApiException ex) {
                log.warn("Could not download progress: " + ex.getMessage());
            }
            throw ae;
        } catch (InterruptedException ie) {
            PluginLogger.printAbort(log, pipelineId);
            try {
                client.abortPipeline(serverUrl, pipelineId, log);
                log.info("Abort signal sent to backend");
            } catch (CavAIApiException abortEx) {
                log.warn("Abort failed: " + abortEx.getMessage());
            }
            Thread.currentThread().interrupt();
            throw ie;
        }

        long durationSeconds = (System.currentTimeMillis() - startTime) / 1000;

        String testsuiteName = progressJson != null ? jf(progressJson, "testsuiteName") : testSuiteName;
        String testsuiteUrl  = progressJson != null ? jf(progressJson, "testsuiteUrl")  : "";
        // The progress response doesn't always carry testsuiteUrl back - whenever a suite was
        // actually generated (testsuiteName is non-empty) but the server left the URL out,
        // build the link ourselves from the same UnifiedDashboard share-link pattern the server
        // itself uses, rather than silently dropping the link.
        if (testsuiteUrl.isEmpty() && !testsuiteName.isEmpty()) {
            testsuiteUrl = serverUrl.replaceAll("/+$", "")
                    + "/UnifiedDashboard/share.html?open=testsuite&ts=" + project + "/" + subProject + "/" + testsuiteName;
        }
        if (!testsuiteUrl.isEmpty()) {
            log.infoHyperlink("        Test Suite : ", testsuiteUrl, "View Test Suite");
        }

        // The backend's own "Pipeline finished with state=COMPLETED (...)" event (already
        // streamed via pollAndStream -> streamNewEvents) is the INFO-level line the console
        // shows for completion; this summary stays DEBUG-only diagnostics.
        PluginLogger.printFinalSummary(log, project, subProject,
                SourceManager.JIRA.equalsIgnoreCase(prdSourceType) ? "JIRA" : sourceType,
                testsuiteName,
                testsuiteUrl,
                progressJson != null ? jf(progressJson, "storyCount")      : "0",
                progressJson != null ? jf(progressJson, "testcaseCount")   : "0",
                progressJson != null ? jf(progressJson, "publishedEpicKey"): "",
                progressJson != null ? jf(progressJson, "batchId")         : "",
                durationSeconds);
    }

    // -- Polling loop -----------------------------------------------------------

    private String pollAndStream(CavAIRestClient client,
                                 String          serverUrl,
                                 String          pipelineId,
                                 CavLogger       log,
                                 Set<String>     printedLines,
                                 CavLogLevel     selectedLevel)
            throws AbortException, InterruptedException {

        int           elapsedPolls = 0;
        StringBuilder progress     = new StringBuilder();

        while (true) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("Build interrupted");

            try {
                String events = client.downloadEvents(serverUrl, pipelineId, log);
                PluginLogger.streamNewEvents(log, events, printedLines, pipelineId, selectedLevel);
            } catch (CavAIApiException e) {
                log.warn("Events fetch error (will retry): " + e.getMessage());
            }

            CavAIRestClient.StatusResponse status;
            try {
                status = client.getPipelineStatus(serverUrl, pipelineId, log);
            } catch (CavAIApiException e) {
                LOGGER.log(Level.WARNING, "Poll error (will retry)", e);
                log.warn("Poll error: " + e.getMessage() + "  -  retrying in 10s");
                Thread.sleep(POLL_INTERVAL_MS);
                continue;
            }

            log.debug("Poll: stage=" + status.getCurrentStage()
                    + " state=" + status.getState().name());

            PipelineState state = status.getState();

            if (state == PipelineState.COMPLETED) {
                try {
                    PluginLogger.streamNewEvents(log,
                            client.downloadEvents(serverUrl, pipelineId, log),
                            printedLines, pipelineId, selectedLevel);
                } catch (CavAIApiException e) {
                    log.debug("Final events error: " + e.getMessage());
                }
                String progressJson = null;
                try {
                    progressJson = client.downloadProgress(serverUrl, pipelineId, log);
                } catch (CavAIApiException e) {
                    log.warn("Could not fetch progress: " + e.getMessage());
                }
                return progressJson;
            }

            if (state.isFailure()) {
                try {
                    PluginLogger.streamNewEvents(log,
                            client.downloadEvents(serverUrl, pipelineId, log),
                            printedLines, pipelineId, selectedLevel);
                } catch (CavAIApiException e) {
                    log.debug("Final events error: " + e.getMessage());
                }
                String reason = status.getFailureReason();
                log.error("Pipeline " + state.name()
                        + (reason.isEmpty() ? "" : ": " + reason));
                throw new AbortException("Pipeline finished with state: " + state.name()
                        + (reason.isEmpty() ? "" : ". Reason: " + reason));
            }

            // Still running - DEBUG-only heartbeat (diagnostics), every 5 minutes. The INFO
            // console is a live mirror of the backend's own event stream now (streamNewEvents
            // above), so no synthetic "still running" line is added there.
            elapsedPolls++;
            progress.append('.');
            long elapsedSeconds = elapsedPolls * (POLL_INTERVAL_MS / 1000);
            if (elapsedSeconds % 300 == 0) {
                log.debug(String.format("AI Test Case Generation is still running %s (%d min elapsed)",
                        progress, elapsedSeconds / 60));
                progress.setLength(0);
            }

            Thread.sleep(POLL_INTERVAL_MS);
        }
    }

    // -- Validation ------------------------------------------------------------

    void validateParameters(CavServiceConnection credential) {
        requireNonBlank(cavServiceConnectionId, "cavServiceConnectionId");
        requireNonBlank(workspaceRoot,  "workspaceRoot");
        requireNonBlank(project,        "project");
        requireNonBlank(subProject,     "subProject");
        requireNonBlank(controllerName, "controllerName");

        if (numberOfTestCases <= 0)
            throw new IllegalArgumentException("numberOfTestCases must be > 0");

        if (applicationUrl == null || applicationUrl.trim().isEmpty())
            throw new IllegalArgumentException("Application URL is required.");

        // JIRA-specific validation
        if (SourceManager.JIRA.equalsIgnoreCase(prdSourceType)) {
            requireNonBlank(jiraEpicPattern,
                    "jiraEpicPattern (required when prdSourceType=JIRA)");
            requireNonBlank(jiraIntegrationName,
                    "jiraIntegrationName (required when prdSourceType=JIRA)");
            return; // publishUserStories fields are not required for JIRA
        }

        // GIT-specific validation
        if (SourceManager.GIT.equalsIgnoreCase(prdSourceType)) {
            requireNonBlank(gitRepoUrl,  "gitRepoUrl (required when prdSourceType=GIT)");
            requireNonBlank(gitPrdPath,  "gitPrdPath (required when prdSourceType=GIT)");
        }

        // Publish fields validation (LOCAL and GIT only)
        if (credential.isPublishUserStories()) {
            requireNonBlank(credential.getIntegrationName(),
                    "integrationName (Service Connection)");
            requireNonBlank(credential.getPublishTrackerType(),
                    "publishTrackerType (Service Connection)");
            requireNonBlank(credential.getPublishEpicName(),
                    "publishEpicName (Service Connection)");
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException("Required field is missing: " + fieldName);
    }

    private static String jf(String json, String key) {
        if (json == null) return "";
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"").matcher(json);
        if (m.find()) return m.group(1);
        m = Pattern.compile("\"" + key + "\"\\s*:\\s*([^,}\\]]+)").matcher(json);
        return m.find() ? m.group(1).trim() : "";
    }

    // -- Descriptor ------------------------------------------------------------

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Symbol("cavAITestCaseGeneration")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @NonNull @Override
        public String getDisplayName() { return "Cavisson - AI Test Case Generation"; }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) { return true; }

        @POST public FormValidation doCheckCavServiceConnectionId(@QueryParameter String v)
        { return blank(v) ? FormValidation.error("Required.") : FormValidation.ok(); }

        public ListBoxModel doFillCavServiceConnectionIdItems(@AncestorInPath Item item,
                @QueryParameter String cavServiceConnectionId) {
            StandardListBoxModel result = new StandardListBoxModel();

            if (item == null) {
                return result.includeCurrentValue(cavServiceConnectionId);
            }

            return result
                    .includeEmptyValue()
                    .includeAs(ACL.SYSTEM, item, CavServiceConnection.class)
                    .includeCurrentValue(cavServiceConnectionId);
        }
        @POST public FormValidation doCheckApplicationUrl(@QueryParameter String applicationUrl) {
            if (blank(applicationUrl)) {
                return FormValidation.error("Application URL is required.");
            }
            String url = applicationUrl.trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return FormValidation.warning(
                        "Application URL should start with http:// or https://");
            }
            return FormValidation.ok();
        }
        @POST public FormValidation doCheckWorkspaceRoot(@QueryParameter String v)
        { return FormValidation.ok(); }
        @POST public FormValidation doCheckProject(@QueryParameter String v)
        { return FormValidation.ok(); }
        @POST public FormValidation doCheckSubProject(@QueryParameter String v)
        { return FormValidation.ok(); }
        @POST public FormValidation doCheckControllerName(@QueryParameter String v)
        { return FormValidation.ok(); }
        @POST public FormValidation doCheckNumberOfTestCases(@QueryParameter int v)
        { return v < 0 ? FormValidation.error("Must be > 0.") : FormValidation.ok(); }

        @POST public FormValidation doCheckJiraEpicPattern(
                @QueryParameter String jiraEpicPattern,
                @QueryParameter String prdSourceType) {
            if (SourceManager.JIRA.equalsIgnoreCase(prdSourceType)
                    && blank(jiraEpicPattern)) {
                return FormValidation.error(
                        "JIRA Epic Pattern is required when Source Type is JIRA. Example: EM-527");
            }
            return FormValidation.ok();
        }

        @POST public FormValidation doCheckJiraIntegrationName(
                @QueryParameter String jiraIntegrationName,
                @QueryParameter String prdSourceType) {
            if (SourceManager.JIRA.equalsIgnoreCase(prdSourceType)
                    && blank(jiraIntegrationName)) {
                return FormValidation.error(
                        "Integration Name is required when Source Type is JIRA. Example: JIRA_CONFIG_TEST");
            }
            return FormValidation.ok();
        }

        @POST public FormValidation doCheckGitRepoUrl(
                @QueryParameter String gitRepoUrl,
                @QueryParameter String prdSourceType) {
            if (SourceManager.GIT.equalsIgnoreCase(prdSourceType) && blank(gitRepoUrl)) {
                return FormValidation.error("Repository URL is required when Source Type is GIT.");
            }
            return FormValidation.ok();
        }

        @POST public FormValidation doCheckGitPrdPath(
                @QueryParameter String gitPrdPath,
                @QueryParameter String prdSourceType) {
            if (SourceManager.GIT.equalsIgnoreCase(prdSourceType) && blank(gitPrdPath)) {
                return FormValidation.error("PRD File Path is required when Source Type is GIT.");
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillPrdSourceTypeItems() {
            ListBoxModel m = new ListBoxModel();
            m.add("Local File Upload (Default)", SourceManager.LOCAL);
            m.add("Git Repository",              SourceManager.GIT);
            m.add("Epic Integration",            SourceManager.JIRA);
            return m;
        }

        public ListBoxModel doFillSourceTypeItems() {
            ListBoxModel m = new ListBoxModel();
            m.add("JIRA",         "JIRA");
            m.add("AZURE_DEVOPS", "AZURE_DEVOPS");
            return m;
        }

        public ListBoxModel doFillLogLevelItems() {
            ListBoxModel m = new ListBoxModel();
            m.add("INFO (Default)", "INFO");
            m.add("DEBUG",          "DEBUG");
            m.add("ERROR",          "ERROR");
            return m;
        }

        public ListBoxModel doFillGitProviderItems() {
            ListBoxModel m = new ListBoxModel();
            m.add("-- Select (optional) --", "");
            m.add("GitHub",       "GITHUB");
            m.add("GitLab",       "GITLAB");
            m.add("Azure Repos",  "AZURE_REPOS");
            return m;
        }

        private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    }
}

