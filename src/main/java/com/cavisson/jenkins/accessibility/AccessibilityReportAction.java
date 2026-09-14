package com.cavisson.jenkins.accessibility;

import hudson.model.Action;

/**
 * Contributes a "View Accessibility Report" sidebar entry and build-page summary link, pointing
 * directly at the per-run shareable deep link ({@code share.html?open=accessibility-report&reportId=<id>}).
 *
 * <p>Exists specifically because the two other mechanisms already used to surface this URL each
 * have a caveat that this one doesn't:
 * <ul>
 *   <li>{@code CavLogger#infoHyperlink} (console) - reliably clickable, but only visible if the
 *       user is actually reading the console log, not glancing at the build page.</li>
 *   <li>{@code CavissonDescriptionPublisher#appendReportRow} (build description) - writes raw
 *       {@code <a href=...>} HTML via {@code run.setDescription(...)}. Whether that HTML actually
 *       *renders* as a clickable link, or shows up as literal escaped text, depends entirely on
 *       the Jenkins instance's global Markup Formatter setting (Manage Jenkins &gt; Security) -
 *       under the default "Plain Text" formatter it is not clickable at all.</li>
 * </ul>
 * A Jelly-rendered {@link Action} (see {@code summary.jelly} alongside this class) is unaffected
 * by that setting - Jenkins always renders an Action's summary/sidepanel views as real markup -
 * so this is the one mechanism guaranteed to produce an actual clickable link regardless of how
 * the instance is configured. The other two are kept as-is for console/description parity; this
 * one is additive, not a replacement.
 */
public final class AccessibilityReportAction implements Action {

    private final String reportUrl;

    public AccessibilityReportAction(String reportUrl) {
        this.reportUrl = reportUrl;
    }

    /** Used by {@code summary.jelly} (as {@code it.reportUrl}). */
    public String getReportUrl() {
        return reportUrl;
    }

    @Override
    public String getIconFileName() {
        // Deliberately null: a non-null value here also controls whether Jenkins adds a sidebar
        // entry, and that code path resolves the icon too. Same reasoning as summary.jelly - an
        // icon-resolution problem on a given Jenkins core version must never be able to take the
        // link down with it. This trades away the sidebar entry; the guaranteed-safe link lives
        // on the build page body via summary.jelly instead.
        return null;
    }

    @Override
    public String getDisplayName() {
        return "View Accessibility Report";
    }

    @Override
    public String getUrlName() {
        return reportUrl;
    }
}
