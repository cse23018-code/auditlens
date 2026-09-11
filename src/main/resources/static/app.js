/* =====================================================================
 * AuditLens dashboard client.
 *
 * Loads the payload from the live Spring Boot API. When the page is served
 * statically (the published demo build) the API is absent, so it falls back
 * to snapshot.json - a file exported by the same engine, so both paths show
 * identical figures.
 * ===================================================================== */

const SVG_NS = 'http://www.w3.org/2000/svg';

const COLOR = {
  observed: '#3987e5',
  expected: '#d95926',
  spend: '#3987e5',
  grid: '#2c2c2a',
  axis: '#383835',
  muted: '#898781',
  ink2: '#c3c2b7',
  surface: '#16161a',
};

const SEVERITY = {
  CRITICAL: { label: 'Critical', color: '#d03b3b', icon: '◆', cls: 'critical' },
  HIGH:     { label: 'High',     color: '#ec835a', icon: '▲', cls: 'high' },
  MEDIUM:   { label: 'Medium',   color: '#fab219', icon: '●', cls: 'medium' },
  LOW:      { label: 'Low',      color: '#0ca30c', icon: '■', cls: 'low' },
};

let DATA = null;
let allFindings = [];

/* ----------------------------- formatting ----------------------------- */

const num = (v) => Number(v ?? 0);

function compactMoney(value) {
  const v = num(value);
  if (v >= 1e7) return '₹' + (v / 1e7).toFixed(2) + ' Cr';
  if (v >= 1e5) return '₹' + (v / 1e5).toFixed(2) + ' L';
  if (v >= 1e3) return '₹' + (v / 1e3).toFixed(1) + 'K';
  return '₹' + v.toFixed(0);
}

function fullMoney(value) {
  return '₹' + num(value).toLocaleString('en-IN', { maximumFractionDigits: 0 });
}

const count = (v) => num(v).toLocaleString('en-IN');

function el(tag, attrs = {}, text) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  if (text !== undefined) node.textContent = text;
  return node;
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/* ------------------------------ tooltip ------------------------------ */

const tooltip = document.getElementById('tooltip');

function showTooltip(evt, html) {
  tooltip.innerHTML = html;
  tooltip.hidden = false;
  const pad = 14;
  const rect = tooltip.getBoundingClientRect();
  let x = evt.clientX + pad;
  let y = evt.clientY + pad;
  if (x + rect.width > window.innerWidth - 8) x = evt.clientX - rect.width - pad;
  if (y + rect.height > window.innerHeight - 8) y = evt.clientY - rect.height - pad;
  tooltip.style.left = x + 'px';
  tooltip.style.top = y + 'px';
}

function hideTooltip() { tooltip.hidden = true; }

/* ------------------------------ loading ------------------------------ */

async function loadData() {
  try {
    const res = await fetch('api/dashboard', { headers: { Accept: 'application/json' } });
    if (res.ok) return await res.json();
  } catch (_) { /* static host - fall through */ }
  const res = await fetch('snapshot.json');
  if (!res.ok) throw new Error('Unable to load audit data');
  return res.json();
}

/* -------------------------------- KPIs -------------------------------- */

function renderHeadline() {
  const h = DATA.headline || {};
  document.getElementById('kpi-txns').textContent = count(h.txnCount);
  document.getElementById('kpi-vendors').textContent = 'across ' + count(h.vendorCount) + ' vendors';
  document.getElementById('kpi-spend').textContent = compactMoney(h.totalSpend);
  document.getElementById('kpi-findings').textContent = count(h.findingCount);
  document.getElementById('kpi-controls').textContent =
    'from ' + (DATA.catalogue || []).length + ' controls';
  document.getElementById('kpi-critical').textContent = count(h.criticalCount);
  document.getElementById('kpi-high').textContent = count(h.highCount) + ' high severity';
  document.getElementById('kpi-exposure').textContent = compactMoney(h.totalExposure);
  document.getElementById('kpi-flagged').textContent = count(h.flaggedVendors);

  document.getElementById('engine-badge').textContent = 'engine v' + (DATA.engineVersion || '-');

  const latest = (DATA.runs || [])[0];
  const meta = latest
    ? count(latest.txnsScanned) + ' transactions scanned in ' + count(latest.durationMs) + ' ms'
    : count(h.txnCount) + ' transactions scanned';
  document.getElementById('scan-meta').textContent = meta;

  const stamp = DATA.generatedAt ? DATA.generatedAt.replace('T', ' ').slice(0, 16) : '';
  document.getElementById('foot-generated').textContent = stamp ? 'snapshot ' + stamp : '';
}

