/**
 * scan.js
 * Runs an accessibility scan against the provided URL.
 *
 * Called from task/index.js as:   node scan.js "<targetUrl>"
 * REPORTS_DIR env var points to Azure BUILD_ARTIFACTSTAGINGDIRECTORY.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CONTRACT PRESERVED FROM PREVIOUS VERSION
 * ─────────────────────────────────────────────────────────────────────────────
 *   • CLI signature unchanged                : `node scan.js "<url>"`
 *   • Uses @axe-core/playwright (AxeBuilder) : unchanged
 *   • Writes accessibility-report.json       : unchanged filename & location
 *   • JSON schema is EXTENDED, not trimmed   : new fields on each violation
 *                                              node (xpath, screenshotFile,
 *                                              screenshotReason). Existing
 *                                              consumers reading `impact`,
 *                                              `id`, `nodes[].html`, etc.
 *                                              continue to work unchanged.
 *   • Exit codes                             : unchanged (0 on success, 1 fail)
 * ─────────────────────────────────────────────────────────────────────────────
 * ADDITIONS FOR THE PROFESSIONAL REPORT
 * ─────────────────────────────────────────────────────────────────────────────
 *   • Captures page title (for report header)
 *   • Captures full-page screenshot → application.png (report hero)
 *   • Captures per-element screenshots → screenshots/*.png (violation cards)
 *   • Computes accessibility score + severity counts
 *   • Writes accessibility-scaninfo.json (score/counts/url/title/date) — a
 *     small sidecar consumed only by generate-report.js. Does not affect
 *     uploads or MongoDB metadata in task/index.js.
 * ─────────────────────────────────────────────────────────────────────────────
 * Every enrichment step is defensive (try/catch). A screenshot failure never
 * fails the scan; the violation is simply reported without an image.
 */

const { chromium }    = require('playwright');
const AxeBuilder      = require('@axe-core/playwright').default;
const fs              = require('fs');
const path            = require('path');

const { captureElementScreenshots } = require('./report/screenshotHelper');
const { ensureDir, writeJSON, calculateScore } = require('./report/utils');

// =========================================
// Report File Names (unchanged)
// =========================================

const REPORT_BASENAME  = 'accessibility-report';
const JSON_REPORT_FILE = `${REPORT_BASENAME}.json`;

// New sidecar (consumed only by generate-report.js — does not affect uploads)
const SCAN_INFO_FILE   = `${REPORT_BASENAME}-scaninfo.json`;
const APP_SCREENSHOT   = 'application.png';
const SCREENSHOTS_SUB  = 'screenshots';

// =========================================
// Helpers
// =========================================

function countBySeverity(violations) {
    const counts = { critical: 0, serious: 0, moderate: 0, minor: 0 };
    for (const v of violations) if (counts[v.impact] !== undefined) counts[v.impact]++;
    return counts;
}

(async () => {

    let browser;

    try {

        const url = process.argv[2];

        if (!url) {
            console.log('❌ Please provide URL');
            process.exit(1);
        }

        console.log('=======================================');
        console.log('🚀 Starting Accessibility Scan');
        console.log('🌐 Opening:', url);
        console.log('=======================================');

        // =========================================
        // Reports directory (unchanged)
        // =========================================

        const reportsDir = process.env.REPORTS_DIR || path.join(__dirname, 'reports');
        console.log('Reports Directory:', reportsDir);

        ensureDir(reportsDir);

        const screenshotsDir = path.join(reportsDir, SCREENSHOTS_SUB);
        ensureDir(screenshotsDir);

        // =========================================
        // Launch browser (unchanged)
        // =========================================

        browser = await chromium.launch({
            headless: true
        });

        const context = await browser.newContext({
            ignoreHTTPSErrors: true,
            viewport:          { width: 1366, height: 768 }
        });

        const page = await context.newPage();

        // =========================================
        // Navigate (unchanged)
        // =========================================

        await page.goto(url, {
            waitUntil: 'domcontentloaded',
            timeout:   30000
        });

        await page.waitForLoadState('networkidle', { timeout: 10000 })
            .catch(() => { /* some pages never idle — proceed anyway */ });

        // ─── ADDITION: capture page title for the report header ───────────
        let pageTitle = '';
        try {
            pageTitle = await page.title();
        } catch { pageTitle = url; }

        // ─── ADDITION: full-page screenshot for the report hero ───────────
        const appScreenshotPath = path.join(reportsDir, APP_SCREENSHOT);
        try {
            await page.screenshot({ path: appScreenshotPath, fullPage: true });
            console.log('📸 Full-page screenshot →', appScreenshotPath);
        } catch (err) {
            console.log('⚠  Full-page screenshot failed (non-fatal):', err.message);
        }

        // =========================================
        // Run axe accessibility scan (UNCHANGED)
        // =========================================

        const results = await new AxeBuilder({ page }).analyze();

        // ─── ADDITION: per-element screenshots + xpath enrichment ─────────
        //
        // Failures here are non-fatal: the violations are still written, just
        // without inline screenshots (the report template renders a friendly
        // "Screenshot not captured" placeholder in that case).

        let enrichedViolations = results.violations;
        try {
            enrichedViolations = await captureElementScreenshots(
                page,
                results.violations,
                screenshotsDir
            );
            console.log('📸 Element screenshots captured for',
                        enrichedViolations.length, 'rule(s).');
        } catch (err) {
            console.log('⚠  Element screenshot pass failed (non-fatal):', err.message);
        }

        // =========================================
        // Save JSON report (enriched — schema is a superset of original)
        // =========================================

        const jsonReportPath = path.join(reportsDir, JSON_REPORT_FILE);

        const enrichedResults = {
            ...results,
            violations: enrichedViolations
        };

        fs.writeFileSync(
            jsonReportPath,
            JSON.stringify(enrichedResults, null, 2)
        );

        // ─── ADDITION: write scan-info sidecar ────────────────────────────
        //
        // Consumed by generate-report.js. NOT uploaded, NOT read by index.js.
        // Zero impact on the existing pipeline outputs.

        const counts = countBySeverity(enrichedViolations);
        const scanInfo = {
            targetUrl:     url,
            pageTitle:     pageTitle,
            scanDate:      new Date().toISOString(),
            score:         calculateScore(counts),
            criticalCount: counts.critical,
            seriousCount:  counts.serious,
            moderateCount: counts.moderate,
            minorCount:    counts.minor
        };
        writeJSON(path.join(reportsDir, SCAN_INFO_FILE), scanInfo);

        // =========================================
        // Original logs (unchanged text)
        // =========================================

        console.log('✅ Accessibility scan completed');
        console.log('📄 JSON report generated');
        console.log('🔍 Violations Found:', enrichedResults.violations.length);
        console.log('📁 Report Path:', jsonReportPath);

        await browser.close();
        browser = null;

        console.log('=======================================');
        console.log('✅ Scan Finished Successfully');
        console.log('=======================================');

    } catch (err) {

        console.log('❌ Scan Failed');
        console.log(err.stack || err.message);

        if (browser) {
            try { await browser.close(); } catch { /* ignore */ }
        }

        process.exit(1);

    }

})();
