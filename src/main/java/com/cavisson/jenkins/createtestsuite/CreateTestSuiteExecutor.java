package com.cavisson.jenkins.createtestsuite;

import com.cavisson.jenkins.connection.CavissonConnection;
import com.cavisson.jenkins.env.CavissonEnvironmentPublisher;
import com.cavisson.jenkins.http.HttpUtil;
import com.cavisson.jenkins.log.AnsiColors;
import com.cavisson.jenkins.log.CavLogger;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.console.HyperlinkNote;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Calls the Scenario Service "Create Test Suite" REST API
 * (com.cavisson.scenarioV2.controller.ScenarioController#createTestSuite, documented in
 * prod-src/web/scenarioservices/openapi-createTestSuite.yaml): finds testcases whose tags
 * intersect the supplied tags list and persists them as a new test suite. Unlike
 * {@code CavissonRunTestExecutor}, this is a single synchronous call with no polling - the
 * endpoint always answers HTTP 200 with a {@code status: success|fail} body. Shared by both
 * {@link CreateTestSuiteBuilder} (Freestyle) and {@link CreateTestSuiteStep} (Pipeline).
 */
final class CreateTestSuiteExecutor {

    private static final String API_PATH = "/DashboardServer/v2/scenario/data/createTestSuite";

    private CreateTestSuiteExecutor() {
    }

    static Map<String, Object> run(Run<?, ?> run,
                                    EnvVars env,
                                    TaskListener listener,
                                    CavissonConnection connection,
                                    String project,
                                    String subProject,
                                    String workspace,
                                    String profile,
                                    String name,
                                    String tags,
                                    boolean automatedOnly,
                                    String gitIntegration,
                                    String commitId,
                                    String mergeId,
                                    String codeMappingMode) throws IOException {

        CavLogger log = new CavLogger(listener, env);

        String baseUrl = connection.getBaseUrl();
        String apiToken = connection.getApiToken();
        boolean allowInsecureSSL = true;

        String resolvedProject = expand(env, firstNonBlank(project, "default"));
        String resolvedSubProject = expand(env, firstNonBlank(subProject, "default"));
        String resolvedWorkspace = expand(env, firstNonBlank(workspace, "admin"));
        String resolvedProfile = expand(env, firstNonBlank(profile, "system"));
        String resolvedName = expand(env, name);

        JSONArray tagsArray = parseTags(expand(env, tags));
        String resolvedGitIntegration = expand(env, gitIntegration);
        String resolvedCommitId = expand(env, commitId);
        String resolvedMergeId = expand(env, mergeId);
        String resolvedCodeMappingMode = expand(env, codeMappingMode);

        boolean hasDiffSource = resolvedGitIntegration != null && !resolvedGitIntegration.trim().isEmpty()
                && ((resolvedCommitId != null && !resolvedCommitId.trim().isEmpty())
                        || (resolvedMergeId != null && !resolvedMergeId.trim().isEmpty()));

        if (tagsArray.isEmpty() && !hasDiffSource) {
            throw new AbortException("At least one of tags, or gitIntegration with commitId/mergeId, is required to create a test suite.");
        }

        log.info("========== Cavisson Create Test Suite ==========");
        log.info("Service Base URL : " + baseUrl);
        log.info("Project          : " + resolvedProject);
        log.info("Sub Project      : " + resolvedSubProject);
        log.info("Workspace/Profile: " + resolvedWorkspace + "/" + resolvedProfile);
        log.info("Tags             : " + tagsArray);
        if (hasDiffSource) {
            log.info("Git Integration  : " + resolvedGitIntegration);
            if (resolvedMergeId != null && !resolvedMergeId.trim().isEmpty()) {
                log.info("Merge ID         : " + resolvedMergeId);
            } else {
                log.info("Commit ID        : " + resolvedCommitId);
            }
        }
        log.info("=================================================");

        String url = baseUrl.replaceAll("/+$", "") + API_PATH;

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("cavToken", apiToken);

        JSONObject requestBody = new JSONObject();
        requestBody.put("project", resolvedProject);
        requestBody.put("subproject", resolvedSubProject);
        requestBody.put("workspace", resolvedWorkspace);
        requestBody.put("profile", resolvedProfile);
        if (resolvedName != null && !resolvedName.trim().isEmpty()) {
            requestBody.put("name", resolvedName.trim());
        }
        if (!tagsArray.isEmpty()) {
            requestBody.put("tags", tagsArray);
        }
        requestBody.put("automatedOnly", automatedOnly);
        if (hasDiffSource) {
            requestBody.put("gitIntegration", resolvedGitIntegration.trim());
            if (resolvedMergeId != null && !resolvedMergeId.trim().isEmpty()) {
                requestBody.put("mergeId", resolvedMergeId.trim());
            } else {
                requestBody.put("commitId", resolvedCommitId.trim());
            }
            if (resolvedCodeMappingMode != null && !resolvedCodeMappingMode.trim().isEmpty()) {
                requestBody.put("codeMappingMode", resolvedCodeMappingMode.trim());
            }
        }

        HttpUtil.HttpResult response = HttpUtil.postJson(url, requestBody.toString(), headers, allowInsecureSSL);
        log.debug("createTestSuite response: " + response.body);
        JSONObject responseJson = new JSONObject(response.body);

        if (!isSuccess(responseJson)) {
            throw new AbortException("Failed to create test suite: " + failureMessage(responseJson));
        }

        String testsuite = responseJson.optString("testsuite", "");
        log.info("Test Suite Creation : " + AnsiColors.bold(AnsiColors.status(responseJson.optString("status", ""))));
        if (!testsuite.isEmpty()) {
            log.infoHyperlink("Test Suite : ", testsuiteUrl(baseUrl, testsuite), testsuite);
        }
        logSourceCodeChanges(log, responseJson);
        logTestSuiteDetails(log, responseJson, baseUrl, hasDiffSource, tagsArray, resolvedMergeId, resolvedCommitId);

        Map<String, String> envVars = new LinkedHashMap<>();
        envVars.put("CAV_NEW_TESTSUITE_NAME", lastPathSegment(testsuite));
        CavissonEnvironmentPublisher.publish(run, envVars);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", AnsiColors.status("Success"));
        result.put("testsuite", testsuite);
        return result;
    }

