/**
 * screenshotHelper.js
 * High-quality, contextual element screenshots for every failing axe node.
 *
 * Origin: axe-local-scanner/screenshotHelper.js.
 * Only change vs. source: the output-screenshots directory is now an explicit
 * parameter (`screenshotsDir`) instead of a hardcoded `__dirname/output/screenshots`
 * constant. This lets the Cavisson extension write into REPORTS_DIR
 * (Azure `BUILD_ARTIFACTSTAGINGDIRECTORY`) without polluting the extension folder.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * Key implementation notes (preserved from source)
 * ═══════════════════════════════════════════════════════════════════════
 * 1. Always use page.screenshot({ clip }) — never element.screenshot().
 * 2. Adaptive padding — 200px default, 100px min, 300px max.
 * 3. Element is scrolled to viewport CENTER before capture.
 * 4. Also scrolls scrollable parents.
 * 5. Strong red highlight: outline + box-shadow + light bg overlay.
 * 6. Waits 2–3 rAF cycles + 100–300ms after highlight before shot.
 * 7. Clip is clamped to page dimensions, never negative.
 * ═══════════════════════════════════════════════════════════════════════
 */

'use strict';

const path = require('path');
const fs   = require('fs');
const { ensureDir } = require('./utils');

// ── Adaptive padding constants ─────────────────────────────────────────────
const PAD_DEFAULT = 200; // px — default context padding on all sides
const PAD_MIN     = 100; // px — never less than this
const PAD_MAX     = 300; // px — never more than this

// ── Helpers ────────────────────────────────────────────────────────────────

function slugify(str) {
  return String(str || '').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '');
}

/**
 * Resolves a Playwright ElementHandle from an axe target array.
 * Supports simple selectors, compound selectors and shadow-DOM nested arrays.
 */
async function resolveElement(page, target) {
  if (!target || target.length === 0) return null;
  const primary = target[0];

  // Shadow DOM nested array
  if (Array.isArray(primary)) {
    try {
      const el = await page.$(`css=${primary.map(s => `css=${s}`).join(' >> ')}`);
      if (el) return el;
    } catch { /* ignore */ }
    try {
      const el = await page.$(primary[primary.length - 1]);
      if (el) return el;
    } catch { /* ignore */ }
    return null;
  }

  const selector = String(primary);

  // Strategy 1 — direct querySelector
  try {
    const el = await page.$(selector);
    if (el) return el;
  } catch { /* malformed */ }

  // Strategy 2 — evaluateHandle
  try {
    const handle = await page.evaluateHandle(sel => document.querySelector(sel), selector);
    const el = handle.asElement();
    if (el) return el;
  } catch { /* ignore */ }

  return null;
}

/**
 * Generates an XPath for an element via a clean recursive in-page function.
 */
async function getXPath(page, elementHandle) {
  try {
    return await page.evaluate((el) => {
      function buildXPath(node) {
        if (!node || node.nodeType !== Node.ELEMENT_NODE) return '';
        if (node === document.documentElement) return '/html';
        if (node === document.body) return '/html/body';
        const tag    = node.tagName.toLowerCase();
        const parent = node.parentNode;
        if (!parent) return '/' + tag;
        let idx = 1, total = 0, sib = parent.firstChild;
        while (sib) {
          if (sib.nodeType === 1 && sib.tagName.toLowerCase() === tag) {
            if (sib === node) idx = total + 1;
            total++;
          }
          sib = sib.nextSibling;
        }
        return buildXPath(parent) + '/' + (total > 1 ? `${tag}[${idx}]` : tag);
      }
      return buildXPath(el);
    }, elementHandle);
  } catch {
    return '';
  }
}

/**
 * Returns a reason string if the element cannot be captured, null if it can.
 */
async function getSkipReason(page, elementHandle) {
  try {
    return await page.evaluate((el) => {
      if (!el.isConnected)                       return 'detached from DOM';
      const style = window.getComputedStyle(el);
      if (style.display === 'none')              return 'display: none';
      if (style.visibility === 'hidden')         return 'visibility: hidden';
      if (parseFloat(style.opacity) === 0)       return 'opacity: 0';
      const rect = el.getBoundingClientRect();
      if (rect.width === 0 && rect.height === 0) return 'zero dimensions';
      return null;
    }, elementHandle);
  } catch {
    return 'detached from DOM';
  }
}

