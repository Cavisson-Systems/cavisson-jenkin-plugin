package com.cavisson.jenkins.ai.testcase.util;

import com.cavisson.jenkins.ai.testcase.builder.CavAITestCaseBuilder;
import com.cavisson.jenkins.connection.CavServiceConnection;
import com.cavisson.jenkins.ai.testcase.source.JiraSourceRequest;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Constructs the JSON request body for
 * POST /agentic/api/testcasePipeline/run.
 *
 * Two build methods are provided:
 *
 * 1. build() -- for LOCAL and GIT source types.
 *    Requires an uploadedFilename (fileRef).
 *    Includes source.type + source.fileRef.
 *    Includes publishUserStories and related fields from credential.
 *
 * 2. buildJira() -- for JIRA source type.
 *    Requires a JiraSourceRequest (epicPattern + integrationName).
 *    Includes source.type = "JIRA" + source.epicPattern.
 *    Does NOT include fileRef.
 *    Does NOT include publishUserStories, publishTrackerType, publishEpicName.
 *    Includes integrationName at the top level.
 *
 * JIRA Payload structure:
 * {
 *   "idempotencyKey": "...",
 *   "source": {
 *     "type":         "JIRA",
 *     "epicPattern":  "EM-527"
 *   },
 *   "project":           "default",
 *   "subproject":        "default",
 *   "testsuiteName":     "AI_Test_Suite",
 *   "numberOfTestCases": 1,
 *   "controllerName":    "work",
 *   "userName":          "cavisson",
 *   "applicationConfig": {
 *     "applicationUrl": "...",
 *     "username":       "",
 *     "password":       ""
 *   },
 *   "tags": ["app=boutique"],
 *   "integrationName": "JIRA_CONFIG_TEST"
 * }
 */
public final class PayloadBuilder {

    private PayloadBuilder() {}

    // -- Build for LOCAL / GIT source types (existing, unchanged) -------------

    /**
     * Builds the JSON payload for LOCAL and GIT source types.
     * Requires uploadedFilename returned by the upload REST call.
     *
     * @param builder          pipeline step configuration
     * @param credential       service connection
     * @param uploadedFilename filename returned by the upload step (fileRef)
     * @param idempotencyKey   Jenkins BUILD_TAG
     * @param userName         resolved via {@link com.cavisson.jenkins.ai.testcase.client.UserNameService}
     *                         from the Cav Token - never hardcoded, never defaulted here
     * @return JSON string ready to POST
     */
    public static String build(
            CavAITestCaseBuilder   builder,
            CavServiceConnection credential,
            String                 uploadedFilename,
            String                 idempotencyKey,
            String                 userName) {

        requireApplicationUrl(builder);
        requireNonBlank(userName, "userName (resolved from Cav Token)");

        // Validate publish fields from credential
        if (credential.isPublishUserStories()) {
            requireNonBlank(credential.getIntegrationName(),
                    "Integration Name (required in Service Connection when Publish User Stories = true)");
            requireNonBlank(credential.getPublishTrackerType(),
                    "Publish Tracker Type (required in Service Connection when Publish User Stories = true)");
            requireNonBlank(credential.getPublishEpicName(),
                    "Publish Epic Name (required in Service Connection when Publish User Stories = true)");
        }

        JSONObject root = new JSONObject();

        // Top-level fields
        root.put("idempotencyKey",    idempotencyKey);
        root.put("project",           builder.getProject());
        root.put("subproject",        builder.getSubProject());
        root.put("testsuiteName",     builder.getTestSuiteName());
        root.put("numberOfTestCases", builder.getNumberOfTestCases());
        root.put("controllerName",    builder.getControllerName());
        root.put("userName",          userName);

        // Source
        JSONObject source = new JSONObject();
        source.put("type",    builder.getSourceType() != null ? builder.getSourceType() : "PRD");
        source.put("fileRef", uploadedFilename);
        root.put("source", source);

        // Application config (from pipeline step)
        JSONObject appConfig = new JSONObject();
        appConfig.put("applicationUrl",        builder.getApplicationUrl());
        appConfig.put("username",              nullSafe(builder.getUsername()));
        appConfig.put("password",              nullSafe(builder.getPassword()));
        appConfig.put("authentication_prompt", nullSafe(builder.getAuthenticationPrompt()));
        root.put("applicationConfig", appConfig);

        // Tags
        addTags(root, builder.getTags());

        // Publish user stories (from credential)
        root.put("publishUserStories", credential.isPublishUserStories());
        if (credential.isPublishUserStories()) {
            root.put("integrationName",    credential.getIntegrationName());
            root.put("publishTrackerType", credential.getPublishTrackerType());
            root.put("publishEpicName",    credential.getPublishEpicName());
        }

        return serialize(root);
    }

