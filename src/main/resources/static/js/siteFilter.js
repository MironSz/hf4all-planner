// Site filter — purely a visual overlay on already-computed endpoints.
// Owns its own DOM controls (read/write), the compiled-predicate factory,
// the header summary, the accordion, and the F / Shift+F keyboard
// shortcuts. Filter state changes redraw the canvas + persist the active
// tab; they never re-trigger a /api/traverse.
import { state } from './state.js';
import { draw } from './draw.js';
import { persistTabs } from './tabs.js';

// Boolean site modifiers shown as tri-state controls in the filter panel.
// Keys match the corresponding JSON flags on site nodes.
export const SITE_FLAG_KEYS = [
    { key: 'atmospheric',   label: 'atmospheric'   },
    { key: 'astrobiology',  label: 'astrobiology'  },
    { key: 'submarine',     label: 'submarine'     },
    { key: 'push',          label: 'push (powersat)' },
];

export function makeDefaultSiteFilter() {
    return {
        types: [],
        minHydration: '',
        flags: [],           // list of flag keys the user has switched on
        flagsMode: 'any',    // 'any' (OR) or 'all' (AND) across selected flags
        synodic: 'any',
    };
}

// Upgrade persisted state from older shapes into the current filter record
// without losing the user's prior selection.
export function migrateSiteFilter(raw) {
    const def = makeDefaultSiteFilter();
    if (!raw || typeof raw !== 'object') return def;
    // hydration.min → minHydration; drop max.
    let minHydration = '';
    if (typeof raw.minHydration === 'string' || typeof raw.minHydration === 'number') {
        minHydration = String(raw.minHydration);
    } else if (raw.hydration && typeof raw.hydration === 'object') {
        minHydration = raw.hydration.min != null ? String(raw.hydration.min) : '';
    }
    // flags: older tri-state shape was { key: null|true|false } — keep only
    // the "on" (true) entries, drop the "must-be-false" ones. Newer shape
    // is already a list of enabled flag keys.
    let flags = [];
    if (Array.isArray(raw.flags)) {
        flags = raw.flags.filter(k => SITE_FLAG_KEYS.some(f => f.key === k));
    } else if (raw.flags && typeof raw.flags === 'object') {
        flags = SITE_FLAG_KEYS
            .filter(f => raw.flags[f.key] === true)
            .map(f => f.key);
    }
    return {
        types:        Array.isArray(raw.types) ? raw.types.slice() : [],
        minHydration,
        flags,
        flagsMode:    raw.flagsMode === 'all' ? 'all' : 'any',
        synodic:      typeof raw.synodic === 'string' ? raw.synodic : 'any',
    };
}

// Pull the filter DOM controls into the filter state shape.
export function readSiteFilterFromDom() {
    const types = [];
    document.querySelectorAll('.site-type-cb').forEach(cb => {
        if (cb.checked) types.push(cb.value);
    });
    const flags = SITE_FLAG_KEYS
        .filter(f => {
            const cb = document.getElementById('flag-cb-' + f.key);
            return cb && cb.checked;
        })
        .map(f => f.key);
    const synSel = document.getElementById('synodic-filter');
    const modeBtn = document.getElementById('flags-mode-toggle');
    return {
        types,
        minHydration: document.getElementById('hydration-min').value,
        flags,
        flagsMode: modeBtn && modeBtn.dataset.mode === 'all' ? 'all' : 'any',
        synodic: synSel ? synSel.value : 'any',
    };
}

// Push a filter state into the DOM controls without firing change events.
export function writeSiteFilterToDom(f) {
    const types = f.types || [];
    document.querySelectorAll('.site-type-cb').forEach(cb => {
        cb.checked = types.indexOf(cb.value) >= 0;
    });
    document.getElementById('hydration-min').value = f.minHydration || '';
    const on = new Set(f.flags || []);
    SITE_FLAG_KEYS.forEach(fd => {
        const cb = document.getElementById('flag-cb-' + fd.key);
        if (cb) cb.checked = on.has(fd.key);
    });
    setFlagsMode(f.flagsMode === 'all' ? 'all' : 'any');
    const synSel = document.getElementById('synodic-filter');
    if (synSel) synSel.value = (f.synodic || 'any');
    updateFilterHeader();
}

