/**
 * generate-report.js
 * Renders the professional accessibility HTML report.
 *
 * Called from task/index.js as:  node generate-report.js
 * REPORTS_DIR env var points to Azure BUILD_ARTIFACTSTAGINGDIRECTORY.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CONTRACT PRESERVED FROM PREVIOUS VERSION
 * ─────────────────────────────────────────────────────────────────────────────
 *   • CLI signature unchanged     : `node generate-report.js` (no args)
 *   • Env inputs unchanged        : REPORTS_DIR (also honours legacy fallback
 *                                    ./reports if REPORTS_DIR is unset)
 *   • Output file unchanged       : accessibility-report.html at REPORTS_DIR
 *   • Exit codes unchanged        : 0 on success, 1 on failure
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT CHANGED
 * ─────────────────────────────────────────────────────────────────────────────
 *   The previous version built basic table HTML inline (~460 lines).
 *   This version is a ~50-line adapter that delegates to
 *   ./report/reportGenerator.js — a Handlebars-based renderer producing a
 *   Google-Lighthouse-style report with:
 *     • Executive summary + accessibility score gauge
 *     • Severity dashboard (Critical / Serious / Moderate / Minor)
 *     • Full-page screenshot
 *     • Rule categories with accordion cards
 *     • Per-element screenshots, HTML snippet, CSS selector, XPath, failure summary
 *     • Passed / Needs-Review / Not-Applicable sections
 *     • Suggested-fix banners
 *
 * The reportGenerator embeds every image as base64 in the HTML, so the
 * uploaded file remains a single self-contained artefact — no relative
 * dependencies for the Cavisson UI to serve.
 *
 * FALLBACK: if accessibility-report-scaninfo.json is missing (e.g. old scan
 * run before this update), we reconstruct it from the report JSON so the
 * generator always has usable inputs.
 */

const fs   = require('fs');
const path = require('path');

const { generateReport } = require('./report/reportGenerator');
const { calculateScore, writeJSON } = require('./report/utils');

// =========================================
// Report File Names (all unchanged)
// =========================================

const REPORT_BASENAME  = 'accessibility-report';
const HTML_REPORT_FILE = `${REPORT_BASENAME}.html`;
const JSON_REPORT_FILE = `${REPORT_BASENAME}.json`;
const SCAN_INFO_FILE   = `${REPORT_BASENAME}-scaninfo.json`;

// =========================================
// Fallback: rebuild scanInfo if the sidecar is missing.
// This keeps the generator working even if scan.js was skipped or the
// sidecar was lost between steps.
// =========================================

function synthesiseScanInfo(reportsDir, reportJsonPath) {
    const raw = JSON.parse(fs.readFileSync(reportJsonPath, 'utf8'));

    const counts = { critical: 0, serious: 0, moderate: 0, minor: 0 };
    for (const v of (raw.violations || [])) {
        if (counts[v.impact] !== undefined) counts[v.impact]++;
    }

    const scanInfo = {
        targetUrl:     (raw.url) || (raw.testEnvironment && raw.testEnvironment.userAgent) || '',
        pageTitle:     '',
        scanDate:      raw.timestamp || new Date().toISOString(),
        score:         calculateScore(counts),
        criticalCount: counts.critical,
        seriousCount:  counts.serious,
        moderateCount: counts.moderate,
        minorCount:    counts.minor
    };

    const outPath = path.join(reportsDir, SCAN_INFO_FILE);
    writeJSON(outPath, scanInfo);
    console.log('ℹ  scanInfo sidecar was missing — synthesised from report JSON.');
    return outPath;
}

// =========================================
// Main
// =========================================

(async () => {
    try {

        const reportsDir = process.env.REPORTS_DIR || './reports';

        const reportJsonPath   = path.join(reportsDir, JSON_REPORT_FILE);
        const outputHtmlPath   = path.join(reportsDir, HTML_REPORT_FILE);
        let   scanInfoJsonPath = path.join(reportsDir, SCAN_INFO_FILE);

        if (!fs.existsSync(reportJsonPath)) {
            throw new Error(`Report JSON not found: ${reportJsonPath}`);
        }

        // Rebuild scanInfo sidecar if scan.js hasn't produced one.
        if (!fs.existsSync(scanInfoJsonPath)) {
            scanInfoJsonPath = synthesiseScanInfo(reportsDir, reportJsonPath);
        }

        await generateReport({
            reportJsonPath,
            scanInfoJsonPath,
            outputHtmlPath,
            outputDir: reportsDir
        });

        console.log('✅ Professional Accessibility HTML Report Generated');

    } catch (err) {

        console.log('❌ HTML Report Generation Failed');
        console.log(err.stack || err.message);
        process.exit(1);

    }
})();
