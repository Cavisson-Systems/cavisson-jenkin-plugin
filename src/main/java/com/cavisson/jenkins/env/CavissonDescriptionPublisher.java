package com.cavisson.jenkins.env;

import hudson.EnvVars;
import hudson.model.Run;

import java.io.IOException;

/**
 * Appends a report-link row to the build's HTML description, shared by every task that produces
 * a report URL ({@code CavissonRunTest}, {@code CavissonStopCodeCoverage},
 * {@code AnalyseTestFailure}). Wraps the first row in a {@code <table>} and inserts subsequent
 * rows before the closing tag, so multiple calls in the same build (e.g. one per Pipeline stage,
 * or a {@code CavissonRunTest} followed by an {@code AnalyseTestFailure} on the same run) each
 * get their own row instead of clobbering the previous link. Unset (or any value other than
 * {@code true}/{@code 1}/{@code yes}, case-insensitive) means the row is appended as normal;
 * set the build/Pipeline environment variable {@code CAV_SKIP_REPORT_IN_DESC=true} to skip the
 * description write entirely - the report URL is still logged and published as an env var either way.
 */
public final class CavissonDescriptionPublisher {

    private static final String DISABLE_ENV_VAR = "CAV_SKIP_REPORT_IN_DESC";

    private CavissonDescriptionPublisher() {
    }

    public static void appendReportRow(Run<?, ?> run, EnvVars env, String reportUrl) throws IOException {
        if (reportUrl == null || reportUrl.trim().isEmpty()) {
            return;
        }

        if (isDisabled(env)) {
            return;
        }

        String stageName = env == null ? "" : env.get("STAGE_NAME", "");
        String row = "<tr><td><b>" + stageName + "</b></td><td><a href='" + reportUrl
                + "' target='_blank'>📊 View Report</a></td></tr>";

        String existing = run.getDescription();
        String updated;
        if (existing == null || existing.trim().isEmpty()) {
            updated = "<table>" + row + "</table>";
        } else if (existing.contains("</table>")) {
            updated = existing.replace("</table>", row + "</table>");
        } else {
            updated = existing + "<table>" + row + "</table>";
        }
        run.setDescription(updated);
    }

    private static boolean isDisabled(EnvVars env) {
        if (env == null) {
            return false;
        }
        String value = env.get(DISABLE_ENV_VAR, "");
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }
}