function setFlagsMode(mode) {
    const btn = document.getElementById('flags-mode-toggle');
    if (!btn) return;
    btn.dataset.mode = (mode === 'all') ? 'all' : 'any';
    btn.textContent = (mode === 'all') ? '(all-of)' : '(any-of)';
}

// Normalise the filter state into a compiled predicate context.
// Returns {active, match(point)}; active means at least one constraint is set.
// Selected modifier flags combine by the flagsMode toggle: 'any' (OR) means
// the site needs at least one of the selected flags; 'all' (AND) means it
// must carry every selected flag. When no flags are selected the modifier
// test is skipped entirely.
function compileSiteFilter(f) {
    f = f || makeDefaultSiteFilter();
    const types = f.types && f.types.length ? new Set(f.types) : null;
    const hMin  = numOrNull(f.minHydration);
    const flagKeys = Array.isArray(f.flags) ? f.flags.slice() : [];
    const flagsMode = f.flagsMode === 'all' ? 'all' : 'any';
    const synodic = f.synodic || 'any';
    const active = !!(types || hMin !== null
                      || flagKeys.length > 0
                      || synodic !== 'any');
    const match = (p) => {
        if (!p || p.type !== 'site') return false;
        // spectral type
        if (types) {
            const letter = sizeClassLetter(p.siteSize);
            if (!types.has(letter)) return false;
        }
        // hydration (siteWater is string in JSON)
        if (hMin !== null) {
            const water = parseInt(p.siteWater) || 0;
            if (water < hMin) return false;
        }
        // modifiers — OR or AND across selected flags depending on mode
        if (flagKeys.length > 0) {
            if (flagsMode === 'all') {
                for (let i = 0; i < flagKeys.length; i++) {
                    if (!p[flagKeys[i]]) return false;
                }
            } else {
                let any = false;
                for (let i = 0; i < flagKeys.length; i++) {
                    if (p[flagKeys[i]]) { any = true; break; }
                }
                if (!any) return false;
            }
        }
        // synodic
        if (synodic === 'none') {
            if (p.synodic) return false;
        } else if (synodic !== 'any') {
            if (p.synodic !== synodic) return false;
        }
        return true;
    };
    return { active, match };
}

// Legacy helpers — kept so existing call-sites need no rewrite.
export function readSiteFilter() {
    const raw = readSiteFilterFromDom();
    const compiled = compileSiteFilter(raw);
    return { active: compiled.active, raw, match: compiled.match };
}
export function siteMatchesFilter(point, filter) {
    if (!filter.active) return false;
    return filter.match(point);
}

function numOrNull(v) {
    if (v == null) return null;
    const s = String(v).trim();
    if (s === '') return null;
    const n = Number(s);
    return Number.isFinite(n) ? n : null;
}
function sizeClassLetter(sizeStr) {
    sizeStr = sizeStr || '';
    for (let i = sizeStr.length - 1; i >= 0; i--) {
        const ch = sizeStr.charAt(i);
        if (ch >= 'A' && ch <= 'Z') return ch;
    }
    return '';
}

// Refresh the collapsed filter summary line + reset-button visibility.
export function updateFilterHeader() {
    const raw = readSiteFilterFromDom();
    const compiled = compileSiteFilter(raw);
    const summary = document.getElementById('site-filter-summary');
    const resetBtn = document.getElementById('site-filter-reset');
    let text;
    if (!compiled.active) {
        text = 'Filter: off';
        resetBtn.classList.remove('show');
    } else {
        const bits = [];
        if (raw.types.length) bits.push(raw.types.join('/'));
        if (raw.minHydration !== '') {
            bits.push('H₂O ≥ ' + raw.minHydration);
        }
        if (raw.flags.length) {
            const sep = raw.flagsMode === 'all' ? '&' : '|';
            bits.push(raw.flags.join(sep));
        }
        if (raw.synodic && raw.synodic !== 'any') {
            bits.push(raw.synodic === 'none' ? 'non-synodic' : 'syn=' + raw.synodic);
        }
        // Count matches across all site nodes (not just reachable) so the
        // summary reflects the filter itself, independent of the current
        // traverse result.
        let total = 0, hit = 0;
        if (state.mapData && state.mapData.points) {
            for (const k in state.mapData.points) {
                const pt = state.mapData.points[k];
                if (pt.type === 'site') {
                    total++;
                    if (compiled.match(pt)) hit++;
                }
            }
        }
        text = 'Filter: ' + bits.join(', ')
             + ' <span class="match-count">(' + hit + '/' + total + ')</span>';
        resetBtn.classList.add('show');
    }
    summary.innerHTML = text;
}