    // -- Build for JIRA source type (NEW) --------------------------------------

    /**
     * Builds the JSON payload for JIRA source type.
     *
     * Key differences from build():
     *   - source.epicPattern instead of source.fileRef
     *   - source.type = "JIRA"
     *   - integrationName at top level (not from credential publish settings)
     *   - NO publishUserStories
     *   - NO publishTrackerType
     *   - NO publishEpicName
     *   - NO fileRef
     *
     * @param builder      pipeline step configuration (project, testSuite, tags, applicationUrl, etc.)
     * @param credential   service connection (dashboardServerUrl, cavToken, etc.)
     * @param jiraRequest  JiraSourceRequest with epicPattern and integrationName
     * @param idempotencyKey Jenkins BUILD_TAG
     * @param userName     resolved via {@link com.cavisson.jenkins.ai.testcase.client.UserNameService}
     *                     from the Cav Token - never hardcoded, never defaulted here
     * @return JSON string ready to POST
     */
    public static String buildJira(
            CavAITestCaseBuilder   builder,
            CavServiceConnection credential,
            JiraSourceRequest      jiraRequest,
            String                 idempotencyKey,
            String                 userName) {

        requireApplicationUrl(builder);
        requireNonBlank(userName, "userName (resolved from Cav Token)");

        JSONObject root = new JSONObject();

        // Top-level fields (identical to build())
        root.put("idempotencyKey",    idempotencyKey);
        root.put("project",           builder.getProject());
        root.put("subproject",        builder.getSubProject());
        root.put("testsuiteName",     builder.getTestSuiteName());
        root.put("numberOfTestCases", builder.getNumberOfTestCases());
        root.put("controllerName",    builder.getControllerName());
        root.put("userName",          userName);

        // Source -- JIRA specific: epicPattern instead of fileRef
        JSONObject source = new JSONObject();
        source.put("type",         "JIRA");
        source.put("epicPattern",  jiraRequest.getEpicPattern());
        root.put("source", source);

        // Application config (from pipeline step, same structure as build())
        JSONObject appConfig = new JSONObject();
        appConfig.put("applicationUrl", builder.getApplicationUrl());
        appConfig.put("username",       nullSafe(builder.getUsername()));
        appConfig.put("password",       nullSafe(builder.getPassword()));
        root.put("applicationConfig", appConfig);

        // Tags
        addTags(root, builder.getTags());

        // Integration name at top level -- tells AI server which JIRA connection to use.
        // publishUserStories / publishTrackerType / publishEpicName are NOT included
        // for JIRA source per the API contract.
        root.put("integrationName", jiraRequest.getIntegrationName());

        return serialize(root);
    }

    // -- Shared helpers --------------------------------------------------------

    private static void addTags(JSONObject root, List<String> tags) {
        if (tags != null && !tags.isEmpty()) {
            JSONArray tagsNode = new JSONArray();
            for (String tag : tags) {
                if (tag != null && !tag.trim().isEmpty()) {
                    tagsNode.put(tag.trim());
                }
            }
            root.put("tags", tagsNode);
        }
    }

    private static String serialize(JSONObject root) {
        try {
            return root.toString(2);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise pipeline payload", e);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Required field is missing: " + fieldName);
        }
    }

    private static void requireApplicationUrl(CavAITestCaseBuilder builder) {
        if (builder.getApplicationUrl() == null || builder.getApplicationUrl().trim().isEmpty()) {
            throw new IllegalArgumentException("Application URL is required.");
        }
    }
}
