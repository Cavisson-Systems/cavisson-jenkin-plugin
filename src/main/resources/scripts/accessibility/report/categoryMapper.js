/**
 * categoryMapper.js
 * Maps Axe-Core rule IDs to human-readable accessibility categories.
 * Enables grouping of violations in the HTML report similar to Lighthouse.
 */

/**
 * Rule-ID → Category mapping.
 * Covers all common Axe rules. Unknown rules fall back to "Best Practices".
 */
const RULE_CATEGORY_MAP = {
  // ── ARIA ──────────────────────────────────────────────────────────────────
  'aria-allowed-attr': 'ARIA',
  'aria-allowed-role': 'ARIA',
  'aria-command-name': 'ARIA',
  'aria-conditional-attr': 'ARIA',
  'aria-deprecated-role': 'ARIA',
  'aria-dialog-name': 'ARIA',
  'aria-hidden-body': 'ARIA',
  'aria-hidden-focus': 'ARIA',
  'aria-input-field-name': 'ARIA',
  'aria-label': 'ARIA',
  'aria-labelledby': 'ARIA',
  'aria-meter-name': 'ARIA',
  'aria-progressbar-name': 'ARIA',
  'aria-prohibited-attr': 'ARIA',
  'aria-required-attr': 'ARIA',
  'aria-required-children': 'ARIA',
  'aria-required-parent': 'ARIA',
  'aria-roledescription': 'ARIA',
  'aria-roles': 'ARIA',
  'aria-text': 'ARIA',
  'aria-toggle-field-name': 'ARIA',
  'aria-tooltip-name': 'ARIA',
  'aria-treeitem-name': 'ARIA',
  'aria-valid-attr': 'ARIA',
  'aria-valid-attr-value': 'ARIA',

  // ── Contrast ──────────────────────────────────────────────────────────────
  'color-contrast': 'Contrast',
  'color-contrast-enhanced': 'Contrast',

  // ── Forms ─────────────────────────────────────────────────────────────────
  'autocomplete-valid': 'Forms',
  'form-field-multiple-labels': 'Forms',
  'label': 'Forms',
  'label-content-name-mismatch': 'Forms',
  'label-title-only': 'Forms',
  'select-name': 'Forms',
  'input-button-name': 'Forms',
  'input-image-alt': 'Forms',

  // ── Tables ────────────────────────────────────────────────────────────────
  'scope-attr-valid': 'Tables',
  'table-duplicate-name': 'Tables',
  'table-fake-caption': 'Tables',
  'td-headers-attr': 'Tables',
  'th-has-data-cells': 'Tables',

  // ── Lists ─────────────────────────────────────────────────────────────────
  'definition-list': 'Lists',
  'dlitem': 'Lists',
  'list': 'Lists',
  'listitem': 'Lists',

  // ── Images ────────────────────────────────────────────────────────────────
  'image-alt': 'Images',
  'image-redundant-alt': 'Images',
  'role-img-alt': 'Images',
  'svg-img-alt': 'Images',

  // ── Keyboard ──────────────────────────────────────────────────────────────
  'accesskeys': 'Keyboard',
  'focus-order-semantics': 'Keyboard',
  'focusable-content': 'Keyboard',
  'focusable-modal-open': 'Keyboard',
  'focusable-no-name': 'Keyboard',
  'interactive-supports-focus': 'Keyboard',
  'no-access-key': 'Keyboard',
  'scrollable-region-focusable': 'Keyboard',
  'tabindex': 'Keyboard',

  // ── Landmarks ─────────────────────────────────────────────────────────────
  'bypass': 'Landmarks',
  'landmark-banner-is-top-level': 'Landmarks',
  'landmark-complementary-is-top-level': 'Landmarks',
  'landmark-contentinfo-is-top-level': 'Landmarks',
  'landmark-main-is-top-level': 'Landmarks',
  'landmark-no-duplicate-banner': 'Landmarks',
  'landmark-no-duplicate-contentinfo': 'Landmarks',
  'landmark-no-duplicate-main': 'Landmarks',
  'landmark-one-main': 'Landmarks',
  'landmark-unique': 'Landmarks',
  'region': 'Landmarks',

  // ── Structure ─────────────────────────────────────────────────────────────
  'document-title': 'Structure',
  'duplicate-id': 'Structure',
  'duplicate-id-active': 'Structure',
  'duplicate-id-aria': 'Structure',
  'empty-heading': 'Structure',
  'frame-focusable-content': 'Structure',
  'frame-tested': 'Structure',
  'frame-title': 'Structure',
  'frame-title-unique': 'Structure',
  'heading-order': 'Structure',
  'html-has-lang': 'Structure',
  'html-lang-valid': 'Structure',
  'html-xml-lang-mismatch': 'Structure',
  'identical-links-same-purpose': 'Structure',
  'meta-refresh': 'Structure',
  'meta-viewport': 'Structure',
  'page-has-heading-one': 'Structure',
  'p-as-heading': 'Structure',
  'valid-lang': 'Structure',

  // ── Names & Labels ────────────────────────────────────────────────────────
  'button-name': 'Names & Labels',
  'link-name': 'Names & Labels',
  'object-alt': 'Names & Labels',
  'server-side-image-map': 'Names & Labels',

  // ── Best Practices ────────────────────────────────────────────────────────
  'avoid-inline-spacing': 'Best Practices',
  'css-orientation-lock': 'Best Practices',
  'link-in-text-block': 'Best Practices',
  'meta-viewport-large': 'Best Practices',
  'no-autoplay-audio': 'Best Practices',
  'target-size': 'Best Practices',
  'use-landmarks': 'Best Practices',
};

/**
 * Returns the category label for a given Axe rule ID.
 * Falls back to "Best Practices" for unmapped rules.
 * @param {string} ruleId - Axe rule identifier, e.g. "image-alt".
 * @returns {string} Category label.
 */
function getCategory(ruleId) {
  return RULE_CATEGORY_MAP[ruleId] || 'Best Practices';
}

/**
 * Groups an array of Axe violation objects by category.
 * @param {Array} violations - Array of Axe violation result objects.
 * @returns {object} Map of { categoryName: [violation, ...] }
 */
function groupViolationsByCategory(violations) {
  const groups = {};

  for (const violation of violations) {
    const category = getCategory(violation.id);
    if (!groups[category]) {
      groups[category] = [];
    }
    groups[category].push(violation);
  }

  // Sort each category's violations by impact severity
  const impactOrder = { critical: 0, serious: 1, moderate: 2, minor: 3 };
  for (const cat of Object.keys(groups)) {
    groups[cat].sort((a, b) => {
      const ia = impactOrder[a.impact] ?? 99;
      const ib = impactOrder[b.impact] ?? 99;
      return ia - ib;
    });
  }

  return groups;
}

module.exports = { getCategory, groupViolationsByCategory };