/* --------------------------- severity bars --------------------------- */

function renderSeverity() {
  const rows = DATA.findingsBySeverity || [];
  const max = Math.max(1, ...rows.map((r) => num(r.findingCount)));
  const host = document.getElementById('severity-bars');
  host.innerHTML = '';

  for (const key of ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']) {
    const meta = SEVERITY[key];
    const row = rows.find((r) => r.severity === key);
    const n = row ? num(row.findingCount) : 0;
    const exposure = row ? num(row.exposure) : 0;

    const wrap = document.createElement('div');
    wrap.className = 'sev-row';
    wrap.innerHTML =
      '<div class="sev-top">' +
        '<span class="sev-name"><span class="sev-icon" style="color:' + meta.color + '">' +
          meta.icon + '</span>' + meta.label + '</span>' +
        '<span class="sev-count"><b>' + count(n) + '</b> findings · ' +
          compactMoney(exposure) + ' exposure</span>' +
      '</div>' +
      '<div class="sev-track"><div class="sev-fill" style="width:' +
        ((n / max) * 100).toFixed(1) + '%;background:' + meta.color + '"></div></div>';
    host.appendChild(wrap);
  }

  const chips = document.getElementById('domain-chips');
  chips.innerHTML = '';
  for (const d of DATA.findingsByDomain || []) {
    const chip = document.createElement('span');
    chip.className = 'chip';
    chip.innerHTML = esc(d.controlDomain) + ' <b>' + count(d.findingCount) + '</b>';
    chips.appendChild(chip);
  }
}

/* --------------------------- Benford chart --------------------------- */

function renderBenford() {
  const data = DATA.benford || [];
  const svg = document.getElementById('benford-chart');
  svg.innerHTML = '';

  const W = 640, H = 260;
  const m = { top: 16, right: 12, bottom: 34, left: 42 };
  const iw = W - m.left - m.right;
  const ih = H - m.top - m.bottom;

  svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
  svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
  svg.style.minWidth = '420px';

  const maxPct = Math.max(...data.map((d) => Math.max(num(d.observedPct), num(d.expectedPct))), 10);
  const yMax = Math.ceil(maxPct / 5) * 5;
  const y = (v) => m.top + ih - (v / yMax) * ih;

  // gridlines + y axis
  for (let t = 0; t <= yMax; t += 5) {
    svg.appendChild(el('line', {
      x1: m.left, x2: m.left + iw, y1: y(t), y2: y(t),
      stroke: COLOR.grid, 'stroke-width': 1,
    }));
    svg.appendChild(el('text', {
      x: m.left - 8, y: y(t) + 4, 'text-anchor': 'end',
      fill: COLOR.muted, 'font-size': 10.5,
    }, t + '%'));
  }
  svg.appendChild(el('line', {
    x1: m.left, x2: m.left + iw, y1: m.top + ih, y2: m.top + ih,
    stroke: COLOR.axis, 'stroke-width': 1,
  }));

  const band = iw / data.length;
  const groupPad = band * 0.22;
  const barW = (band - groupPad * 2 - 2) / 2;   // 2px surface gap between the pair

  data.forEach((d, i) => {
    const x0 = m.left + i * band + groupPad;
    const series = [
      { key: 'observedPct', color: COLOR.observed, label: 'Observed' },
      { key: 'expectedPct', color: COLOR.expected, label: 'Benford expected' },
    ];

    series.forEach((s, si) => {
      const v = num(d[s.key]);
      const h = Math.max(1, (v / yMax) * ih);
      const x = x0 + si * (barW + 2);
      const rect = el('rect', {
        x, y: y(v), width: barW, height: h,
        fill: s.color, rx: 3, ry: 3,
      });
      rect.style.cursor = 'pointer';
      rect.addEventListener('mousemove', (e) => showTooltip(e,
        '<div class="tt-title">Leading digit ' + d.digit + '</div>' +
        '<div class="tt-row"><span style="color:' + COLOR.observed + '">■</span> Observed <b>' +
          num(d.observedPct).toFixed(2) + '%</b></div>' +
        '<div class="tt-row"><span style="color:' + COLOR.expected + '">■</span> Expected <b>' +
          num(d.expectedPct).toFixed(2) + '%</b></div>' +
        '<div class="tt-row">Invoices <b>' + count(d.observedCount) + '</b></div>'));
      rect.addEventListener('mouseleave', hideTooltip);
      svg.appendChild(rect);
    });

    svg.appendChild(el('text', {
      x: m.left + i * band + band / 2, y: H - 12,
      'text-anchor': 'middle', fill: COLOR.muted, 'font-size': 11,
    }, d.digit));
  });

  svg.appendChild(el('text', {
    x: m.left + iw / 2, y: H - 1, 'text-anchor': 'middle',
    fill: COLOR.muted, 'font-size': 10,
  }, 'first significant digit of invoice value'));

  const legend = document.getElementById('benford-legend');
  legend.innerHTML =
    '<span class="legend-item"><span class="legend-swatch" style="background:' +
      COLOR.observed + '"></span>Observed</span>' +
    '<span class="legend-item"><span class="legend-swatch" style="background:' +
      COLOR.expected + '"></span>Benford expected</span>';
}