/**
 * Polls up to 3 seconds waiting for the element to become stable.
 */
async function waitForElementStability(page, elementHandle) {
  const deadline = Date.now() + 3000;
  while (Date.now() < deadline) {
    const reason = await getSkipReason(page, elementHandle);
    if (!reason) return true;
    await page.waitForTimeout(100);
  }
  return false;
}

/**
 * Scrolls element to the center of the viewport.
 * Also scrolls any intermediate scrollable containers.
 */
async function scrollElementToCenter(page, elementHandle) {
  await page.evaluate((el) => {
    el.scrollIntoView({ behavior: 'instant', block: 'center', inline: 'center' });
  }, elementHandle).catch(() => {});

  await page.evaluate((el) => {
    let parent = el.parentElement;
    while (parent && parent !== document.body) {
      const style    = window.getComputedStyle(parent);
      const overflow = style.overflow + style.overflowY + style.overflowX;
      if (/auto|scroll/.test(overflow)) {
        const rect       = el.getBoundingClientRect();
        const parentRect = parent.getBoundingClientRect();
        const scrollTop  = parent.scrollTop + rect.top - parentRect.top
                         - (parentRect.height - rect.height) / 2;
        parent.scrollTo({ top: scrollTop, behavior: 'instant' });
      }
      parent = parent.parentElement;
    }
  }, elementHandle).catch(() => {});

  await page.waitForTimeout(200);
}

/**
 * Applies a strong, visible red highlight. Returns the original styles.
 */
async function applyHighlight(page, elementHandle) {
  return await page.evaluate((el) => {
    const orig = {
      outline:         el.style.outline,
      outlineOffset:   el.style.outlineOffset,
      boxShadow:       el.style.boxShadow,
      backgroundColor: el.style.backgroundColor,
      zIndex:          el.style.zIndex,
      position:        el.style.position,
    };
    el.setAttribute('data-axe-highlight', 'true');
    el.style.outline         = '3px solid #ff4e42';
    el.style.outlineOffset   = '2px';
    el.style.boxShadow       = '0 0 0 3px rgba(255, 78, 66, 0.35), 0 0 12px 4px rgba(255, 78, 66, 0.25)';
    el.style.backgroundColor = 'rgba(255, 78, 66, 0.08)';
    el.style.zIndex          = '2147483647';
    if (window.getComputedStyle(el).position === 'static') {
      el.style.position = 'relative';
    }
    return orig;
  }, elementHandle).catch(() => null);
}

/**
 * Removes the highlight and restores original styles.
 */
async function removeHighlight(page, elementHandle, origStyles) {
  await page.evaluate((el, orig) => {
    el.removeAttribute('data-axe-highlight');
    if (!orig) {
      el.style.removeProperty('outline');
      el.style.removeProperty('outline-offset');
      el.style.removeProperty('box-shadow');
      el.style.removeProperty('background-color');
      el.style.removeProperty('z-index');
      el.style.removeProperty('position');
      return;
    }
    el.style.outline         = orig.outline;
    el.style.outlineOffset   = orig.outlineOffset;
    el.style.boxShadow       = orig.boxShadow;
    el.style.backgroundColor = orig.backgroundColor;
    el.style.zIndex          = orig.zIndex;
    if (orig.position !== undefined) el.style.position = orig.position;
  }, elementHandle, origStyles).catch(() => {});
}

/**
 * Calculates a smart clip region around the element's bounding box.
 */
function calculateSmartClip(bb, vp, pageHeight) {
  const elementArea = bb.width * bb.height;
  let pad;
  if (elementArea < 500)       pad = PAD_MAX;
  else if (elementArea < 5000) pad = PAD_DEFAULT;
  else                         pad = PAD_MIN;

  let x = bb.x - pad;
  let y = bb.y - pad;
  let w = bb.width  + pad * 2;
  let h = bb.height + pad * 2;

  const maxWidth  = vp.width;
  const maxHeight = pageHeight || vp.height;

  x = Math.max(0, x);
  y = Math.max(0, y);
  w = Math.min(maxWidth  - x, w);
  h = Math.min(maxHeight - y, h);

  w = Math.max(w, bb.width  + PAD_MIN * 2);
  h = Math.max(h, bb.height + PAD_MIN * 2);

  w = Math.min(maxWidth  - x, w);
  h = Math.min(maxHeight - y, h);

  w = Math.max(1, w);
  h = Math.max(1, h);

  return { x: Math.round(x), y: Math.round(y), width: Math.round(w), height: Math.round(h) };
}