    /**
     * Prints the flat "Source Code Changes" list - the distinct set of "file" entries from the
     * response's "details" array (each tagged with its "change" label), replacing the older
     * per-file testcase breakdown. The server only returns "details" for git-diff-based creation
     * (commitId/mergeId); a tags-only creation has no source-file mapping, so nothing is printed.
     */
    private static void logSourceCodeChanges(CavLogger log, JSONObject responseJson) {
        JSONArray details = responseJson.optJSONArray("details");
        if (details == null || details.isEmpty()) {
            return;
        }

        Set<String> seen = new LinkedHashSet<>();
        Map<String, String> modifiedFiles = new LinkedHashMap<>();
        for (int i = 0; i < details.length(); i++) {
            JSONObject detail = details.getJSONObject(i);
            String file = detail.optString("file", "");
            if (!file.isEmpty() && seen.add(file)) {
                modifiedFiles.put(file, changeLabel(detail.optString("change", "")));
            }
        }

        log.info("   Source Code Changes :");
        for (Map.Entry<String, String> entry : modifiedFiles.entrySet()) {
            log.info("     " + entry.getValue() + " - " + entry.getKey());
        }
    }

    /**
     * Prints the distinct testcase count - with a suffix noting whether the suite was built from
     * tags or from a git mergeId/commitId - plus a clickable share.html link per testcase. The
     * testcase set is read from the response's top-level "testcases" array so it's populated
     * whether or not "details" (git-diff-only) is present, plus (commented out below) a per-file
     * breakdown listing the testcases each file affected.
     */
    private static void logTestSuiteDetails(CavLogger log, JSONObject responseJson, String baseUrl,
                                             boolean hasDiffSource, JSONArray tagsArray,
                                             String resolvedMergeId, String resolvedCommitId) {
        JSONArray details = responseJson.optJSONArray("details");
        JSONArray testcasesArray = responseJson.optJSONArray("testcases");

        Set<String> allTestcases = new LinkedHashSet<>();
        if (testcasesArray != null) {
            for (int i = 0; i < testcasesArray.length(); i++) {
                allTestcases.add(testcasesArray.getString(i));
            }
        } else if (details != null) {
            for (int i = 0; i < details.length(); i++) {
                JSONArray testcases = details.getJSONObject(i).optJSONArray("testcases");
                if (testcases != null) {
                    for (int j = 0; j < testcases.length(); j++) {
                        allTestcases.add(testcases.getString(j));
                    }
                }
            }
        }

        if (allTestcases.isEmpty()) {
            return;
        }

        StringBuilder source = new StringBuilder();
        if (tagsArray != null && !tagsArray.isEmpty()) {
            source.append("with provided tags ").append(tagsArray);
        }
        if (hasDiffSource) {
            if (source.length() > 0) {
                source.append(" and ");
            } else {
                source.append("with ");
            }
            source.append((resolvedMergeId != null && !resolvedMergeId.trim().isEmpty())
                    ? "mergeId " + resolvedMergeId.trim()
                    : "commitId " + resolvedCommitId.trim());
        }

        log.info("   Test Suite created with " + AnsiColors.bold(String.valueOf(allTestcases.size()))
                + " test cases, " + source);
        for (String testcase : allTestcases) {
            log.info("     " + HyperlinkNote.encodeTo(testcaseUrl(baseUrl, testcase), lastPathSegment(testcase)));
        }
        // Superseded by logModifiedFiles(); kept for reference in case per-file testcase
        // breakdown logging is wanted again.
        // log.info("   Test Case selected based on source code changes - ");
        // for (int i = 0; i < details.length(); i++) {
        //     JSONObject detail = details.getJSONObject(i);
        //     String file = detail.optString("file", "");
        //     String label = changeLabel(detail.optString("change", ""));
        //     log.info("     " + label + " - " + file + " ");
        //
        //     JSONArray testcases = detail.optJSONArray("testcases");
        //     if (testcases != null) {
        //         for (int j = 0; j < testcases.length(); j++) {
        //             log.info("       " + testcases.getString(j));
        //         }
        //     }
        // }
    }