/* ---------------------------- spend trend ---------------------------- */

function renderSpend() {
  const data = DATA.monthlySpend || [];
  const svg = document.getElementById('spend-chart');
  svg.innerHTML = '';
  if (!data.length) return;

  const W = 1080, H = 260;
  const m = { top: 16, right: 16, bottom: 36, left: 62 };
  const iw = W - m.left - m.right;
  const ih = H - m.top - m.bottom;

  svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
  svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
  svg.style.minWidth = '640px';

  const maxSpend = Math.max(...data.map((d) => num(d.spend)));
  const yMax = maxSpend * 1.12;
  const x = (i) => m.left + (data.length === 1 ? iw / 2 : (i / (data.length - 1)) * iw);
  const y = (v) => m.top + ih - (v / yMax) * ih;

  for (let t = 0; t <= 4; t++) {
    const v = (yMax / 4) * t;
    svg.appendChild(el('line', {
      x1: m.left, x2: m.left + iw, y1: y(v), y2: y(v),
      stroke: COLOR.grid, 'stroke-width': 1,
    }));
    svg.appendChild(el('text', {
      x: m.left - 10, y: y(v) + 4, 'text-anchor': 'end',
      fill: COLOR.muted, 'font-size': 10.5,
    }, compactMoney(v)));
  }

  const linePts = data.map((d, i) => `${x(i)},${y(num(d.spend))}`).join(' ');
  const areaPts = `${m.left},${m.top + ih} ${linePts} ${m.left + iw},${m.top + ih}`;

  const grad = el('linearGradient', { id: 'spendFill', x1: 0, y1: 0, x2: 0, y2: 1 });
  grad.appendChild(el('stop', { offset: '0%', 'stop-color': COLOR.spend, 'stop-opacity': 0.34 }));
  grad.appendChild(el('stop', { offset: '100%', 'stop-color': COLOR.spend, 'stop-opacity': 0.02 }));
  const defs = el('defs');
  defs.appendChild(grad);
  svg.appendChild(defs);

  svg.appendChild(el('polygon', { points: areaPts, fill: 'url(#spendFill)' }));
  svg.appendChild(el('polyline', {
    points: linePts, fill: 'none', stroke: COLOR.spend,
    'stroke-width': 2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round',
  }));

  const crosshair = el('line', {
    y1: m.top, y2: m.top + ih, stroke: COLOR.axis, 'stroke-width': 1, opacity: 0,
  });
  svg.appendChild(crosshair);
  const marker = el('circle', {
    r: 5, fill: COLOR.spend, stroke: COLOR.surface, 'stroke-width': 2, opacity: 0,
  });
  svg.appendChild(marker);

  data.forEach((d, i) => {
    if (i % Math.ceil(data.length / 12) !== 0) return;
    svg.appendChild(el('text', {
      x: x(i), y: H - 14, 'text-anchor': 'middle',
      fill: COLOR.muted, 'font-size': 10.5,
    }, d.period));
  });

  const hit = el('rect', {
    x: m.left, y: m.top, width: iw, height: ih, fill: 'transparent',
  });
  hit.style.cursor = 'crosshair';
  hit.addEventListener('mousemove', (e) => {
    const box = svg.getBoundingClientRect();
    const px = ((e.clientX - box.left) / box.width) * W;
    let idx = Math.round(((px - m.left) / iw) * (data.length - 1));
    idx = Math.max(0, Math.min(data.length - 1, idx));
    const d = data[idx];
    crosshair.setAttribute('x1', x(idx));
    crosshair.setAttribute('x2', x(idx));
    crosshair.setAttribute('opacity', 1);
    marker.setAttribute('cx', x(idx));
    marker.setAttribute('cy', y(num(d.spend)));
    marker.setAttribute('opacity', 1);
    showTooltip(e,
      '<div class="tt-title">' + esc(d.period) + '</div>' +
      '<div class="tt-row">Spend <b>' + fullMoney(d.spend) + '</b></div>' +
      '<div class="tt-row">Invoices <b>' + count(d.txnCount) + '</b></div>' +
      '<div class="tt-row">Cumulative <b>' + compactMoney(d.cumulativeSpend) + '</b></div>');
  });
  hit.addEventListener('mouseleave', () => {
    crosshair.setAttribute('opacity', 0);
    marker.setAttribute('opacity', 0);
    hideTooltip();
  });
  svg.appendChild(hit);
}