function onFilterChanged() {
    updateFilterHeader();
    draw();
    persistTabs();
}

// Accordion open/close
function setFilterOpen(open) {
    document.getElementById('site-filter-body').classList.toggle('open', open);
    document.getElementById('site-filter-toggle').classList.toggle('open', open);
}

// Reset all filters back to defaults.
function resetSiteFilter() {
    writeSiteFilterToDom(makeDefaultSiteFilter());
    onFilterChanged();
}

// One-shot DOM wiring at module init time. Mirrors the order of the
// original IIFE so behaviour is identical: build flag grid first, then
// hook listeners on the static controls, then keyboard shortcuts, then
// kick off the polling waitForMap that updates the header once map
// data resolves.
export function initSiteFilter() {
    // Build the modifier checkbox grid.
    const host = document.getElementById('site-flag-grid');
    SITE_FLAG_KEYS.forEach(f => {
        const row = document.createElement('label');
        row.className = 'flag-cb-row';
        const cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.id = 'flag-cb-' + f.key;
        cb.addEventListener('change', onFilterChanged);
        const label = document.createElement('span');
        label.textContent = f.label;
        row.appendChild(cb);
        row.appendChild(label);
        host.appendChild(row);
    });

    document.querySelectorAll('.site-type-cb').forEach(cb => {
        cb.addEventListener('change', onFilterChanged);
    });
    document.getElementById('site-type-none').addEventListener('click', () => {
        document.querySelectorAll('.site-type-cb').forEach(cb => { cb.checked = false; });
        onFilterChanged();
    });

    document.getElementById('hydration-min').addEventListener('input', onFilterChanged);
    document.getElementById('synodic-filter').addEventListener('change', onFilterChanged);
    // Initialise and wire the ANY/ALL modifier-mode toggle.
    setFlagsMode('any');
    document.getElementById('flags-mode-toggle').addEventListener('click', (e) => {
        e.stopPropagation();
        const btn = e.currentTarget;
        setFlagsMode(btn.dataset.mode === 'all' ? 'any' : 'all');
        onFilterChanged();
    });

    document.getElementById('site-filter-header').addEventListener('click', (e) => {
        // Don't toggle when the user clicked the inline reset button.
        if (e.target.closest('#site-filter-reset')) return;
        const body = document.getElementById('site-filter-body');
        setFilterOpen(!body.classList.contains('open'));
    });

    document.getElementById('site-filter-reset').addEventListener('click', (e) => {
        e.stopPropagation();
        resetSiteFilter();
    });

    // Keep the header summary current once the map has loaded (counts need points).
    (function waitForMap() {
        if (state.mapData) { updateFilterHeader(); return; }
        setTimeout(waitForMap, 100);
    })();
}

// Keyboard: F toggles the panel, Shift+F resets filters.
// Exported for main.js which owns the global keydown listener that also
// handles Q/E. (Mirrors the original IIFE which had both groups in one
// listener.)
export function handleFilterKey(e) {
    if (e.key === 'F') {           // Shift+F
        e.preventDefault();
        resetSiteFilter();
        return true;
    } else if (e.key === 'f') {    // bare F
        e.preventDefault();
        const body = document.getElementById('site-filter-body');
        setFilterOpen(!body.classList.contains('open'));
        return true;
    }
    return false;
}
