/**
 * utils.js
 * Shared utility functions for the accessibility report generator.
 *
 * Origin: axe-local-scanner/utils.js (verbatim, no logic changes).
 * Reused by scan.js, screenshotHelper.js, reportGenerator.js and generate-report.js.
 */

const fs   = require('fs');
const path = require('path');

/**
 * Ensures a directory exists; creates it recursively if it does not.
 * @param {string} dirPath - Absolute or relative path to directory.
 */
function ensureDir(dirPath) {
  if (!fs.existsSync(dirPath)) {
    fs.mkdirSync(dirPath, { recursive: true });
  }
}

/**
 * Writes a JavaScript object to disk as formatted JSON.
 * @param {string} filePath - Destination file path.
 * @param {object} data - Data to serialize.
 */
function writeJSON(filePath, data) {
  ensureDir(path.dirname(filePath));
  fs.writeFileSync(filePath, JSON.stringify(data, null, 2), 'utf8');
}

/**
 * Reads and parses a JSON file from disk.
 * @param {string} filePath - Source file path.
 * @returns {object} Parsed JSON object.
 */
function readJSON(filePath) {
  const raw = fs.readFileSync(filePath, 'utf8');
  return JSON.parse(raw);
}

/**
 * Reads a file as a UTF-8 string.
 * @param {string} filePath - Source file path.
 * @returns {string} File contents.
 */
function readFile(filePath) {
  return fs.readFileSync(filePath, 'utf8');
}

/**
 * Escapes special HTML characters in a string to prevent XSS in reports.
 * @param {string} str - Raw string.
 * @returns {string} HTML-safe string.
 */
function escapeHTML(str) {
  if (typeof str !== 'string') return String(str ?? '');
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * Formats an ISO date string into a human-readable form.
 * @param {string} isoString - ISO 8601 date string.
 * @returns {string} e.g. "June 25, 2025 at 14:32:01"
 */
function formatDate(isoString) {
  const date = new Date(isoString);
  return date.toLocaleString('en-US', {
    year:   'numeric',
    month:  'long',
    day:    'numeric',
    hour:   '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

/**
 * Calculates an accessibility score from violation counts.
 * Starts at 100 and subtracts per-severity penalty. Floor is 0.
 * @param {object} counts - { critical, serious, moderate, minor }
 * @returns {number} Score between 0 and 100.
 */
function calculateScore({ critical = 0, serious = 0, moderate = 0, minor = 0 }) {
  const penalty = critical * 10 + serious * 5 + moderate * 2 + minor * 1;
  return Math.max(0, 100 - penalty);
}

module.exports = {
  ensureDir,
  writeJSON,
  readJSON,
  readFile,
  escapeHTML,
  formatDate,
  calculateScore,
};