/* -------------------------- control catalogue -------------------------- */

function renderRules() {
  const byRule = new Map();
  for (const r of DATA.findingsByRule || []) byRule.set(r.ruleCode, r);

  const tbody = document.querySelector('#rules-table tbody');
  tbody.innerHTML = '';

  const maxCount = Math.max(1, ...[...byRule.values()].map((r) => num(r.findingCount)));

  for (const rule of DATA.catalogue || []) {
    const stat = byRule.get(rule.code);
    const n = stat ? num(stat.findingCount) : 0;
    const sev = SEVERITY[rule.severity] || SEVERITY.LOW;
    const tr = document.createElement('tr');
    tr.innerHTML =
      '<td class="mono-ref">' + esc(rule.code) + '</td>' +
      '<td><span class="cell-strong">' + esc(rule.title) + '</span>' +
        '<div class="cell-dim">' + esc(rule.description) + '</div></td>' +
      '<td class="cell-dim">' + esc(rule.domain) + '</td>' +
      '<td><span class="pill pill-' + sev.cls + '">' + sev.icon + ' ' + sev.label + '</span></td>' +
      '<td class="num"><div class="bar-cell">' +
        '<div class="bar-track"><div class="bar-fill" style="width:' +
          ((n / maxCount) * 100).toFixed(1) + '%;background:' + sev.color + '"></div></div>' +
        '<span class="bar-value">' + count(n) + '</span></div></td>' +
      '<td class="num">' + (stat ? compactMoney(stat.exposure) : '—') + '</td>';
    tbody.appendChild(tr);
  }
}

/* ---------------------------- vendor risk ---------------------------- */

function renderVendors() {
  const tbody = document.querySelector('#vendor-table tbody');
  tbody.innerHTML = '';

  for (const v of DATA.vendorRisk || []) {
    const score = num(v.riskScore);
    const color = score >= 75 ? SEVERITY.CRITICAL.color
                : score >= 50 ? SEVERITY.HIGH.color
                : score >= 25 ? SEVERITY.MEDIUM.color
                : SEVERITY.LOW.color;
    const tr = document.createElement('tr');
    tr.innerHTML =
      '<td><span class="cell-strong">' + esc(v.vendorName) + '</span>' +
        '<div class="mono-ref">' + esc(v.vendorCode) + ' · ' + esc(v.country) + '</div></td>' +
      '<td class="cell-dim">' + esc(v.category) + '</td>' +
      '<td class="num">' + compactMoney(v.totalSpend) + '</td>' +
      '<td class="num">' + count(v.txnCount) + '</td>' +
      '<td class="num">' + count(v.findingCount) +
        '<div class="cell-dim">' + count(v.criticalCount) + ' critical</div></td>' +
      '<td class="num">' + compactMoney(v.exposure) + '</td>' +
      '<td><div class="bar-cell">' +
        '<div class="bar-track"><div class="bar-fill" style="width:' + score +
          '%;background:' + color + '"></div></div>' +
        '<span class="bar-value">' + score + '</span></div></td>';
    tbody.appendChild(tr);
  }
}

/* ------------------------- exception register ------------------------- */

