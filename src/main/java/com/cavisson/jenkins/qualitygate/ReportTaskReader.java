package com.cavisson.jenkins.qualitygate;

import hudson.FilePath;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads SonarQube's {@code .scannerwork/report-task.txt} from the workspace, if a Static scan
 * left one behind, so cavQualityGate can correlate its combined evaluation with the SonarQube
 * analysis (projectKey/ceTaskId/ceTaskUrl) the same way the per-stage Static evaluation does.
 */
final class ReportTaskReader {

    private ReportTaskReader() {
    }

    static Map<String, String> readProperties(FilePath workspace) throws IOException, InterruptedException {
        Map<String, String> properties = new HashMap<>();

        FilePath reportTaskFile = workspace.child(".scannerwork/report-task.txt");
        if (!reportTaskFile.exists()) {
            return properties;
        }

        String content = reportTaskFile.readToString();
        if (content == null || content.isEmpty()) {
            return properties;
        }

        for (String line : content.split("\\r?\\n")) {
            String trimmedLine = line.trim();
            int separatorIndex = trimmedLine.indexOf('=');

            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#") || separatorIndex < 0) {
                continue;
            }

            String key = trimmedLine.substring(0, separatorIndex).trim();
            String value = trimmedLine.substring(separatorIndex + 1).trim();
            properties.put(key, value);
        }

        return properties;
    }
}
