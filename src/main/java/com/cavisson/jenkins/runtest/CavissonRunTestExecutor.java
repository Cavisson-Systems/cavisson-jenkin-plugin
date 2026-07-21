package com.cavisson.jenkins.runtest;

import com.cavisson.jenkins.connection.CavissonConnection;
import com.cavisson.jenkins.env.CavissonDescriptionPublisher;
import com.cavisson.jenkins.env.CavissonEnvironmentPublisher;
import com.cavisson.jenkins.http.HttpUtil;
import com.cavisson.jenkins.log.AnsiColors;
import com.cavisson.jenkins.log.CavLogger;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.ArtifactArchiver;
import hudson.tasks.junit.JUnitResultArchiver;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of the Azure DevOps "CavissonRunTest" task (task/index.js): triggers a Cavisson
 * TestSuite or Load Test scenario via the DashboardServer REST API, polls until it finishes,
 * and (for Load Test runs) downloads and archives the HTML report. Shared by both
 * {@link CavissonRunTestBuilder} (Freestyle) and {@link CavissonRunTestStep} (Pipeline).
 */
final class CavissonRunTestExecutor {

    private static final String API_BASE_PATH = "/DashboardServer/v2/scenario/cicd";
    private static final long POLL_INTERVAL_MILLIS = 60000L;

    private CavissonRunTestExecutor() {
    }

