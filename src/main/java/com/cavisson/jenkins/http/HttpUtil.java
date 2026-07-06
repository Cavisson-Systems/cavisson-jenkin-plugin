package com.cavisson.jenkins.http;

import java.io.ByteArrayOutputStream;
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
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal HTTP client used to call the Cavisson DashboardServer REST endpoints, with no extra
 * runtime HTTP client dependency. Shared by every task in this plugin.
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

    private static HttpResult request(String method, String url, String body, Map<String, String> headers, boolean allowInsecureSSL) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();

        if (allowInsecureSSL && connection instanceof HttpsURLConnection) {
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
