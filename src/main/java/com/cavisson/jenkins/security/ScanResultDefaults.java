package com.cavisson.jenkins.security;

/**
 * Shared per-scan-type defaults for the result map (reportUrl, message) that all three scan
 * flows (SAST, SCA, DAST) surface via {@link ScanResultPrinter} and the Pipeline step's return
 * value.
 */
final class ScanResultDefaults {

    private ScanResultDefaults() {
    }

    static String reportUrl(String baseUrl, String scanType) {
        String normalizedBaseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        return normalizedBaseUrl + "/UnifiedDashboard/share.html?open=security-orchestration-" + pathSuffix(scanType);
    }

    static String completionMessage(String scanType) {
        if ("SAST".equalsIgnoreCase(scanType)) {
            return "SAST scan completed successfully.";
        }
        if ("SCA".equalsIgnoreCase(scanType)) {
            return "SCA scan initiated, scanning will take 5-10 minutes to generate the report.";
        }
        if ("DAST".equalsIgnoreCase(scanType)) {
            return "DAST scan initiated, scanning will take 5-10 minutes to generate the report.";
        }
        return "";
    }

    private static String pathSuffix(String scanType) {
        if ("SAST".equalsIgnoreCase(scanType)) {
            return "sast";
        }
        if ("SCA".equalsIgnoreCase(scanType)) {
            return "sca";
        }
        if ("DAST".equalsIgnoreCase(scanType)) {
            return "dast";
        }
        return scanType == null ? "" : scanType.toLowerCase(java.util.Locale.ROOT);
    }
}