    static Map<String, Object> run(Run<?, ?> run,
                                    FilePath workspace,
                                    Launcher launcher,
                                    EnvVars env,
                                    TaskListener listener,
                                    CavissonConnection connection,
                                    String testType,
                                    String project,
                                    String subProject,
                                    String username,
                                    String profile,
                                    String testSuiteName,
                                    String scenarioName) throws IOException, InterruptedException {

        CavLogger log = new CavLogger(listener, env);

        String baseUrl = connection.getBaseUrl();
        String apiToken = connection.getApiToken();
        boolean allowInsecureSSL = true;

        String resolvedTestType = "LoadTest".equalsIgnoreCase(trim(testType)) ? "LoadTest" : "TestSuite";
        String mode = "LoadTest".equals(resolvedTestType) ? "N" : "T";

        String resolvedProject = expand(env, firstNonBlank(project, "default"));
        String resolvedSubProject = expand(env, firstNonBlank(subProject, "default"));
        String resolvedUsername = expand(env, firstNonBlank(username, "Cavisson"));
        String resolvedProfile = expand(env, firstNonBlank(profile, "default"));
        String targetScenario = "T".equals(mode) ? expand(env, testSuiteName) : expand(env, scenarioName);

        if (targetScenario == null || targetScenario.trim().isEmpty()) {
            throw new AbortException("T".equals(mode)
                    ? "TestSuite Name is required when Test Type is TestSuite."
                    : "Test Name (scenario) is required when Test Type is LoadTest.");
        }

        String targetLabel = "T".equals(mode) ? "Test Suite" : "Test Case";

        log.debug("========== Cavisson Run Test ==========");
        log.debug("Service Base URL : " + baseUrl);
        log.debug("Test Type        : " + resolvedTestType);
        log.debug("Project          : " + resolvedProject);
        log.debug("Sub Project      : " + resolvedSubProject);
        log.debug(String.format("%-17s: %s", targetLabel, targetScenario));
        log.debug("========================================");

        String apiBase = baseUrl.replaceAll("/+$", "") + API_BASE_PATH;

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("cavToken", apiToken);

        JSONObject startBody = new JSONObject();
        startBody.put("testmode", mode);
        startBody.put("project", resolvedProject);
        startBody.put("subproject", resolvedSubProject);
        startBody.put("scenario", targetScenario);
        startBody.put("workProfile", resolvedProfile);
        startBody.put("username", resolvedUsername);

        log.debug("Triggering Cavisson test " + resolvedProject + "/" + resolvedSubProject + "/" + targetScenario);

        HttpUtil.HttpResult startResponse = HttpUtil.postJson(apiBase + "/startTest", startBody.toString(), headers, allowInsecureSSL);
        log.debug("startTest response: " + startResponse.body);
        JSONObject startJson = new JSONObject(startResponse.body);

        long runNo = startJson.optLong("run", 0);

        if (!isStartSuccessful(startJson) || runNo == 0) {
            throw new AbortException("Failed to start Cavisson test: " + startErrorDetail(startJson));
        }

        // The server can report back a different effective test mode than what was requested
        // (e.g. "effectiveTestMode":"N"/"T" in the startTest response); subsequent poll calls
        // must use that corrected mode, not the originally-requested one, or polling can behave
        // incorrectly against the actual run.
        String effectiveMode = resolveEffectiveMode(startJson, mode);

        // "functional"/"performance" as classified by the server for this run; drives whether
        // a JUnit report should be published, same as the ADO extension's executionType.
        String executionType = startJson.optString("testType", "").trim().toLowerCase(Locale.ROOT);

        log.info("Test triggered successfully with run number " + runNo);
        log.debug("Polling for test completion...");

        String finalStatus = null;
        String statusMessage = "";
        String reportUrl = "";
        int elapsedMinutes = 0;
        StringBuilder progress = new StringBuilder();
        JunitSummary junitSummary = null;

        while (true) {
            String statusUrl = apiBase + "/checkConnectionStatus"
                    + "?testRun=" + runNo
                    + "&testmode=" + urlEncode(effectiveMode)
                    + "&scenarioName=" + urlEncode(targetScenario)
                    + "&replaceTR=false";

            HttpUtil.HttpResult statusResponse = HttpUtil.getJson(statusUrl, headers, allowInsecureSSL);
            JSONObject statusJson = new JSONObject(statusResponse.body);

            boolean running = statusJson.optBoolean("running", false);

            if (!running) {
                finalStatus = statusJson.optString("status", "").toLowerCase(Locale.ROOT);
                statusMessage = firstNonBlank(statusJson.optString("error", ""),
                        firstNonBlank(statusJson.optString("msg", ""), statusJson.optString("message", "No message provided")));
                reportUrl = statusJson.optString("reportUrl", "");
                String htmlReportUrl = statusJson.optString("HtmlReport", "");

                // Auto-publish JUnit results for TestSuite/functional runs, regardless of the
                // eventual pass/fail/error outcome - matches the ADO extension's behavior.
                boolean shouldPublishJUnit = "T".equals(effectiveMode) || "functional".equals(executionType);
                if (shouldPublishJUnit) {
                    long reportRunNo = firstPositive(extractTsrFromUrl(reportUrl), extractTsrFromUrl(htmlReportUrl), runNo);
                    try {
                        junitSummary = downloadAndPublishJUnitReport(run, workspace, launcher, listener, log, apiBase, headers,
                                allowInsecureSSL, reportRunNo, effectiveMode, executionType);
                    } catch (IOException junitError) {
                        log.error("Could not download/publish JUnit report: " + junitError.getMessage());
                    }
                }

                if (isTerminalStatus(finalStatus)) {
                    log.info("Test completed.");
                    if (junitSummary != null) {
                        log.info(String.format("        Total TestCases - %d, Success - %s, Failure - %s",
                                junitSummary.total, AnsiColors.green(String.valueOf(junitSummary.success)),
                                AnsiColors.red(String.valueOf(junitSummary.failures.size()))));
                    }
                    log.info(String.format("        Status : %s", AnsiColors.status(finalStatus)));
                    log.debug(String.format("        Message: %s", statusMessage));
                    if (!reportUrl.isEmpty()) {
                        log.info(String.format("        Report : %s", reportUrl));
                    }
                    if (junitSummary != null && !junitSummary.failures.isEmpty()) {
                        log.info(AnsiColors.red("        Failure Test Case(s) - "));
                        for (String failure : junitSummary.failures) {
                            log.info("            " + AnsiColors.red(failure));
                        }
                    }
                    break;
                } else if ("error".equals(finalStatus)) {
                    throw new AbortException("Cavisson test execution error: " + statusMessage);
                } else {
                    throw new AbortException("Unknown Cavisson test completion status: " + finalStatus);
                }
            } else {
                elapsedMinutes++;
                progress.append('.');

                if (elapsedMinutes % 5 == 0) {
                    log.debug(String.format("Test is still Running %s (%d min elapsed)", progress, elapsedMinutes));
                    // Start a fresh progress line for the next 5 minutes.
                    progress.setLength(0);
                }

                Thread.sleep(POLL_INTERVAL_MILLIS);
            }
        }

        if ("N".equals(effectiveMode)) {
            try {
                downloadAndArchiveReport(run, workspace, launcher, listener, log, apiBase, headers, allowInsecureSSL, runNo);
            } catch (IOException reportError) {
                log.error("Report download skipped: " + reportError.getMessage());
            }
        }

        try {
            CavissonDescriptionPublisher.appendReportRow(run, env, reportUrl, "Cavisson - Run Test");
        } catch (IOException descriptionError) {
            log.error("Could not set build description with report link: " + descriptionError.getMessage());
        }

        Map<String, String> envVars = new LinkedHashMap<>();
        envVars.put("CAV_TSR_NUMBER", String.valueOf(runNo));
        envVars.put("CAV_TSR_STATUS", finalStatus == null ? "" : finalStatus);
        envVars.put("CAV_TSR_REPORT_URL", reportUrl == null ? "" : reportUrl);
        CavissonEnvironmentPublisher.publish(run, envVars);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("testStatus", finalStatus);
        result.put("reportUrl", reportUrl);
        result.put("runNo", runNo);
        result.put("message", statusMessage);
        return result;
    }