/**
 * Main function — captures screenshots for every failing node.
 *
 * @param {import('playwright').Page} page
 * @param {Array}  violations       - axe violations array
 * @param {string} screenshotsDir   - absolute path where screenshots are saved
 * @returns {Promise<Array>} enriched violations (with screenshotFile, screenshotReason, xpath on each node)
 */
async function captureElementScreenshots(page, violations, screenshotsDir) {
  ensureDir(screenshotsDir);

  const enrichedViolations = [];

  for (const violation of violations) {
    const ruleSlug      = slugify(violation.id);
    const enrichedNodes = [];
    let nodeIdx         = 1;

    for (const node of violation.nodes) {
      let screenshotFile   = null;
      let screenshotReason = null;
      let xpath            = '';
      let origStyles       = null;
      let elementHandle    = null;

      try {
        // Step 1: Resolve element
        elementHandle = await resolveElement(page, node.target).catch(() => null);

        if (!elementHandle) {
          screenshotReason = 'Element not found in DOM — may have been re-rendered or removed after axe scan';
        } else {

          // Step 2: Wait for element stability
          const isStable = await waitForElementStability(page, elementHandle);
          if (!isStable) {
            screenshotReason = 'Element not stable after 3s — possibly animating or conditionally hidden';
          } else {

            // Step 3: XPath before DOM mutation
            xpath = await getXPath(page, elementHandle);

            // Step 4: Scroll to viewport centre
            await scrollElementToCenter(page, elementHandle);

            // Step 5: Wait for rendering after scroll
            await page.evaluate(() =>
              new Promise(res =>
                requestAnimationFrame(() =>
                  requestAnimationFrame(() =>
                    requestAnimationFrame(res)
                  )
                )
              )
            ).catch(() => {});
            await page.waitForTimeout(300);

            // Step 6: Apply strong red highlight
            origStyles = await applyHighlight(page, elementHandle);

            // Step 7: Wait for highlight to paint
            await page.evaluate(() =>
              new Promise(res => requestAnimationFrame(() => requestAnimationFrame(res)))
            ).catch(() => {});
            await page.waitForTimeout(100);

            // Step 8: Bounding box
            const bb = await elementHandle.boundingBox();

            if (!bb || bb.width === 0 || bb.height === 0) {
              screenshotReason = 'Element has zero dimensions — hidden via CSS or not rendered';
            } else {

              // Step 9: Smart clip
              const vp = page.viewportSize() || { width: 1366, height: 768 };
              const pageHeight = await page.evaluate(() => document.documentElement.scrollHeight)
                .catch(() => vp.height);
              const clip = calculateSmartClip(bb, vp, pageHeight);

              // Step 10: Capture via page.screenshot({ clip })
              const fileName = `${ruleSlug}_${nodeIdx}.png`;
              const filePath = path.join(screenshotsDir, fileName);

              await page.screenshot({ path: filePath, clip });

              if (fs.existsSync(filePath)) {
                // Store relative path so the report generator can resolve via outputDir
                screenshotFile = path.join('screenshots', fileName);
              } else {
                screenshotReason = 'Screenshot file was not written to disk';
              }
            }
          }
        }

      } catch (err) {
        screenshotReason = `Screenshot error: ${err.message}`;

      } finally {
        // Step 11: Always remove highlight
        if (elementHandle) {
          await removeHighlight(page, elementHandle, origStyles);
        }
      }

      enrichedNodes.push({ ...node, screenshotFile, screenshotReason, xpath });
      nodeIdx++;
    }

    enrichedViolations.push({ ...violation, nodes: enrichedNodes });
  }

  return enrichedViolations;
}

module.exports = { captureElementScreenshots };
