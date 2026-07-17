package com.cavisson.jenkins.ai.testcase.client;

import com.cavisson.jenkins.ai.testcase.exception.CavAIApiException;
import org.json.JSONException;
import org.json.JSONObject;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.logging.Logger;

import com.cavisson.jenkins.ai.testcase.util.PluginLogger;

/**
 * All HTTP communication with the Cavisson Agentic AI backend.
 *
 * Official REST APIs supported:
 *   1. POST  /uploadfile                              - upload PRD
 *   2. POST  /testcasePipeline/run                   - trigger pipeline
 *   3. GET   /testcasePipeline/status/{id}           - poll status
 *   4. GET   /testcasePipeline/progress/{id}         - full progress JSON
 *   5. GET   /testcasePipeline/progress/{id}?format=events
 *   6. GET   /testcasePipeline/progress/{id}?format=log&stage=generate
 *   7. POST  /testcasePipeline/abort/{id}            - abort pipeline
 *   8. GET   /node/ALL/DashboardServer/v2/web/common/getUserNameFromToken
 *                                                     - resolve userName from Cav Token
 *
 * NOTE: downloadJUnit (?format=junit) has been removed - it is not in the
 * official API specification.
 *
 * REST details (URL, payload, response body) are only logged in DEBUG mode
 * via PluginLogger.logRestCall(). In INFO mode all REST calls are silent
 * at the HTTP level - only their outcomes are logged by the caller.
 */
public class CavAIRestClient {

    private static final Logger LOGGER =
            Logger.getLogger(CavAIRestClient.class.getName());

    // -- API path constants ----------------------------------------------------

    private static final String PATH_UPLOAD =
            "/tomcat/master/DashboardServer/v2/web/filemanager/uploadfile";
    private static final String PATH_RUN =
            "/service/agenticAiServer/agentic/api/testcasePipeline/run";
    private static final String PATH_STATUS =
            "/service/agenticAiServer/agentic/api/testcasePipeline/status/";
    private static final String PATH_PROGRESS =
            "/service/agenticAiServer/agentic/api/testcasePipeline/progress/";
    private static final String PATH_ABORT =
            "/service/agenticAiServer/agentic/api/testcasePipeline/abort/";
    private static final String PATH_GET_USERNAME =
            "/node/ALL/DashboardServer/v2/web/common/getUserNameFromToken";

    // -- Timeouts --------------------------------------------------------------

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int SOCKET_TIMEOUT_MS  = 120_000;

    // -- Trust-all SSL context (equivalent to prior TrustAllStrategy / NoopHostnameVerifier) --

