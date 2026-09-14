/**
 * reportGenerator.js
 * Builds the professional HTML report via Handlebars.
 *
 * Origin: axe-local-scanner/reportGenerator.js.
 * Only change vs. source: the OUTPUT_DIR (where screenshots and application.png
 * are resolved for base64 embedding) is now an explicit parameter passed to
 * `generateReport(...)`, not a hardcoded `__dirname/output` constant. This lets
 * the Cavisson extension point at REPORTS_DIR (Azure BUILD_ARTIFACTSTAGINGDIRECTORY)
 * without touching the extension source layout.
 *
 * Handlebars helpers, score-gauge SVG, category iteration and category enrichment
 * logic are all preserved verbatim so the rendered HTML is byte-for-byte
 * equivalent to axe-local-scanner's output when fed the same data.
 */

const Handlebars = require('handlebars');
const path       = require('path');
const fs         = require('fs');

const { readJSON, readFile, escapeHTML, formatDate } = require('./utils');
const { groupViolationsByCategory }                  = require('./categoryMapper');
const { getSuggestedFix }                            = require('./suggestionMap');

const TEMPLATES_DIR = path.join(__dirname, 'templates');

// ─── Static Handlebars helpers ─────────────────────────────────────────────
//
// These do not depend on the output directory, so they can be registered once
// at module load time.

Handlebars.registerHelper('escapeHTML', (s) => new Handlebars.SafeString(escapeHTML(s)));

Handlebars.registerHelper('gt', (a, b) => a > b);

Handlebars.registerHelper('impactClass', (impact) =>
  ({ critical: 'critical', serious: 'serious', moderate: 'moderate', minor: 'minor' }[impact] || 'minor')
);
Handlebars.registerHelper('impactLabel', (impact) =>
  impact ? impact.charAt(0).toUpperCase() + impact.slice(1) : 'Unknown'
);

Handlebars.registerHelper('formatTags', (tags) => {
  if (!Array.isArray(tags)) return '';
  return new Handlebars.SafeString(
    tags.map(t => `<span class="tag">${escapeHTML(t)}</span>`).join('')
  );
});

Handlebars.registerHelper('selectorList', (target) => {
  if (!Array.isArray(target)) return '';
  return new Handlebars.SafeString(
    target.map(t => {
      const s = Array.isArray(t) ? t.join(' > ') : String(t);
      return `<code class="selector">${escapeHTML(s)}</code>`;
    }).join('<br>')
  );
});

Handlebars.registerHelper('each_category', function (groups, options) {
  return Object.entries(groups)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([name, violations]) => options.fn({ name, violations }))
    .join('');
});

Handlebars.registerHelper('length', (arr) => Array.isArray(arr) ? arr.length : 0);