    private static void downloadAndArchiveReport(Run<?, ?> run,
                                                  FilePath workspace,
                                                  Launcher launcher,
                                                  TaskListener listener,
                                                  CavLogger log,
                                                  String apiBase,
                                                  Map<String, String> headers,
                                                  boolean allowInsecureSSL,
                                                  long runNo) throws IOException, InterruptedException {

        JSONObject reportRequest = new JSONObject();
        reportRequest.put("testRun", runNo);

        HttpUtil.HttpResult reportResponse = HttpUtil.postJson(apiBase + "/getHtmlReport", reportRequest.toString(), headers, allowInsecureSSL);

        FilePath reportDir = workspace.child("cavisson-report");
        reportDir.mkdirs();

        FilePath reportFile = reportDir.child("TestSuiteReport_" + runNo + ".html");
        reportFile.write(reportResponse.body, "UTF-8");

        ArtifactArchiver archiver = new ArtifactArchiver("cavisson-report/" + reportFile.getName());
        archiver.setAllowEmptyArchive(true);
        archiver.setOnlyIfSuccessful(false);
        archiver.perform(run, workspace, run.getEnvironment(listener), launcher, listener);

        log.info("HTML report downloaded and published to artifacts: " + reportFile.getName());
    }

    private static JunitSummary downloadAndPublishJUnitReport(Run<?, ?> run,
                                                       FilePath workspace,
                                                       Launcher launcher,
                                                       TaskListener listener,
                                                       CavLogger log,
                                                       String apiBase,
                                                       Map<String, String> headers,
                                                       boolean allowInsecureSSL,
                                                       long reportRunNo,
                                                       String effectiveMode,
                                                       String executionType) throws IOException, InterruptedException {

        JSONObject junitRequest = new JSONObject();
        junitRequest.put("testRun", reportRunNo);
        junitRequest.put("testmode", effectiveMode);
        junitRequest.put("type", "T".equals(effectiveMode) ? "testsuite" : "test");
        junitRequest.put("executionType", executionType);
        junitRequest.put("replaceTR", "false");

        HttpUtil.HttpResult junitResponse = HttpUtil.postJson(apiBase + "/getJunitReport", junitRequest.toString(), headers, allowInsecureSSL);

        FilePath resultsDir = workspace.child("cavisson-test-results");
        resultsDir.mkdirs();

        FilePath junitFile = resultsDir.child("junit-" + reportRunNo + ".xml");
        junitFile.write(junitResponse.body, "UTF-8");

        log.debug("JUnit report downloaded: " + junitFile.getRemote());

        JUnitResultArchiver archiver = new JUnitResultArchiver("cavisson-test-results/" + junitFile.getName());
        archiver.setAllowEmptyResults(true);
        archiver.perform(run, workspace, launcher, listener);

        log.info("JUnit results published to build Test Results.");

        try {
            return parseJunitSummary(junitResponse.body);
        } catch (IOException summaryError) {
            log.error("Could not summarize JUnit report: " + summaryError.getMessage());
            return null;
        }
    }

