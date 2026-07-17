package com.cavisson.jenkins.security;

import com.cavisson.jenkins.scriptlog.CavLogger;

import hudson.model.TaskListener;

final class ScanResultPrinter {

    private ScanResultPrinter() {
    }

    static void print(TaskListener listener,
                      String scanType,
                      String runMode,
                      String toolName,
                      boolean success,
                      String status,
                      String scanId,
                      String reportUrl,
                      String message) {

        CavLogger.debug(listener, "========== Cavisson Security Scan Result ==========");
        CavLogger.debug(listener, "Scan Type  : " + safe(scanType));
        CavLogger.debug(listener, "Run Mode   : " + safe(runMode));
        CavLogger.debug(listener, "Tool       : " + safe(toolName));
        CavLogger.debug(listener, "Success    : " + success);
        CavLogger.debug(listener, "Status     : " + safe(status));
        CavLogger.debug(listener, "Scan ID    : " + safeOrNa(scanId));
        CavLogger.debug(listener, "Report URL : " + safeOrNa(reportUrl));

        if (message != null && !message.trim().isEmpty()) {
            CavLogger.debug(listener, "Message    : " + message);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safeOrNa(String value) {
        return value == null || value.trim().isEmpty() ? "N/A" : value;
    }
}