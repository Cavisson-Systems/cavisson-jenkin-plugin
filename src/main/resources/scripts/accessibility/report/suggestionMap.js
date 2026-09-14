/**
 * suggestionMap.js
 * Maps Axe rule IDs to concise, actionable fix suggestions for report display.
 */

const SUGGESTIONS = {
  // Images
  'image-alt': 'Add a meaningful `alt` attribute to the `<img>` element that describes its content. Use `alt=""` for purely decorative images.',
  'image-redundant-alt': 'Remove redundant text from `alt` attributes; screen readers already announce the element as an image.',
  'input-image-alt': 'Provide an `alt` attribute on `<input type="image">` that describes the button action.',
  'role-img-alt': 'Add an `aria-label` or `aria-labelledby` attribute to elements with `role="img"`.',
  'svg-img-alt': 'Add a `<title>` element inside the SVG or an `aria-label` on the container.',

  // Forms
  'label': 'Associate a `<label>` with the form control using a matching `for`/`id` pair, or wrap the control inside the `<label>`.',
  'label-content-name-mismatch': 'Ensure the accessible name (from `aria-label`) contains the visible label text.',
  'label-title-only': 'Replace `title`-only labelling with a visible `<label>` or `aria-label`.',
  'select-name': 'Provide a `<label>`, `aria-label`, or `aria-labelledby` for every `<select>` element.',
  'input-button-name': 'Give every `<input type="button">` or `<input type="submit">` a descriptive `value` attribute.',
  'autocomplete-valid': 'Use a valid `autocomplete` token from the HTML specification on this form field.',
  'form-field-multiple-labels': 'Remove duplicate labels so the form field has exactly one accessible label.',

  // Contrast
  'color-contrast': 'Increase the contrast ratio between foreground text and its background to at least 4.5:1 (3:1 for large text).',
  'color-contrast-enhanced': 'Increase contrast ratio to at least 7:1 (4.5:1 for large text) to meet WCAG AAA.',

  // ARIA
  'aria-allowed-attr': 'Remove ARIA attributes that are not permitted for this element\'s role.',
  'aria-allowed-role': 'Use a role that is allowed for this element type.',
  'aria-command-name': 'Provide an accessible name via `aria-label`, `aria-labelledby`, or inner text.',
  'aria-hidden-focus': 'Remove `aria-hidden="true"` from elements that contain focusable children.',
  'aria-input-field-name': 'Add an accessible name to the input field via `aria-label` or `aria-labelledby`.',
  'aria-required-attr': 'Add all required ARIA attributes for this role.',
  'aria-required-children': 'Ensure this ARIA role contains all required child roles.',
  'aria-required-parent': 'Place this element inside the required parent role.',
  'aria-roles': 'Replace the invalid ARIA role with a valid one from the WAI-ARIA specification.',
  'aria-valid-attr': 'Remove or correct any misspelled or non-existent ARIA attributes.',
  'aria-valid-attr-value': 'Correct the ARIA attribute value to match the allowed values for this attribute.',
  'aria-hidden-body': 'Remove `aria-hidden="true"` from the `<body>` element.',
  'aria-toggle-field-name': 'Give the toggle widget an accessible name using `aria-label` or `aria-labelledby`.',
  'aria-tooltip-name': 'Ensure the tooltip element has an accessible name.',
  'aria-meter-name': 'Add an accessible name to the meter element.',
  'aria-progressbar-name': 'Add an accessible name to the progress bar.',
  'aria-dialog-name': 'Ensure all dialog/alertdialog elements have accessible names.',
  'aria-text': 'Do not use `role="text"` on interactive elements.',

  // Tables
  'scope-attr-valid': 'Use only `col`, `row`, `colgroup`, or `rowgroup` as values for the `scope` attribute.',
  'table-duplicate-name': 'Remove duplication between the table `<caption>` and the `summary` attribute.',
  'table-fake-caption': 'Use a `<caption>` element instead of a data cell as the table caption.',
  'td-headers-attr': 'Ensure all `headers` attribute IDs reference valid `<th>` elements in the same table.',
  'th-has-data-cells': 'Ensure every `<th>` header cell is associated with at least one data cell.',

  // Lists
  'list': 'Ensure `<ul>` and `<ol>` elements only contain `<li>`, `<script>`, or `<template>` as direct children.',
  'listitem': 'Ensure `<li>` elements are only used inside `<ul>`, `<ol>`, or `<menu>` elements.',
  'definition-list': 'Ensure `<dl>` elements only contain properly-ordered `<dt>` and `<dd>` groups.',
  'dlitem': 'Place `<dt>` and `<dd>` elements only inside `<dl>` elements.',

  // Keyboard
  'tabindex': 'Avoid using `tabindex` values greater than 0; use the natural DOM order instead.',
  'accesskeys': 'Remove `accesskey` attributes or ensure they do not conflict across the page.',
  'scrollable-region-focusable': 'Make scrollable regions focusable by adding `tabindex="0"` so keyboard users can scroll.',
  'interactive-supports-focus': 'Ensure interactive elements are focusable via keyboard.',
  'focus-order-semantics': 'Ensure the focus order matches the visual reading order.',

  // Landmarks
  'bypass': 'Add a "Skip to main content" link as the first focusable element on the page.',
  'landmark-one-main': 'Ensure the page has exactly one `<main>` landmark.',
  'landmark-no-duplicate-banner': 'Ensure there is only one `<header>` / `role="banner"` at the top level.',
  'landmark-no-duplicate-contentinfo': 'Ensure there is only one `<footer>` / `role="contentinfo"` at the top level.',
  'region': 'Wrap all page content in a landmark region such as `<main>`, `<nav>`, `<aside>`, or `<section aria-label="...">`.',

  // Structure
  'document-title': 'Add a descriptive `<title>` element inside `<head>` that identifies the page.',
  'duplicate-id': 'Ensure all `id` attribute values are unique within the page.',
  'duplicate-id-active': 'Ensure all `id` values on active, focusable elements are unique.',
  'duplicate-id-aria': 'Ensure all `id` values referenced by ARIA attributes are unique.',
  'heading-order': 'Use headings in sequential order (h1→h2→h3) without skipping levels.',
  'html-has-lang': 'Add a `lang` attribute to the `<html>` element, e.g. `<html lang="en">`.',
  'html-lang-valid': 'Replace the `lang` attribute value with a valid BCP 47 language tag.',
  'meta-viewport': 'Remove `user-scalable=no` or `maximum-scale=1` from the viewport meta tag to allow text resizing.',
  'page-has-heading-one': 'Add a single `<h1>` element that describes the main topic of the page.',
  'valid-lang': 'Replace the `lang` attribute value with a valid BCP 47 language tag.',
  'empty-heading': 'Add descriptive text content inside all heading elements.',
  'frame-title': 'Add a descriptive `title` attribute to every `<iframe>` and `<frame>` element.',
  'p-as-heading': 'Use proper heading elements (`<h1>`–`<h6>`) instead of styled paragraphs.',

  // Names & Labels
  'button-name': 'Add visible text, `aria-label`, or `aria-labelledby` to give the button an accessible name.',
  'link-name': 'Add descriptive text content or an `aria-label` to every link element.',
  'object-alt': 'Provide alternative text for `<object>` elements via the `title` attribute or fallback content.',

  // Best Practices
  'avoid-inline-spacing': 'Do not use inline `style` attributes to override letter-spacing, word-spacing, or line-height.',
  'no-autoplay-audio': 'Do not autoplay audio for longer than 3 seconds, or provide controls to stop/mute it.',
  'meta-refresh': 'Remove `<meta http-equiv="refresh">` or set the delay to 0 for immediate redirects only.',
  'target-size': 'Ensure interactive targets have a minimum size of 24×24 CSS pixels.',
};

/**
 * Returns a suggested fix for a given Axe rule ID.
 * @param {string} ruleId - Axe rule identifier.
 * @returns {string} Human-readable fix suggestion.
 */
function getSuggestedFix(ruleId) {
  return (
    SUGGESTIONS[ruleId] ||
    'Review the WCAG guideline linked in the help URL and update the element to meet the requirement.'
  );
}

module.exports = { getSuggestedFix };
