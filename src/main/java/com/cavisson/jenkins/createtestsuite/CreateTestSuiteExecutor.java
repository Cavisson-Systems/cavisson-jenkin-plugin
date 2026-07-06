package com.cavisson.jenkins.createtestsuite;

import com.cavisson.jenkins.connection.CavissonConnection;
import com.cavisson.jenkins.env.CavissonEnvironmentPublisher;
import com.cavisson.jenkins.http.HttpUtil;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

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
                                    boolean automatedOnly) throws IOException {

        String baseUrl = connection.getBaseUrl();
        String apiToken = connection.getApiToken();
        boolean allowInsecureSSL = true;

        String resolvedProject = expand(env, firstNonBlank(project, "default"));
        String resolvedSubProject = expand(env, firstNonBlank(subProject, "default"));
        String resolvedWorkspace = expand(env, firstNonBlank(workspace, "admin"));
        String resolvedProfile = expand(env, firstNonBlank(profile, "system"));
        String resolvedName = expand(env, name);

        JSONArray tagsArray = parseTags(expand(env, tags));
        if (tagsArray.isEmpty()) {
            throw new AbortException("At least one tag is required to create a test suite.");
        }

        listener.getLogger().println("========== Cavisson Create Test Suite ==========");
        listener.getLogger().println("Service Base URL : " + baseUrl);
        listener.getLogger().println("Project          : " + resolvedProject);
        listener.getLogger().println("Sub Project      : " + resolvedSubProject);
        listener.getLogger().println("Workspace/Profile: " + resolvedWorkspace + "/" + resolvedProfile);
        listener.getLogger().println("Tags             : " + tagsArray);
        listener.getLogger().println("=================================================");

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
        requestBody.put("tags", tagsArray);
        requestBody.put("automatedOnly", automatedOnly);

        HttpUtil.HttpResult response = HttpUtil.postJson(url, requestBody.toString(), headers, allowInsecureSSL);
        listener.getLogger().println("createTestSuite response: " + response.body);
        JSONObject responseJson = new JSONObject(response.body);

        if (!isSuccess(responseJson)) {
            throw new AbortException("Failed to create test suite: " + failureMessage(responseJson));
        }

        String testsuite = responseJson.optString("testsuite", "");
        listener.getLogger().println("Test suite created: " + testsuite);

        Map<String, String> envVars = new LinkedHashMap<>();
        envVars.put("CAV_NEW_TESTSUITE_NAME", lastPathSegment(testsuite));
        CavissonEnvironmentPublisher.publish(run, envVars);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("testsuite", testsuite);
        return result;
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