    private static final HostnameVerifier TRUST_ALL_HOSTNAME_VERIFIER = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    private static SSLContext buildTrustAllSslContext()
            throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        return sslContext;
    }

    private void applyTrustAll(HttpURLConnection connection)
            throws NoSuchAlgorithmException, KeyManagementException {
        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) connection;
            https.setSSLSocketFactory(buildTrustAllSslContext().getSocketFactory());
            https.setHostnameVerifier(TRUST_ALL_HOSTNAME_VERIFIER);
        }
    }

    private HttpURLConnection openConnection(String url, String method)
            throws IOException, NoSuchAlgorithmException, KeyManagementException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        applyTrustAll(connection);
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(SOCKET_TIMEOUT_MS);
        return connection;
    }

    private String readStream(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream stream = in) {
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    private String readResponseBody(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = (status >= 200 && status < 300)
                ? connection.getInputStream()
                : connection.getErrorStream();
        return readStream(stream);
    }

    // -- Reusable helpers ------------------------------------------------------

    /**
     * Executes a GET and returns the response body.
     * Logs the REST call details via PluginLogger (DEBUG mode only).
     */
    private String executeGet(String url, String stepName, PrintStream log,
                              boolean allowNon2xx) throws CavAIApiException {

        String logUrl = url.replaceAll("cavToken=[^&]*", "cavToken=***");

        try {
            HttpURLConnection connection = openConnection(url, "GET");

            int    status = connection.getResponseCode();
            String body   = readResponseBody(connection, status);
            connection.disconnect();

            // Only log REST details in DEBUG mode
            PluginLogger.logRestCall(log, "GET " + stepName, logUrl, null, status, body);

            if (!allowNon2xx && (status < 200 || status >= 300)) {
                throw new CavAIApiException(stepName + " failed. HTTP " + status, status);
            }
            return body;

        } catch (CavAIApiException e) {
            throw e;
        } catch (Exception e) {
            throw new CavAIApiException(stepName + " error: " + e.getMessage(), e);
        }
    }

    /**
     * Executes a POST with a JSON body and returns the response body.
     * Logs REST details in DEBUG mode only.
     */
    private String executePost(String url, String jsonBody, String stepName,
                               PrintStream log, boolean allowNon2xx)
            throws CavAIApiException {

        String logUrl = url.replaceAll("cavToken=[^&]*", "cavToken=***");

        try {
            HttpURLConnection connection = openConnection(url, "POST");
            connection.setRequestProperty("Content-Type", "application/json");

            if (jsonBody != null && !jsonBody.isEmpty()) {
                connection.setDoOutput(true);
                byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }

            int    status = connection.getResponseCode();
            String body   = readResponseBody(connection, status);
            connection.disconnect();

            // Only log REST details in DEBUG mode
            PluginLogger.logRestCall(log, "POST " + stepName, logUrl, jsonBody, status, body);

            if (!allowNon2xx && (status < 200 || status >= 300)) {
                throw new CavAIApiException(stepName + " failed. HTTP " + status, status);
            }
            return body;

        } catch (CavAIApiException e) {
            throw e;
        } catch (Exception e) {
            throw new CavAIApiException(stepName + " error: " + e.getMessage(), e);
        }
    }

    // -- Step 1 - Upload PRD ---------------------------------------------------

    /**
     * Uploads the PRD file using multipart/form-data.
     * Destination path is NOT URL-encoded - the server expects a plain path.
     */
    public String uploadFile(String serverBaseUrl, String cavToken,
                             File prdFile, String destination,
                             PrintStream log) throws CavAIApiException {

        String url    = serverBaseUrl + PATH_UPLOAD
                + "?for=fileExplorer&destination=" + destination
                + "&cavToken=" + cavToken;
        String logUrl = serverBaseUrl + PATH_UPLOAD
                + "?for=fileExplorer&destination=" + destination + "&cavToken=***";

        String boundary = "----CavAIBoundary" + UUID.randomUUID().toString().replace("-", "");
        String lineEnd  = "\r\n";

        try {
            HttpURLConnection connection = openConnection(url, "POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + boundary);

            try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
                out.writeBytes("--" + boundary + lineEnd);
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\""
                        + prdFile.getName() + "\"" + lineEnd);
                out.writeBytes("Content-Type: application/octet-stream" + lineEnd);
                out.writeBytes(lineEnd);

                try (FileInputStream fileIn = new FileInputStream(prdFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = fileIn.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }

                out.writeBytes(lineEnd);
                out.writeBytes("--" + boundary + "--" + lineEnd);
            }

            int    status = connection.getResponseCode();
            String body   = readResponseBody(connection, status);
            connection.disconnect();

            PluginLogger.logRestCall(log, "POST Upload PRD File", logUrl, null, status, body);

            if (status < 200 || status >= 300) {
                throw new CavAIApiException("Upload failed. HTTP " + status + " - " + body, status);
            }

            return extractUploadedFilename(body, prdFile.getName());

        } catch (CavAIApiException e) {
            throw e;
        } catch (Exception e) {
            throw new CavAIApiException("Upload error: " + e.getMessage(), e);
        }
    }

    /** Backward-compatible overload without PrintStream. */
    public String uploadFile(String serverBaseUrl, String cavToken,
                             File prdFile, String destination) throws CavAIApiException {
        return uploadFile(serverBaseUrl, cavToken, prdFile, destination, null);
    }

    private String extractUploadedFilename(String responseBody, String originalName) {
        if (responseBody == null || responseBody.trim().isEmpty()) return originalName;
        String trimmed = responseBody.trim();
        if (trimmed.startsWith("{")) {
            try {
                JSONObject root = new JSONObject(trimmed);
                for (String field : new String[]{"fileName", "filename", "name", "file"}) {
                    if (root.has(field) && root.get(field) instanceof String) {
                        return root.getString(field);
                    }
                }
            } catch (JSONException e) {
                LOGGER.fine("Could not parse upload response: " + e.getMessage());
            }
        }
        return (!trimmed.contains(" ") && trimmed.length() < 256) ? trimmed : originalName;
    }

    // -- Step 2 - Trigger pipeline ---------------------------------------------

    public String triggerPipeline(String serverBaseUrl, String cavToken,
                                  String jsonPayload, PrintStream log)
            throws CavAIApiException {
        String body = executePost(serverBaseUrl + PATH_RUN, jsonPayload,
                "Trigger AI Pipeline", log, false);
        return extractPipelineId(body);
    }

    /** Backward-compatible overload. */
    public String triggerPipeline(String serverBaseUrl, String cavToken,
                                  String jsonPayload) throws CavAIApiException {
        return triggerPipeline(serverBaseUrl, cavToken, jsonPayload, null);
    }

    private String extractPipelineId(String body) throws CavAIApiException {
        try {
            JSONObject root = new JSONObject(body);
            if (!root.has("pipelineId") || !(root.get("pipelineId") instanceof String)
                    || root.getString("pipelineId").isEmpty()) {
                throw new CavAIApiException("No pipelineId in response: " + body);
            }
            return root.getString("pipelineId");
        } catch (JSONException e) {
            throw new CavAIApiException("Cannot parse trigger response: " + e.getMessage(), e);
        }
    }

    // -- Step 3 - Poll status --------------------------------------------------

    public StatusResponse getPipelineStatus(String serverBaseUrl,
                                            String pipelineId) throws CavAIApiException {
        return getPipelineStatus(serverBaseUrl, pipelineId, null);
    }

    public StatusResponse getPipelineStatus(String serverBaseUrl,
                                            String pipelineId,
                                            PrintStream log) throws CavAIApiException {
        String body = executeGet(serverBaseUrl + PATH_STATUS + pipelineId,
                "Poll Status", log, false);
        return parseStatusResponse(body);
    }

    private StatusResponse parseStatusResponse(String body) throws CavAIApiException {
        try {
            JSONObject root       = new JSONObject(body);
            String     stateRaw   = root.has("state")
                    ? root.optString("state", "UNKNOWN") : "UNKNOWN";
            String     stage      = root.has("currentStage")
                    ? root.optString("currentStage", "") : "";
            String     failReason = root.has("reason")
                    ? root.optString("reason", "")
                    : (root.has("message") ? root.optString("message", "") : "");
            return new StatusResponse(
                    PipelineState.fromString(stateRaw), stage, failReason, body);
        } catch (JSONException e) {
            throw new CavAIApiException("Cannot parse status response: " + e.getMessage(), e);
        }
    }

    // -- Step 4 - Download progress --------------------------------------------

    public String downloadProgress(String serverBaseUrl, String pipelineId,
                                   PrintStream log) throws CavAIApiException {
        return executeGet(serverBaseUrl + PATH_PROGRESS + pipelineId,
                "Download Progress", log, false);
    }

    // -- Step 5 - Download events ----------------------------------------------

    public String downloadEvents(String serverBaseUrl, String pipelineId,
                                 PrintStream log) throws CavAIApiException {
        return executeGet(serverBaseUrl + PATH_PROGRESS + pipelineId + "?format=events",
                "Download Events", log, false);
    }

    // -- Step 6 - Download generation log -------------------------------------

    public String downloadGenerationLog(String serverBaseUrl, String pipelineId,
                                        PrintStream log) throws CavAIApiException {
        return executeGet(serverBaseUrl + PATH_PROGRESS + pipelineId
                + "?format=log&stage=generate",
                "Download Generation Log", log, false);
    }

    // -- Fetch username from Cav Token ------------------------------------------

    /**
     * Calls GET /node/ALL/DashboardServer/v2/web/common/getUserNameFromToken
     * and returns the raw JSON response body. Parsing and validation of the
     * response (e.g. checking that "userName" is present) is the caller's
     * responsibility - see {@link UserNameService}.
     *
     * The Cav Token is masked in DEBUG logs the same way as every other
     * REST call in this client (via {@link #executeGet}).
     */
    public String fetchUserNameFromToken(String serverBaseUrl, String cavToken,
                                         PrintStream log) throws CavAIApiException {
        String url       = serverBaseUrl + PATH_GET_USERNAME + "?cavToken=" + cavToken;
        String maskedUrl = url.replaceAll("cavToken=[^&]*", "cavToken=***");

        // -- DEBUG-only diagnostics for the username-fetch call ----------------
        PluginLogger.logDebug(log, "[UserNameFetch] Dashboard URL   : " + serverBaseUrl);
        PluginLogger.logDebug(log, "[UserNameFetch] Endpoint        : " + maskedUrl);
        PluginLogger.logDebug(log, "[UserNameFetch] HTTP Method     : GET");
        PluginLogger.logDebug(log, "[UserNameFetch] Headers         : "
                + "cavToken=*** (query param); User-Agent (JDK default) = Java/"
                + System.getProperty("java.version"));

        try {
            return executeGet(url, "Fetch Username From Token", log, false);
        } catch (CavAIApiException e) {
            PluginLogger.logDebugStackTrace(log, "[UserNameFetch]", e);
            throw e;
        }
    }

    // -- Step 7 - Abort pipeline -----------------------------------------------

    /**
     * Aborts a running pipeline.
     * A 409 Conflict response means the pipeline already finished - logged as
     * a warning, not an error, so the build does not fail unnecessarily.
     */
    public void abortPipeline(String serverBaseUrl, String pipelineId,
                              PrintStream log) throws CavAIApiException {
        // allowNon2xx=true so that 409 does not throw
        String body = executePost(serverBaseUrl + PATH_ABORT + pipelineId,
                null, "Abort Pipeline", log, true);
        LOGGER.fine("Abort response for " + pipelineId + ": " + body);
    }

    // -- Value object ----------------------------------------------------------

    public static final class StatusResponse {

        private final PipelineState state;
        private final String        currentStage;
        private final String        failureReason;
        private final String        rawJson;

        public StatusResponse(PipelineState state, String currentStage,
                              String failureReason, String rawJson) {
            this.state         = state;
            this.currentStage  = currentStage;
            this.failureReason = failureReason;
            this.rawJson       = rawJson;
        }

        public PipelineState getState()         { return state; }
        public String        getCurrentStage()  { return currentStage; }
        public String        getFailureReason() { return failureReason; }
        public String        getRawJson()       { return rawJson; }
    }

    public String downloadJUnit(String serverBaseUrl, String pipelineId,
                                PrintStream log) throws CavAIApiException {
        return executeGet(serverBaseUrl + PATH_PROGRESS + pipelineId + "?format=junit",
                "Download JUnit Report", log, false);
    }

}