    /** Builds the "View Testcase" share.html link: {@code <baseUrl>/UnifiedDashboard/share.html?open=testcase&tc=<testcase>}. */
    private static String testcaseUrl(String baseUrl, String testcase) {
        return baseUrl.replaceAll("/+$", "") + "/UnifiedDashboard/share.html?open=testcase&tc=" + urlEncode(testcase);
    }

    /**
     * Builds the "View Test Suite" share.html link: {@code <baseUrl>/UnifiedDashboard/share.html?open=testsuite&ts=<testsuite>}.
     * {@code testsuite} ("project/subproject/name") is used verbatim, not URL-encoded - the
     * slashes are the expected path-segment separators in this query param, matching the same
     * unencoded {@code ts=} convention used elsewhere for this share-link shape.
     */
    private static String testsuiteUrl(String baseUrl, String testsuite) {
        return baseUrl.replaceAll("/+$", "") + "/UnifiedDashboard/share.html?open=testsuite&ts=" + testsuite;
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }

    /** "m" -> cyan "[MODIFIED]", "n" -> yellow "[NEW]", anything else -> plain "[CHANGED]". */
    private static String changeLabel(String change) {
        if ("m".equalsIgnoreCase(change)) {
            return AnsiColors.cyan("[MODIFIED]");
        }
        if ("n".equalsIgnoreCase(change)) {
            return AnsiColors.yellow("[MODIFIED]"); // This Change is done for Demo Purpose
        }
        return "[CHANGED]";
    }

    static boolean isSuccess(JSONObject responseJson) {
        return "success".equalsIgnoreCase(responseJson.optString("status", ""));
    }

    static String failureMessage(JSONObject responseJson) {
        return responseJson.optString("msg", "Unknown error");
    }

    /** The response's "testsuite" is "project/subproject/name" - only the name is published as CAV_NEW_TESTSUITE_NAME. */
    static String lastPathSegment(String testsuite) {
        if (testsuite == null || testsuite.isEmpty()) {
            return testsuite;
        }
        String[] parts = testsuite.split("/");
        return parts[parts.length - 1];
    }

    static JSONArray parseTags(String tags) {
        JSONArray array = new JSONArray();
        if (tags == null || tags.trim().isEmpty()) {
            return array;
        }
        Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .forEach(array::put);
        return array;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.trim().isEmpty()) {
            return preferred;
        }
        return fallback;
    }

    private static String expand(EnvVars env, String value) {
        return value == null ? null : env.expand(value);
    }
}