    /** Holds the counts and failing testcase names derived from a JUnit report. */
    private static final class JunitSummary {
        final int total;
        final int success;
        final List<String> failures;

        JunitSummary(int total, int success, List<String> failures) {
            this.total = total;
            this.success = success;
            this.failures = failures;
        }
    }

    /**
     * Derives a "Total testcase - X, Success - Y, Failure - Z" summary plus the list of failing
     * testcase names, from each {@code <testcase>}'s own "status" property - same per-testcase
     * status source {@code JunitFailureParser} in the analysefailure package uses - rather than
     * the {@code <testsuite>} tag's own tests/failures attributes, since those aren't reliably
     * populated in Cavisson's JUnit report.
     */
    private static JunitSummary parseJunitSummary(String junitXml) throws IOException {
        Element testsuite;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(junitXml)));
            testsuite = document.getDocumentElement();
        } catch (ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IOException("Failed to parse JUnit report: " + e.getMessage(), e);
        }

        NodeList testcases = testsuite.getElementsByTagName("testcase");
        List<String> failures = new ArrayList<>();
        int total = testcases.getLength();
        int success = 0;

        for (int i = 0; i < total; i++) {
            Element testcase = (Element) testcases.item(i);
            if ("failed".equalsIgnoreCase(readDirectProperty(testcase, "status"))) {
                failures.add(testcase.getAttribute("name"));
            } else {
                success++;
            }
        }

        return new JunitSummary(total, success, failures);
    }

    private static Element directChild(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static String readDirectProperty(Element parent, String propertyName) {
        Element properties = directChild(parent, "properties");
        if (properties == null) {
            return "";
        }

        NodeList propertyNodes = properties.getElementsByTagName("property");
        for (int i = 0; i < propertyNodes.getLength(); i++) {
            Element property = (Element) propertyNodes.item(i);
            if (propertyName.equals(property.getAttribute("name"))) {
                return property.getAttribute("value");
            }
        }
        return "";
    }

    private static long extractTsrFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return 0;
        }
        Matcher matcher = Pattern.compile("[?&]tsr=(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    private static long firstPositive(long... values) {
        for (long value : values) {
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    /**
     * The DashboardServer's startTest response uses a boolean "success" flag to report whether
     * the trigger itself succeeded; its "status"/"message" fields at this stage can reflect other
     * things (e.g. a stale/default test verdict such as {"status":"PASS", "message":"{...}"} seen
     * on a real server even though the field name suggests otherwise), so they must not be relied
     * upon to decide start success unless "success" is absent entirely.
     */
    static boolean isStartSuccessful(JSONObject startJson) {
        if (startJson.has("success")) {
            return startJson.optBoolean("success", false);
        }
        return "success".equalsIgnoreCase(startJson.optString("status", ""));
    }

    static String startErrorDetail(JSONObject startJson) {
        String errorDetail = startJson.optString("error", "");
        if (!errorDetail.isEmpty()) {
            return errorDetail;
        }
        return startJson.optString("message", "Unknown error");
    }

    /**
     * The server can normalize/override the requested test mode (e.g. respond with
     * "effectiveTestMode":"N" even though "T" was requested); poll calls must follow that
     * corrected mode rather than the originally-requested one.
     */
    static String resolveEffectiveMode(JSONObject startJson, String requestedMode) {
        String effectiveMode = startJson.optString("effectiveTestMode", "").trim().toUpperCase(Locale.ROOT);
        return effectiveMode.isEmpty() ? requestedMode : effectiveMode;
    }

    /** "fail" and "failed" are both observed as terminal (non-running) failure statuses. */
    static boolean isTerminalStatus(String status) {
        return "pass".equals(status) || "fail".equals(status) || "failed".equals(status) || "error".equals(status);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.trim().isEmpty()) {
            return preferred;
        }
        return fallback;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String expand(EnvVars env, String value) {
        return value == null ? null : env.expand(value);
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