Handlebars.registerHelper('slugify', (str) =>
  String(str || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
);

Handlebars.registerHelper('suggestedFix', (ruleId) => getSuggestedFix(ruleId));

Handlebars.registerHelper('nodeIndex', function (options) {
  return (options.data.index || 0) + 1;
});

// ─── Score Gauge SVG (verbatim from source) ────────────────────────────────

Handlebars.registerHelper('scoreGaugeSVG', function (score) {
  const SIZE = 200, cx = 100, cy = 100, R = 82, SW = 8;
  const FF   = "'Google Sans','Segoe UI',system-ui,-apple-system,sans-serif";
  const circ  = +(2 * Math.PI * R).toFixed(2);
  const filled = +(circ * (score / 100)).toFixed(2);
  const offset = +(circ - filled).toFixed(2);

  let cls, label, color;
  if      (score >= 90) { cls = 'score-good';    label = 'Good';              color = '#0cce6b'; }
  else if (score >= 70) { cls = 'score-average'; label = 'Needs Improvement'; color = '#ffa400'; }
  else                  { cls = 'score-poor';    label = 'Poor';              color = '#ff4e42'; }

  let numY, labelSVG;
  if (label === 'Needs Improvement') {
    numY = cy - 28;
    labelSVG = `
      <text x="${cx}" y="${cy + 7}"
            text-anchor="middle" dominant-baseline="middle"
            font-family="${FF}" font-size="13" font-weight="500"
            letter-spacing="0.2" fill="${color}">Needs</text>
      <text x="${cx}" y="${cy + 23}"
            text-anchor="middle" dominant-baseline="middle"
            font-family="${FF}" font-size="13" font-weight="500"
            letter-spacing="0.2" fill="${color}">Improvement</text>`;
  } else {
    numY = cy - 14;
    labelSVG = `
      <text x="${cx}" y="${cy + 16}"
            text-anchor="middle" dominant-baseline="middle"
            font-family="${FF}" font-size="14" font-weight="500"
            letter-spacing="0.3" fill="${color}">${label}</text>`;
  }

  return new Handlebars.SafeString(`
<div class="score-gauge ${cls}"
     role="img"
     aria-label="Accessibility score: ${score} — ${label}">
  <svg class="score-gauge__svg"
       width="${SIZE}" height="${SIZE}"
       viewBox="0 0 ${SIZE} ${SIZE}"
       xmlns="http://www.w3.org/2000/svg">

    <!-- Grey track -->
    <circle cx="${cx}" cy="${cy}" r="${R}"
            fill="none" stroke="#e0e0e0" stroke-width="${SW}"/>

    <!-- Coloured progress arc (animated by accordion.js) -->
    <circle class="score-gauge__arc ${cls}"
            cx="${cx}" cy="${cy}" r="${R}"
            fill="none" stroke-width="${SW}" stroke-linecap="round"
            stroke-dasharray="${circ}" stroke-dashoffset="${circ}"
            data-target-offset="${offset}"
            transform="rotate(-90 ${cx} ${cy})"/>

    <!-- Score number -->
    <text x="${cx}" y="${numY}"
          text-anchor="middle" dominant-baseline="middle"
          font-family="${FF}"
          font-size="46" font-weight="700" letter-spacing="-1"
          fill="${color}">${score}</text>

    <!-- Status label -->
    ${labelSVG}
  </svg>
</div>`);
});

// ─── Output-directory-scoped helpers ───────────────────────────────────────
//
// `encode_image` and `encode_app_image` need to know the runtime output dir
// (where the screenshots and application.png actually live). We register them
// per-call inside generateReport() so the same generator can be reused for
// different output directories without state leak.

function registerOutputDirScopedHelpers(outputDir) {
  Handlebars.registerHelper('encode_image', (screenshotFile) => {
    if (!screenshotFile) return '';
    const p = path.join(outputDir, screenshotFile);
    if (!fs.existsSync(p)) return '';
    return `data:image/png;base64,${fs.readFileSync(p).toString('base64')}`;
  });

  Handlebars.registerHelper('encode_app_image', () => {
    const p = path.join(outputDir, 'application.png');
    if (!fs.existsSync(p)) return '';
    return `data:image/png;base64,${fs.readFileSync(p).toString('base64')}`;
  });
}

// ─── Data helpers (verbatim from source) ───────────────────────────────────

/**
 * Enriches violation nodes with a human-readable `nodeLabel` extracted from HTML.
 */
function enrichViolations(violations) {
  return violations.map(v => ({
    ...v,
    nodes: v.nodes.map(node => {
      let nodeLabel = node.nodeLabel || '';
      if (!nodeLabel && node.html) {
        const m = node.html.match(/>([^<]{1,80})<\/\w/);
        if (m) nodeLabel = m[1].trim();
      }
      return { ...node, nodeLabel };
    }),
  }));
}

function simplify(audits) {
  if (!Array.isArray(audits)) return [];
  return audits.map(a => ({
    id:          a.id          || '',
    description: a.description || a.help || a.id || '',
    nodes:       a.nodes       || [],
  }));
}

// ─── Main entry point ──────────────────────────────────────────────────────

/**
 * Generates the professional HTML report.
 *
 * @param {object} opts
 * @param {string} opts.reportJsonPath    - Path to the (enriched) axe JSON.
 * @param {string} opts.scanInfoJsonPath  - Path to scanInfo JSON (score, counts, url, title, date).
 * @param {string} opts.outputHtmlPath    - Destination path for the generated HTML file.
 * @param {string} opts.outputDir         - Directory containing application.png and screenshots/*.png.
 */
async function generateReport({ reportJsonPath, scanInfoJsonPath, outputHtmlPath, outputDir }) {
  if (!outputDir) {
    // Backward-compat default: derive from HTML output path if caller forgot to pass it.
    outputDir = path.dirname(outputHtmlPath);
  }

  registerOutputDirScopedHelpers(outputDir);

  const raw      = readJSON(reportJsonPath);
  const scanInfo = readJSON(scanInfoJsonPath);

  const violations   = enrichViolations(raw.violations   || []);
  const passes       = simplify(raw.passes       || []);
  const incomplete   = simplify(raw.incomplete   || []);
  const inapplicable = simplify(raw.inapplicable || []);

  const categoryGroups = groupViolationsByCategory(violations);

  const template = Handlebars.compile(readFile(path.join(TEMPLATES_DIR, 'base.html')));

  const html = template({
    targetUrl:         scanInfo.targetUrl,
    pageTitle:         scanInfo.pageTitle,
    scanDate:          formatDate(scanInfo.scanDate),
    score:             scanInfo.score,
    criticalCount:     scanInfo.criticalCount,
    seriousCount:      scanInfo.seriousCount,
    moderateCount:     scanInfo.moderateCount,
    minorCount:        scanInfo.minorCount,
    totalViolations:   violations.length,
    passedCount:       passes.length,
    incompleteCount:   incomplete.length,
    inapplicableCount: inapplicable.length,
    categoryGroups,
    violations,
    passes,
    incomplete,
    inapplicable,
    cssContent: readFile(path.join(TEMPLATES_DIR, 'style.css')),
    jsContent:  readFile(path.join(TEMPLATES_DIR, 'accordion.js')),
  });

  fs.writeFileSync(outputHtmlPath, html, 'utf8');
  console.log(`  ✔ report.html → ${outputHtmlPath}`);
}

module.exports = { generateReport };
