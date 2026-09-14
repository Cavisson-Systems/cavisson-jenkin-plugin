(function () {
  'use strict';

  var activeSeverity = 'all';

  /* ── Violation accordions ─── */
  function openPanel(header) {
    var panel = header.nextElementSibling;
    header.classList.add('open');
    header.setAttribute('aria-expanded', 'true');
    panel.classList.add('open');
    panel.style.maxHeight = panel.scrollHeight + 'px';
  }
  function closePanel(header) {
    var panel = header.nextElementSibling;
    header.classList.remove('open');
    header.setAttribute('aria-expanded', 'false');
    panel.classList.remove('open');
    panel.style.maxHeight = null;
  }
  function togglePanel(header) {
    if (header.classList.contains('open')) closePanel(header); else openPanel(header);
  }

  /* ── Severity filter ─── */
  function applyFilter() {
    document.querySelectorAll('.rule-card').forEach(function (card) {
      var impact = (card.dataset.impact || '').toLowerCase();
      card.style.display = (activeSeverity === 'all' || impact === activeSeverity) ? '' : 'none';
    });
    document.querySelectorAll('.category-section').forEach(function (section) {
      section.style.display = section.querySelector('.rule-card:not([style*="display: none"])') ? '' : 'none';
    });
  }

  /* ── Audit section toggles (Passed / Review / NA) ─── */
  function initAuditToggles() {
    document.querySelectorAll('.audit-section__toggle').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var listId = btn.dataset.list;
        var list   = document.getElementById(listId);
        if (!list) return;
        var open = btn.getAttribute('aria-expanded') === 'true';
        btn.setAttribute('aria-expanded', open ? 'false' : 'true');
        if (open) {
          list.setAttribute('hidden', '');
          btn.firstChild.textContent = 'Show ';
        } else {
          list.removeAttribute('hidden');
          btn.firstChild.textContent = 'Hide ';
        }
      });
    });
  }

  /* ── Score gauge animation ─── */
  function animateGauge() {
    var arc = document.querySelector('.score-gauge__arc');
    if (!arc) return;
    var target = parseFloat(arc.dataset.targetOffset || '0');
    var circ   = parseFloat(arc.getAttribute('stroke-dasharray') || '0');
    arc.style.strokeDashoffset = String(circ);
    requestAnimationFrame(function () {
      requestAnimationFrame(function () {
        arc.style.strokeDashoffset = String(target);
      });
    });
  }

  /* ── DOM Ready ─── */
  document.addEventListener('DOMContentLoaded', function () {

    /* Violation accordions */
    document.querySelectorAll('.accordion-header').forEach(function (h) {
      h.addEventListener('click',   function () { togglePanel(h); });
      h.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); togglePanel(h); }
      });
    });

    /* Expand / Collapse All */
    var btnEx = document.getElementById('btn-expand-all');
    if (btnEx) btnEx.addEventListener('click', function () {
      document.querySelectorAll('.accordion-header').forEach(openPanel);
    });
    var btnCol = document.getElementById('btn-collapse-all');
    if (btnCol) btnCol.addEventListener('click', function () {
      document.querySelectorAll('.accordion-header').forEach(closePanel);
    });

    /* Severity chips */
    document.querySelectorAll('.filter-chip').forEach(function (chip) {
      chip.addEventListener('click', function () {
        document.querySelectorAll('.filter-chip').forEach(function (c) {
          c.classList.remove('active'); c.setAttribute('aria-pressed', 'false');
        });
        chip.classList.add('active');
        chip.setAttribute('aria-pressed', 'true');
        activeSeverity = chip.dataset.severity || 'all';
        applyFilter();
      });
    });

    /* Smooth scroll */
    document.querySelectorAll('a[href^="#"]').forEach(function (a) {
      a.addEventListener('click', function (e) {
        var t = document.querySelector(this.getAttribute('href'));
        if (t) { e.preventDefault(); t.scrollIntoView({ behavior: 'smooth', block: 'start' }); }
      });
    });

    initAuditToggles();
    animateGauge();
  });
})();