function renderFindings() {
  const sev = document.getElementById('filter-severity').value;
  const rule = document.getElementById('filter-rule').value;
  const term = document.getElementById('filter-text').value.trim().toLowerCase();

  const rows = allFindings.filter((f) => {
    if (sev && f.severity !== sev) return false;
    if (rule && f.ruleCode !== rule) return false;
    if (term) {
      const hay = [f.vendorName, f.txnRef, f.summary, f.ruleTitle, f.invoiceNo]
        .join(' ').toLowerCase();
      if (!hay.includes(term)) return false;
    }
    return true;
  });

  document.getElementById('result-count').textContent =
    'Showing ' + count(rows.length) + ' of ' + count(allFindings.length) + ' exceptions';

  const tbody = document.querySelector('#findings-table tbody');
  tbody.innerHTML = '';

  if (!rows.length) {
    const tr = document.createElement('tr');
    tr.innerHTML = '<td colspan="6"><div class="empty-state">No exceptions match these filters.</div></td>';
    tbody.appendChild(tr);
    return;
  }

  for (const f of rows.slice(0, 150)) {
    const meta = SEVERITY[f.severity] || SEVERITY.LOW;
    const tr = document.createElement('tr');
    tr.innerHTML =
      '<td><span class="pill pill-' + meta.cls + '">' + meta.icon + ' ' + meta.label + '</span></td>' +
      '<td><span class="mono-ref">' + esc(f.ruleCode) + '</span>' +
        '<div class="cell-dim">' + esc(f.ruleTitle) + '</div></td>' +
      '<td><span class="cell-strong">' + esc(f.vendorName || '—') + '</span>' +
        (f.txnRef ? '<div class="mono-ref">' + esc(f.txnRef) + '</div>' : '') + '</td>' +
      '<td>' + esc(f.summary) + '</td>' +
      '<td class="num">' + compactMoney(f.exposureAmount) + '</td>' +
      '<td class="num">' + num(f.riskScore) + '</td>';
    tr.addEventListener('click', () => openDrawer(f));
    tbody.appendChild(tr);
  }
}

function openDrawer(f) {
  const meta = SEVERITY[f.severity] || SEVERITY.LOW;
  document.getElementById('drawer-code').textContent = f.ruleCode + ' · ' + f.controlDomain;
  document.getElementById('drawer-title').textContent = f.summary;

  const facts = [
    ['Control', f.ruleTitle],
    ['Severity', meta.icon + ' ' + meta.label],
    ['Risk score', num(f.riskScore) + ' / 100'],
    ['Exposure', fullMoney(f.exposureAmount)],
    ['Vendor', f.vendorName ? f.vendorName + ' (' + f.vendorCode + ')' : '—'],
    ['Category', f.vendorCategory || '—'],
    ['Transaction', f.txnRef || '—'],
    ['Invoice', f.invoiceNo || '—'],
    ['Invoice value', f.txnAmount ? fullMoney(f.txnAmount) : '—'],
    ['Invoice date', f.invoiceDate ? String(f.invoiceDate).slice(0, 10) : '—'],
  ];
  document.getElementById('drawer-facts').innerHTML = facts
    .map(([k, v]) => '<dt>' + esc(k) + '</dt><dd>' + esc(v) + '</dd>').join('');

  document.getElementById('drawer-evidence').textContent = f.evidence || 'No further detail recorded.';
  document.getElementById('drawer').hidden = false;
}

function closeDrawer() { document.getElementById('drawer').hidden = true; }

/* ------------------------------ wiring ------------------------------ */

function populateRuleFilter() {
  const select = document.getElementById('filter-rule');
  select.innerHTML = '<option value="">All</option>';
  for (const r of DATA.catalogue || []) {
    const opt = document.createElement('option');
    opt.value = r.code;
    opt.textContent = r.code + ' - ' + r.title;
    select.appendChild(opt);
  }
}

function renderAll() {
  allFindings = DATA.findings || [];
  renderHeadline();
  renderSeverity();
  renderBenford();
  renderSpend();
  renderRules();
  renderVendors();
  renderFindings();
}

async function init() {
  try {
    DATA = await loadData();
    populateRuleFilter();
    renderAll();
  } catch (err) {
    document.getElementById('scan-meta').textContent = 'Failed to load audit data';
    console.error(err);
  }
}

document.getElementById('filter-severity').addEventListener('change', renderFindings);
document.getElementById('filter-rule').addEventListener('change', renderFindings);
document.getElementById('filter-text').addEventListener('input', renderFindings);
document.getElementById('drawer-close').addEventListener('click', closeDrawer);
document.getElementById('drawer').addEventListener('click', (e) => {
  if (e.target.id === 'drawer') closeDrawer();
});
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeDrawer(); });
window.addEventListener('resize', () => { if (DATA) { renderBenford(); renderSpend(); } });

document.getElementById('run-audit').addEventListener('click', async (e) => {
  const btn = e.currentTarget;
  btn.disabled = true;
  btn.textContent = 'Running…';
  try {
    const res = await fetch('api/audit/run', { method: 'POST' });
    if (!res.ok) throw new Error('engine unavailable');
    const result = await res.json();
    DATA = await loadData();
    renderAll();
    document.getElementById('scan-meta').textContent =
      count(result.transactionsScanned) + ' transactions scanned in ' +
      count(result.durationMs) + ' ms';
  } catch (_) {
    document.getElementById('scan-meta').textContent =
      'Static demo — re-run requires the Spring Boot backend';
  } finally {
    btn.disabled = false;
    btn.textContent = 'Re-run audit';
  }
});

init();
