package com.cavisson.jenkins.accessibility;

import com.cavisson.jenkins.connection.CavissonConnection;
import com.cavisson.jenkins.connection.CavissonConnectionResolver;
import com.cavisson.jenkins.env.CavissonDescriptionPublisher;
import com.cavisson.jenkins.env.CavissonEnvironmentPublisher;
import com.cavisson.jenkins.http.HttpUtil;
import com.cavisson.jenkins.log.CavLogger;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.console.LineTransformationOutputStream;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.ArtifactArchiver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Java port of the Cav Accessibility Scanner Azure DevOps extension's {@code task/index.js},
 * {@code task/scanner/scan.js} and {@code task/scanner/generate-report.js}. Runs the bundled
 * Node.js scanner (Playwright + axe-core) against the configured Application URL, generates the
 * JSON and HTML accessibility reports, and uploads both to the Cavisson server resolved from the
 * existing {@link CavissonConnection} (Service Connection) - the same
 * {@code filemanager/uploadfile} REST endpoint and {@code accessibility_report_data} metadata
 * endpoint the Azure extension calls. Shared by both {@link AccessibilityScannerBuilder}
 * (Freestyle) and {@link AccessibilityScannerStep} (Pipeline), matching the pattern already used
 * by every other task pair in this plugin (see {@code CavissonRunTestExecutor}).
 *
 * <p>The scanner scripts themselves ({@code scan.js}/{@code generate-report.js}/{@code report/*})
 * are copied verbatim from the Azure extension - they are already environment-agnostic, driven
 * only by the {@code REPORTS_DIR}/{@code CAV_SERVER_URL}/{@code CAV_TOKEN} environment variables
 * with no Azure Pipelines-specific API calls - so no scan/report-generation behavior was
 * reinvented here, only the Node process launch, the report upload, and the metadata push (all
 * previously done via {@code axios}/{@code FormData} in {@code index.js}) were re-implemented in
 * Java against the same endpoints, request shape, and headers.
 */
final class AccessibilityScannerExecutor {

    private static final String SCRIPTS_RESOURCE_ROOT = "/scripts/accessibility";
    private static final String SCRIPTS_WORKDIR = ".cav-accessibility-scanner";
    private static final String REPORT_DIRNAME = "cavisson-accessibility-report";

    private static final String HTML_REPORT_FILE = "accessibility-report.html";
    private static final String JSON_REPORT_FILE = "accessibility-report.json";

    private static final String UPLOAD_PATH = "/tomcat/master/DashboardServer/v2/web/filemanager/uploadfile";
    private static final String METADATA_PATH = "/tomcat/master/DashboardServer/v2/config/module/accessibility_report_data/objects";

    // Base for the shareable report link (Cavisson's guest-mode "Copy Link" gateway - see
    // UnifiedDashboard/share.html). 'open=accessibility-report' maps (via SHARE_URL_ROUTE_MAP in
    // session.service.ts) to the Accessibility Test Report History page. That page now reads an
    // optional 'reportId' query param (appended below, per-run) to auto-open that specific
    // report's detail panel; without it, the page just shows the bare list.
    private static final String REPORT_SHARE_LINK_BASE = "/UnifiedDashboard/share.html?open=accessibility-report";

    // Bundled scanner files (relative to both the classpath resource root and the materialized
    // workspace directory) - copied verbatim from the Azure extension's task/scanner directory.
    private static final String[] SCANNER_FILES = {
            "package.json",
            "scan.js",
            "generate-report.js",
            "report/utils.js",
            "report/categoryMapper.js",
            "report/suggestionMap.js",
            "report/screenshotHelper.js",
            "report/reportGenerator.js",
            "report/templates/base.html",
            "report/templates/style.css",
            "report/templates/accordion.js"
    };

    private static final Pattern SAFE_CONTROLLER = Pattern.compile("^[A-Za-z0-9_\\-./]+$");

    /**
     * Cap on how many trailing lines of a child process' output are retained for failure-reason
     * extraction (see {@link #extractFailureReason}), regardless of Log Mode. Bounded so a huge
     * npm/Playwright log can't grow this unboundedly in memory.
     */
    private static final int CAPTURED_LINES_LIMIT = 300;

    private static final Map<String, Integer> SEVERITY_RANK = new LinkedHashMap<>();
    static {
        SEVERITY_RANK.put("critical", 4);
        SEVERITY_RANK.put("serious", 3);
        SEVERITY_RANK.put("moderate", 2);
        SEVERITY_RANK.put("minor", 1);
    }

    private AccessibilityScannerExecutor() {
    }

    static Map<String, Object> run(Run<?, ?> run,
                                    FilePath workspace,
                                    Launcher launcher,
                                    EnvVars env,
                                    TaskListener listener,
                                    String cavConnectionId,
                                    String controller,
                                    String applicationUrl) throws IOException, InterruptedException {
        return run(run, workspace, launcher, env, listener, cavConnectionId, controller, applicationUrl, null);
    }

    /**
     * @param cavConnectionId "Cavisson Service Connection" credential ID. Resolved to an actual
     *                        {@link CavissonConnection} (base URL + API token) here, inside the
     *                        already-constructed {@link CavLogger}'s scope, so a missing/blank ID
     *                        or a credential that can't be found (deleted, wrong ID, no
     *                        permission) prints as a normal {@code [ERROR]} console line like
     *                        every other failure in this task, instead of a raw uncaught
     *                        {@link AbortException} from before the logger existed.
     * @param logLevel Per-task "Log Mode" selection (INFO/DEBUG/ERROR) coming from the
     *                 Freestyle/Pipeline field - independent of the global {@code LOG_LEVEL}
     *                 env var. Fed into a copy of {@code env} before constructing the shared
     *                 {@link CavLogger}, so the existing INFO/DEBUG/ERROR gating in that class
     *                 is reused as-is (no separate logger, no separate log file). Blank/null
     *                 falls back to {@code CavLogger}'s own default (INFO).
     */
    static Map<String, Object> run(Run<?, ?> run,
                                    FilePath workspace,
                                    Launcher launcher,
                                    EnvVars env,
                                    TaskListener listener,
                                    String cavConnectionId,
                                    String controller,
                                    String applicationUrl,
                                    String logLevel) throws IOException, InterruptedException {

        EnvVars effectiveEnv = new EnvVars(env);
        if (logLevel != null && !logLevel.trim().isEmpty()) {
            effectiveEnv.put("LOG_LEVEL", logLevel.trim().toUpperCase(java.util.Locale.ROOT));
        }

        CavLogger log = new CavLogger(listener, effectiveEnv);
        boolean verbose = log.isDebugEnabled();

        // Console-narration lines: shown only when Log Mode = DEBUG, but printed with the same
        // "[INFO ]" label as before (CavLogger#printAt is unconditional by design - see its
        // javadoc - so the DEBUG/INFO decision is made here, once, via `verbose`).
        printMilestone(log, verbose, "Accessibility Scanner started");

        CavissonConnection connection;
        try {
            connection = CavissonConnectionResolver.resolve(
                    run, env, "serviceConnection", null, null, cavConnectionId);
        } catch (AbortException e) {
            log.error(e.getMessage());
            throw e;
        }

        String baseUrl = connection.getBaseUrl();
        String apiToken = connection.getApiToken();
        printMilestone(log, verbose, "Service Connection resolved");
        log.debug("Cavisson Server URL: " + baseUrl);

        String resolvedController;
        String resolvedUrl;
        try {
            resolvedController = validateController(expand(env, controller));
            resolvedUrl = validateApplicationUrl(expand(env, applicationUrl));
        } catch (AbortException e) {
            log.error(e.getMessage());
            throw e;
        }

        printMilestone(log, verbose, "Controller configured: " + resolvedController);
        // Always shown at INFO and above - the one input-echo line kept visible by default.
        log.info("Application URL configured: " + resolvedUrl);

        FilePath scriptsDir = materializeScanner(workspace, log);

        FilePath reportsDir = workspace.child(REPORT_DIRNAME);
        reportsDir.mkdirs();

        // Playwright caches downloaded browsers under $HOME/.cache/ms-playwright by default.
        // $HOME can resolve differently between a Freestyle build's process environment and a
        // Pipeline step's EnvVars context on some Jenkins versions, which makes the "already
        // downloaded, skip re-fetching" check silently look in two different places depending on
        // how the job was run - so a browser cached by one mode is invisible to the other, and
        // every run in whichever mode "loses" that lookup re-downloads Chromium from the network
        // (and can fail/time out if that network path is flaky). Pinning PLAYWRIGHT_BROWSERS_PATH
        // to a fixed folder next to the job workspaces removes the ambiguity entirely: Freestyle
        // and Pipeline builds - and every job - share the exact same on-disk cache from here on,
        // so Chromium is downloaded at most once per agent regardless of which mode triggers it.
        FilePath workspaceParent = workspace.getParent();
        FilePath playwrightCacheDir = workspaceParent != null
                ? workspaceParent.child(".cavisson-playwright-cache")
                : workspace.child(".cavisson-playwright-cache");

        Map<String, String> scanEnv = new LinkedHashMap<>();
        scanEnv.put("REPORTS_DIR", reportsDir.getRemote());
        scanEnv.put("CAV_SERVER_URL", baseUrl);
        scanEnv.put("CAV_TOKEN", apiToken);
        scanEnv.put("PLAYWRIGHT_BROWSERS_PATH", playwrightCacheDir.getRemote());
        log.debug("Playwright browsers cache: " + playwrightCacheDir.getRemote());

        try {
            installScannerDependencies(launcher, listener, log, verbose, scriptsDir, env, scanEnv);

            printMilestone(log, verbose, "Accessibility scan started");
            // INFO-mode-only heartbeat: without this, INFO mode goes straight from "Application
            // URL configured" to "Report : ..." with a silent gap while npm install/Playwright/
            // scan.js actually run (which can take anywhere from seconds to a couple of minutes) -
            // easy to mistake for a stuck build. DEBUG mode already shows the real, live scanner
            // output during this same window (see CapturingLineForwarder), so this line would be
            // redundant noise there - only print it when NOT verbose (i.e. Log Mode = INFO; ERROR
            // mode already excludes all log.info(...) lines via CavLogger's own gating, so no
            // separate check is needed for that case).
            if (!verbose) {
                log.info("Scanning in progress...");
            }
            runNode(launcher, listener, log, verbose, scriptsDir, env, scanEnv,
                    Arrays.asList("scan.js", resolvedUrl), "Accessibility Scan");
        } catch (IOException | InterruptedException e) {
            log.error("Accessibility scan failed: " + friendlyReason(e));
            throw e;
        }

        FilePath jsonReport = reportsDir.child(JSON_REPORT_FILE);
        if (!jsonReport.exists()) {
            log.error("Accessibility scan failed: " + JSON_REPORT_FILE + " was not produced. "
                    + "Check that the Application URL is reachable from the Jenkins agent and that "
                    + "the bundled scanner (Node.js/Playwright) installed correctly - see the "
                    + "DEBUG log mode for full scanner output.");
            throw new AbortException("Accessibility scan failed: " + JSON_REPORT_FILE + " was not produced.");
        }
        printMilestone(log, verbose, "Accessibility scan completed successfully");

        try {
            log.debug("Preparing accessibility report generation");
            runNode(launcher, listener, log, verbose, scriptsDir, env, scanEnv,
                    Collections.singletonList("generate-report.js"), "Accessibility Report Generation");
        } catch (IOException | InterruptedException e) {
            log.error("Accessibility report generation failed: " + friendlyReason(e));
            throw e;
        }

        FilePath htmlReport = reportsDir.child(HTML_REPORT_FILE);
        if (!htmlReport.exists()) {
            log.error("Accessibility report generation failed: " + HTML_REPORT_FILE + " was not produced.");
            throw new AbortException("Accessibility report generation failed: " + HTML_REPORT_FILE + " was not produced.");
        }
        printMilestone(log, verbose, "Accessibility report generated successfully");

        // Archive locally in Jenkins so the reports remain reachable as build artifacts,
        // in addition to (not instead of) the upload to the Cavisson server below. Jenkins'
        // ArtifactArchiver prints its own "Archiving artifacts" line directly to the listener
        // (bypassing CavLogger), so it is routed through TaskListener.NULL outside DEBUG mode
        // to keep it out of the INFO/ERROR console, consistent with everything else here.
        archiveReports(run, workspace, launcher, verbose ? listener : TaskListener.NULL, log);

        JSONObject axeJson = new JSONObject(jsonReport.readToString());
        String highestSeverity = highestSeverity(axeJson);
        JSONArray violations = axeJson.optJSONArray("violations");
        int violationsCount = violations == null ? 0 : violations.length();

        String reportFolder = "accessibility-" + System.currentTimeMillis();

        // Absolute base path for this run's reports, e.g. /home/cavisson/work/logs/accessibility/accessibility-<ts>.
        // Derived from NS_WDIR (falling back to /home/cavisson/work) rather than the Controller
        // field, so it's always a real absolute filesystem path regardless of what the user typed
        // into Controller (which defaults to "/home/cavisson/work" and could otherwise have been
        // previously risked being concatenated into a duplicated .../home/cavisson/work/home/cavisson/work/...
        // path). The exact same string is used below for both where the report files are actually
        // written on the Cavisson server (the upload `destination`) and the DB record
        // (accessibility_report_data's reports.html/reports.json) - per requirement, those two must
        // never diverge. Folder-name pattern and file names are unchanged; Controller is now
        // validated against this exact expected path (see EXPECTED_CONTROLLER) precisely because
        // it no longer feeds path construction - a mismatch would otherwise go silently unnoticed.
        String basePath = resolveReportBasePath(env, reportFolder);

        String uploadUrl = baseUrl.replaceAll("/+$", "") + UPLOAD_PATH
                + "?destination=" + basePath
                + "&fileExplorer=test";

        Map<String, String> uploadHeaders = new LinkedHashMap<>();
        uploadHeaders.put("Authorization", "Bearer " + apiToken);
        uploadHeaders.put("Accept", "application/json");

        log.debug("Preparing accessibility report upload");
        printMilestone(log, verbose, "Uploading accessibility report to Cavisson server");
        log.debug("Upload request initialized");

        uploadReportFile(log, uploadUrl, htmlReport, uploadHeaders, "HTML");
        uploadReportFile(log, uploadUrl, jsonReport, uploadHeaders, "JSON");

        log.debug("Upload response received");
        printMilestone(log, verbose, "Accessibility report uploaded successfully");

        String finalHtmlPath = basePath + "/" + HTML_REPORT_FILE;
        String finalJsonPath = basePath + "/" + JSON_REPORT_FILE;

        // publishReportMetadata already logs its own [ERROR] line (with the exact HTTP/IO
        // failure reason) before throwing - not duplicated here.
        String reportId = publishReportMetadata(log, baseUrl, apiToken, resolvedUrl, run,
                highestSeverity, finalHtmlPath, finalJsonPath);

        // Shareable report link (guest-mode "Copy Link" gateway) - per-run deep link straight to
        // this build's report detail panel (see REPORT_SHARE_LINK_BASE above; reportId appended).
        // baseUrl already has any trailing slash stripped a few lines up when building uploadUrl,
        // but strip again defensively since this is built independently.
        String reportShareUrl = baseUrl.replaceAll("/+$", "") + REPORT_SHARE_LINK_BASE
                + "&reportId=" + reportId;

        Map<String, String> envVars = new LinkedHashMap<>();
        envVars.put("CAV_ACCESSIBILITY_STATUS", "SUCCESS");
        envVars.put("CAV_ACCESSIBILITY_HIGHEST_SEVERITY", highestSeverity);
        envVars.put("CAV_ACCESSIBILITY_VIOLATIONS_COUNT", String.valueOf(violationsCount));
        envVars.put("CAV_ACCESSIBILITY_REPORT_ID", reportId);
        envVars.put("CAV_ACCESSIBILITY_REPORT_FOLDER", reportFolder);
        envVars.put("CAV_ACCESSIBILITY_REPORT_URL", reportShareUrl);
        CavissonEnvironmentPublisher.publish(run, envVars);

        // Shareable report link - surfaced three ways:
        //   1) A clickable line in the console log (log.infoHyperlink, via Jenkins'
        //      HyperlinkNote) - always renders as a real link regardless of instance config.
        //   2) A row appended to the build's own HTML description
        //      (CavissonDescriptionPublisher), same mechanism used by CavissonRunTestExecutor /
        //      AnalyseTestFailureExecutor. NOTE: this one only *renders* as a clickable link if
        //      the Jenkins instance's global Markup Formatter (Manage Jenkins > Security) is set
        //      to something that renders HTML (e.g. "Safe HTML") - under the default "Plain
        //      Text" formatter, run.setDescription(...) content is shown as literal escaped
        //      text, not a link. Kept for parity with the other tasks, but not relied on alone.
        //   3) AccessibilityReportAction, a proper Jenkins Action (sidebar entry + build-page
        //      summary link, target="_blank") - Jelly-rendered, so unaffected by the Markup
        //      Formatter setting above; this is the mechanism guaranteed to work regardless of
        //      instance configuration.
        // None of these failing should fail the build - the scan itself already succeeded.
        log.infoHyperlink("Report : ", reportShareUrl, "View Accessibility Report");
        try {
            CavissonDescriptionPublisher.appendReportRow(run, env, reportShareUrl, "Accessibility Scan");
        } catch (IOException e) {
            log.debug("Could not add report link to build description: " + e.getMessage());
        }
        run.addAction(new AccessibilityReportAction(reportShareUrl));

        // Always shown at INFO and above - the second (and last) input/output line kept visible
        // by default, matching "Application URL configured" above.
        log.info("Accessibility process completed");

        // The severity summary line is narration, not a required INFO line - only surfaced in
        // DEBUG mode, printed raw (no "[LEVEL]" prefix) exactly as the caller's previous
        // unconditional println did.
        if (verbose) {
            log.raw("Cavisson Accessibility Scan finished with highest severity: " + highestSeverity);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("reportId", reportId);
        result.put("highestSeverity", highestSeverity);
        result.put("violationsCount", violationsCount);
        result.put("reportFolder", reportFolder);
        result.put("reportUrl", reportShareUrl);
        result.put("htmlReportPath", htmlReport.getRemote());
        result.put("jsonReportPath", jsonReport.getRemote());
        return result;
    }

    /** Prints a "[INFO ] message" narration line, gated on Log Mode = DEBUG (see {@link #run}). */
    private static void printMilestone(CavLogger log, boolean verbose, String message) {
        if (verbose) {
            log.printAt("INFO", message);
        }
    }

    /** Shortest usable failure reason for the always-visible [ERROR] line - avoids dumping a full stack trace inline. */
    private static String friendlyReason(Exception e) {
        String message = e.getMessage();
        return (message == null || message.trim().isEmpty()) ? e.getClass().getSimpleName() : message;
    }

    /**
     * Rewrites a raw HTTP/network failure message (as thrown by {@link HttpUtil#postMultipartFile}
     * / {@link HttpUtil#postJson}, e.g. {@code "HTTP 401 uploading to ...: <body>"}, or a raw
     * {@link java.io.IOException} message from a lower-level connection failure) into a plain-English
     * reason naming the likely cause, with the original detail kept alongside for debugging. Used
     * for both the report upload and the metadata publish calls, since both hit the same Cavisson
     * server and can fail the same ways.
     */
    private static String describeHttpFailure(String rawMessage) {
        if (rawMessage == null || rawMessage.trim().isEmpty()) {
            return "unknown error";
        }
        if (rawMessage.contains("HTTP 401") || rawMessage.contains("HTTP 403")) {
            return "Cavisson API token appears to be invalid or expired - check the credential used in "
                    + "the Cavisson Service Connection. (" + rawMessage + ")";
        }
        if (rawMessage.contains("HTTP 404")) {
            return "Cavisson server endpoint not found (HTTP 404) - check the Base URL in the Service "
                    + "Connection. (" + rawMessage + ")";
        }
        if (rawMessage.matches("(?s).*HTTP 5\\d\\d.*")) {
            return "Cavisson server returned an internal error - the server may be down or misconfigured. "
                    + "(" + rawMessage + ")";
        }
        String lower = rawMessage.toLowerCase(java.util.Locale.ROOT);
        if (rawMessage.contains("UnknownHostException") || lower.contains("no such host")) {
            return "Could not resolve the Cavisson server hostname - check the Base URL in the Service "
                    + "Connection. (" + rawMessage + ")";
        }
        if (rawMessage.contains("ConnectException") || lower.contains("connection refused")) {
            return "Could not connect to the Cavisson server (connection refused) - check the Base URL/port "
                    + "and that the server is reachable from the Jenkins agent. (" + rawMessage + ")";
        }
        if (lower.contains("timed out") || rawMessage.contains("SocketTimeoutException")) {
            return "Connection to the Cavisson server timed out - check network connectivity from the "
                    + "Jenkins agent to the server. (" + rawMessage + ")";
        }
        if (rawMessage.contains("SSLHandshakeException") || lower.contains("certificate")) {
            return "TLS/certificate error connecting to the Cavisson server - check the Base URL (http vs "
                    + "https) and the server's certificate. (" + rawMessage + ")";
        }
        return rawMessage;
    }

    // ===================================================================================
    // Validation
    // ===================================================================================

    /**
     * The only Controller path this task currently accepts, leading slash required. Report files
     * are always written under {@code /home/cavisson/work} on the Cavisson server (see
     * {@link #resolveReportBasePath}), so a Controller value that names any other path - or is
     * missing the leading {@code /} - would be misleading about where reports actually land.
     * Rejecting a mismatch here surfaces that immediately as a clear [ERROR] instead of a
     * silently-ignored input.
     */
    private static final String EXPECTED_CONTROLLER = "home/cavisson/work";

    static String validateController(String controller) throws AbortException {
        if (controller == null || controller.trim().isEmpty()) {
            throw new AbortException("Controller is required for the Accessibility Scanner.");
        }
        String trimmedInput = controller.trim();
        String withoutTrailingSlash = trimmedInput.replaceAll("/+$", "");

        // Character/traversal safety check runs on the leading-slash-stripped form, same as
        // before, regardless of whether the leading slash itself turns out to be present.
        String forSafetyCheck = withoutTrailingSlash.replaceAll("^/+", "");
        if (forSafetyCheck.contains("..") || !SAFE_CONTROLLER.matcher(forSafetyCheck).matches()) {
            throw new AbortException("Controller '" + controller
                    + "' is invalid. Only letters, digits, '.', '_', '-' and '/' are allowed, and '..' is not permitted.");
        }

        if (!withoutTrailingSlash.startsWith("/")) {
            throw new AbortException("Controller path '" + controller
                    + "' is invalid - the fully qualified path is required, starting with '/'. Use /"
                    + EXPECTED_CONTROLLER + ".");
        }
        if (!forSafetyCheck.equals(EXPECTED_CONTROLLER)) {
            throw new AbortException("Controller path '" + controller
                    + "' is invalid. Use /" + EXPECTED_CONTROLLER + ".");
        }
        return forSafetyCheck;
    }

    static String validateApplicationUrl(String applicationUrl) throws AbortException {
        if (applicationUrl == null || applicationUrl.trim().isEmpty()) {
            throw new AbortException("Application URL is required for the Accessibility Scanner.");
        }
        String trimmed = applicationUrl.trim();
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new AbortException("Application URL '" + trimmed + "' must start with http:// or https://.");
            }
            if (uri.getHost() == null) {
                throw new AbortException("Application URL '" + trimmed + "' is not a valid URL.");
            }
        } catch (URISyntaxException e) {
            throw new AbortException("Application URL '" + trimmed + "' is not a valid URL: " + e.getMessage());
        }
        return trimmed;
    }

    private static String expand(EnvVars env, String value) {
        return value == null ? null : env.expand(value);
    }

    /**
     * Builds the absolute base path for one run's reports:
     * {@code <NS_WDIR>/logs/accessibility/accessibility-<timestamp>}. Equivalent to
     * {@code path.join(process.env.NS_WDIR || '/home/cavisson/work', 'logs', 'accessibility', reportFolder)}.
     * Always starts with {@code /} and never contains a duplicated {@code home/cavisson/work}
     * segment, regardless of whether {@code NS_WDIR} itself has a leading/trailing slash, is
     * blank/unset, or (as a defensive normalization, since this value ultimately lands in an
     * uploaded-file destination path) contains {@code ..}.
     */
    private static String resolveReportBasePath(EnvVars env, String reportFolder) {
        String raw = env == null ? null : env.get("NS_WDIR");
        String nsWdir = (raw != null && !raw.trim().isEmpty()) ? raw.trim() : "/home/cavisson/work";

        if (!nsWdir.startsWith("/")) {
            nsWdir = "/" + nsWdir;
        }
        // Collapse any doubled slashes (e.g. a trailing slash on NS_WDIR plus the leading slash
        // added above) and strip a trailing slash, so exactly one "/" ever separates segments.
        nsWdir = nsWdir.replaceAll("/{2,}", "/").replaceAll("/+$", "");
        if (nsWdir.isEmpty()) {
            nsWdir = "/";
        }
        // Defensive: NS_WDIR is an environment variable, not a validated plugin input like
        // Controller - strip any ".." segments so it can't be used to escape the intended
        // logs/accessibility tree on the Cavisson server.
        nsWdir = nsWdir.replace("..", "");

        return nsWdir + "/logs/accessibility/" + reportFolder;
    }

    // ===================================================================================
    // Scanner materialization + execution
    // ===================================================================================

    private static FilePath materializeScanner(FilePath workspace, CavLogger log) throws IOException, InterruptedException {
        FilePath scriptsDir = workspace.child(SCRIPTS_WORKDIR);
        scriptsDir.mkdirs();

        log.debug("Preparing bundled accessibility scanner in " + scriptsDir.getRemote());

        for (String relativePath : SCANNER_FILES) {
            FilePath target = scriptsDir.child(relativePath);
            FilePath targetDir = target.getParent();
            if (targetDir != null) {
                targetDir.mkdirs();
            }

            String resourcePath = SCRIPTS_RESOURCE_ROOT + "/" + relativePath;
            try (InputStream resourceStream = AccessibilityScannerExecutor.class.getResourceAsStream(resourcePath)) {
                if (resourceStream == null) {
                    throw new IOException("Bundled accessibility scanner resource not found: " + resourcePath);
                }
                target.copyFrom(resourceStream);
            }
        }

        return scriptsDir;
    }

    private static void installScannerDependencies(Launcher launcher,
                                                     TaskListener listener,
                                                     CavLogger log,
                                                     boolean verbose,
                                                     FilePath scriptsDir,
                                                     EnvVars env,
                                                     Map<String, String> extraEnv) throws IOException, InterruptedException {
        log.debug("Installing accessibility scanner dependencies (npm install)");
        CapturedOutput npmInstall = launch(launcher, listener, log, verbose, scriptsDir, env, extraEnv,
                Arrays.asList("npm", "install"));
        if (npmInstall.exitCode != 0) {
            throw new AbortException("Failed to install accessibility scanner dependencies: "
                    + reasonOrExitCode(npmInstall));
        }

        log.debug("Installing Playwright Chromium browser");
        CapturedOutput playwrightInstall = launch(launcher, listener, log, verbose, scriptsDir, env, extraEnv,
                Arrays.asList("node", "node_modules/playwright/cli.js", "install", "chromium"));
        if (playwrightInstall.exitCode != 0) {
            throw new AbortException("Failed to install Playwright Chromium: "
                    + reasonOrExitCode(playwrightInstall));
        }
    }

    private static void runNode(Launcher launcher,
                                 TaskListener listener,
                                 CavLogger log,
                                 boolean verbose,
                                 FilePath scriptsDir,
                                 EnvVars env,
                                 Map<String, String> extraEnv,
                                 List<String> scriptAndArgs,
                                 String title) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("node");
        command.addAll(scriptAndArgs);

        log.debug("========== " + title + " ==========");
        log.debug("Command: node " + String.join(" ", scriptAndArgs));
        log.debug("========================================");

        CapturedOutput output = launch(launcher, listener, log, verbose, scriptsDir, env, extraEnv, command);
        if (output.exitCode != 0) {
            throw new AbortException(title + " failed: " + reasonOrExitCode(output)
                    + " (re-run with Log Mode = DEBUG for full scanner output).");
        }
    }

    /** Formats a captured-process failure for an [ERROR] line: the extracted reason if one was
     *  found, otherwise just the exit code as before. */
    private static String reasonOrExitCode(CapturedOutput output) {
        String reason = extractFailureReason(output.lines);
        return reason != null ? reason + " (exit code " + output.exitCode + ")"
                : "exit code " + output.exitCode;
    }

    /**
     * Runs the given command. Its stdout/stderr (npm/Playwright/scan.js/generate-report.js
     * output) is only ever echoed to the console when {@code verbose} (Log Mode = DEBUG) is true
     * - printed verbatim via {@link CavLogger#raw}, i.e. without any "[LEVEL]" prefix, exactly as
     * it already appears in the scanner's own output. Outside DEBUG mode the process still runs
     * the same way and its output is still captured (see {@link #CAPTURED_LINES_LIMIT}) so a
     * failure can still be explained precisely - it just isn't echoed to the console live.
     */
    private static CapturedOutput launch(Launcher launcher,
                                          TaskListener listener,
                                          CavLogger log,
                                          boolean verbose,
                                          FilePath pwd,
                                          EnvVars env,
                                          Map<String, String> extraEnv,
                                          List<String> command) throws IOException, InterruptedException {
        EnvVars merged = new EnvVars(env);
        merged.putAll(extraEnv);

        CapturingLineForwarder out = new CapturingLineForwarder(log, verbose);

        int exitCode = launcher.launch()
                .cmds(command)
                .envs(merged)
                .pwd(pwd)
                .stdout(out)
                .quiet(true)
                .join();

        return new CapturedOutput(exitCode, out.lines());
    }

    /** Result of {@link #launch}: the process exit code plus its captured trailing output lines. */
    private static final class CapturedOutput {
        final int exitCode;
        final List<String> lines;

        CapturedOutput(int exitCode, List<String> lines) {
            this.exitCode = exitCode;
            this.lines = lines;
        }
    }

    /**
     * Extracts the single most useful line explaining a failed process from its captured output,
     * so the always-visible {@code [ERROR]} line names the actual cause (e.g. a Playwright
     * navigation error such as {@code net::ERR_NAME_NOT_RESOLVED} for an unreachable/unsupported
     * Application URL, or a CDN download timeout during Playwright's Chromium install) instead of
     * just an exit code. {@code scan.js}/{@code generate-report.js} both print a
     * {@code ❌ ... Failed} marker line followed by {@code err.stack || err.message} on failure
     * (see their {@code catch} blocks) - the line right after that marker is the most specific
     * single line available (the first line of the error, before any stack-frame lines). Falls
     * back to the last non-blank, non-stack-frame line for output that doesn't use that
     * convention (e.g. npm/Playwright's own install-time errors).
     */
    private static String extractFailureReason(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        for (int i = 0; i < lines.size() - 1; i++) {
            String line = lines.get(i);
            if (line != null && line.contains("❌")) {
                String next = lines.get(i + 1);
                if (next != null && !next.trim().isEmpty()) {
                    return next.trim();
                }
            }
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("at ")) {
                continue;
            }
            return trimmed;
        }
        return null;
    }

    /**
     * Forwards each line of a child process' combined stdout/stderr to {@link CavLogger#raw}
     * verbatim (no prefix) only when {@code verbose} is true, and unconditionally retains the
     * last {@link #CAPTURED_LINES_LIMIT} lines for {@link #extractFailureReason}. Same
     * line-splitting approach as {@code com.cavisson.jenkins.scriptlog.CavLogger}'s internal
     * {@code LogLineOutputStream}.
     */
    private static final class CapturingLineForwarder extends LineTransformationOutputStream {
        private final CavLogger log;
        private final boolean verbose;
        private final java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();

        private CapturingLineForwarder(CavLogger log, boolean verbose) {
            this.log = log;
            this.verbose = verbose;
        }

        @Override
        protected void eol(byte[] bytes, int len) {
            int end = len;
            while (end > 0 && (bytes[end - 1] == '\n' || bytes[end - 1] == '\r')) {
                end--;
            }
            String line = new String(bytes, 0, end, StandardCharsets.UTF_8);

            if (verbose) {
                log.raw(line);
            }

            tail.addLast(line);
            if (tail.size() > CAPTURED_LINES_LIMIT) {
                tail.removeFirst();
            }
        }

        List<String> lines() {
            return new ArrayList<>(tail);
        }
    }

    private static void archiveReports(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener, CavLogger log) {
        try {
            ArtifactArchiver archiver = new ArtifactArchiver(REPORT_DIRNAME + "/**");
            archiver.setAllowEmptyArchive(true);
            archiver.setOnlyIfSuccessful(false);
            archiver.perform(run, workspace, run.getEnvironment(listener), launcher, listener);
        } catch (IOException | InterruptedException e) {
            // Local artifact archiving is a convenience on top of the Cavisson server upload,
            // not a substitute for it - never fail the build over this alone.
            log.error("Could not archive accessibility reports as Jenkins build artifacts: " + e.getMessage());
        }
    }

    // ===================================================================================
    // Upload / metadata
    // ===================================================================================

    private static void uploadReportFile(CavLogger log, String uploadUrl, FilePath reportFile,
                                          Map<String, String> headers, String label) throws IOException, InterruptedException {
        File localFile = new File(reportFile.getRemote());
        if (!reportFile.exists()) {
            log.error("Failed to upload accessibility report: " + label + " report file not found at " + reportFile.getRemote());
            throw new AbortException("Failed to upload accessibility report: " + label + " report file not found.");
        }

        log.debug("Uploading " + label + " report: " + reportFile.getRemote());
        try {
            HttpUtil.HttpResult response = HttpUtil.postMultipartFile(uploadUrl, localFile, "file", headers, true);
            log.debug(label + " report upload response status: " + response.statusCode);
        } catch (IOException e) {
            String reason = describeHttpFailure(e.getMessage());
            log.error("Failed to upload accessibility report (" + label + "): " + reason);
            throw new AbortException("Failed to upload accessibility " + label + " report to the Cavisson server: " + reason);
        }
    }

    private static String publishReportMetadata(CavLogger log, String baseUrl, String apiToken, String applicationUrl,
                                                  Run<?, ?> run, String highestSeverity,
                                                  String finalHtmlPath, String finalJsonPath) throws IOException {
        long timestamp = System.currentTimeMillis();
        String reportId = "cav-accessibility-" + timestamp;
        String reportName = "Accessibility_Report_" + timestamp;
        String createdBy = resolveCreatedBy(run);

        JSONObject payload = new JSONObject();
        payload.put("reportId", reportId);
        payload.put("reportName", reportName);
        payload.put("targetUrl", applicationUrl);
        payload.put("createdBy", createdBy);
        payload.put("timestamp", timestamp);
        payload.put("createdAt", timestamp);
        payload.put("highestSeverity", highestSeverity);
        payload.put("projectName", "Cavisson Accessibility Scanner");
        payload.put("tags", "accessibility");
        payload.put("toolName", "Cavisson Accessibility Scanner");
        payload.put("status", "COMPLETED");

        JSONObject reports = new JSONObject();
        reports.put("html", finalHtmlPath);
        reports.put("json", finalJsonPath);
        payload.put("reports", reports);

        String metadataUrl = baseUrl.replaceAll("/+$", "") + METADATA_PATH + "?cavToken=" + apiToken;

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");

        try {
            HttpUtil.HttpResult response = HttpUtil.postJson(metadataUrl, payload.toString(), headers, true);
            log.debug("Report metadata response status: " + response.statusCode);
        } catch (IOException e) {
            String reason = describeHttpFailure(e.getMessage());
            log.error("Failed to save accessibility report metadata to the Cavisson server: " + reason);
            throw new AbortException("Failed to save accessibility report metadata: " + reason);
        }

        return reportId;
    }

    private static String resolveCreatedBy(Run<?, ?> run) {
        try {
            EnvVars runEnv = run.getEnvironment(TaskListener.NULL);
            String buildUser = firstNonBlank(runEnv.get("BUILD_USER"), runEnv.get("BUILD_USER_ID"));
            if (buildUser != null) {
                return buildUser;
            }
        } catch (IOException | InterruptedException ignored) {
            // fall through to default below
        }
        return "System";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** Same severity-priority logic as the Azure extension's index.js highestSeverity computation. */
    static String highestSeverity(JSONObject axeJson) {
        JSONArray violations = axeJson.optJSONArray("violations");
        if (violations == null) {
            return "NONE";
        }

        String highest = "NONE";
        int highestRank = 0;

        for (int i = 0; i < violations.length(); i++) {
            JSONObject violation = violations.optJSONObject(i);
            if (violation == null) {
                continue;
            }
            String impact = violation.optString("impact", "minor").toLowerCase(java.util.Locale.ROOT);
            int rank = SEVERITY_RANK.getOrDefault(impact, 0);
            if (rank > highestRank) {
                highestRank = rank;
                highest = impact.toUpperCase(java.util.Locale.ROOT);
            }
        }

        return highest;
    }
}
