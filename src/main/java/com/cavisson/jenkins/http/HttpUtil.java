package com.cavisson.jenkins.http;

import com.cavisson.jenkins.config.CavissonGlobalConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal HTTP client used to call the Cavisson DashboardServer REST endpoints, with no extra
 * runtime HTTP client dependency. Shared by every task in this plugin.
 *
 * Trust-all SSL is applied only when the caller asks for it <em>and</em> an administrator has
 * enabled it globally ({@link CavissonGlobalConfiguration}); otherwise normal JVM certificate and
 * hostname verification applies.
 */
public final class HttpUtil {

    public static final class HttpResult {
        public final int statusCode;
        public final String body;

        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private HttpUtil() {
    }

    public static HttpResult postJson(String url, String jsonBody, Map<String, String> headers, boolean allowInsecureSSL) throws IOException {
        return request("POST", url, jsonBody, headers, allowInsecureSSL);
    }

    public static HttpResult getJson(String url, Map<String, String> headers, boolean allowInsecureSSL) throws IOException {
        return request("GET", url, null, headers, allowInsecureSSL);
    }

    /**
     * Uploads a single local file as {@code multipart/form-data} (form field name {@code "file"}),
     * the same request shape the Cavisson DashboardServer's {@code filemanager/uploadfile} endpoint
     * expects. {@code headers} carries authentication (e.g. {@code Authorization: Bearer <cavToken>})
     * - never logged by this method. Added for {@code AccessibilityScannerExecutor}'s report upload;
     * shared by any future task needing plain file uploads.
     */
    public static HttpResult postMultipartFile(String url, File file, String formFieldName,
                                                Map<String, String> headers, boolean allowInsecureSSL) throws IOException {
        String boundary = "----CavJenkinsBoundary" + UUID.randomUUID().toString().replace("-", "");
        String lineEnd = "\r\n";

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();

        if (allowInsecureSSL && CavissonGlobalConfiguration.insecureSslAllowed()
                && connection instanceof HttpsURLConnection) {
            applyTrustAllSsl((HttpsURLConnection) connection);
        }

        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(300000);
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }

        try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
            out.writeBytes("--" + boundary + lineEnd);
            out.writeBytes("Content-Disposition: form-data; name=\"" + formFieldName + "\"; filename=\""
                    + file.getName() + "\"" + lineEnd);
            out.writeBytes("Content-Type: application/octet-stream" + lineEnd);
            out.writeBytes(lineEnd);

            try (FileInputStream fileIn = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fileIn.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            out.writeBytes(lineEnd);
            out.writeBytes("--" + boundary + "--" + lineEnd);
        }

        int statusCode = connection.getResponseCode();
        InputStream stream = statusCode >= 200 && statusCode < 400
                ? connection.getInputStream()
                : connection.getErrorStream();

        String responseBody = stream == null ? "" : readFully(stream);

        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("HTTP " + statusCode + " uploading to " + url + ": " + responseBody);
        }

        return new HttpResult(statusCode, responseBody);
    }

    private static HttpResult request(String method, String url, String body, Map<String, String> headers, boolean allowInsecureSSL) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();

        if (allowInsecureSSL && CavissonGlobalConfiguration.insecureSslAllowed()
                && connection instanceof HttpsURLConnection) {
            applyTrustAllSsl((HttpsURLConnection) connection);
        }

        connection.setRequestMethod(method);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(300000);
        connection.setDoInput(true);

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }

        if (body != null) {
            connection.setDoOutput(true);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(payload);
            }
        }

        int statusCode = connection.getResponseCode();
        InputStream stream = statusCode >= 200 && statusCode < 400
                ? connection.getInputStream()
                : connection.getErrorStream();

        String responseBody = stream == null ? "" : readFully(stream);

        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("HTTP " + statusCode + " calling " + url + ": " + responseBody);
        }

        return new HttpResult(statusCode, responseBody);
    }

    private static String readFully(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = inputStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("lgtm[jenkins/unsafe-calls]") // trust-all TLS only when an admin opts in via CavissonGlobalConfiguration
    private static void applyTrustAllSsl(HttpsURLConnection connection) {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());

            SSLSocketFactory socketFactory = sslContext.getSocketFactory();
            connection.setSSLSocketFactory(socketFactory);
            connection.setHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                    return true;
                }
            });
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new IllegalStateException("Unable to configure insecure SSL context", e);
        }
    }
}
