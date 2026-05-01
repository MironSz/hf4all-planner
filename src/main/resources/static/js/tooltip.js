// Shared tooltip popup, dynamically positioned to stay on-screen.
// Pseudo-element approach was abandoned because CSS cannot read the
// popup's own width to flip alignment near edges.

// Tooltip text source — loaded from /data/tooltips.json at init. Each
// `<span class="tip" data-tip-key="...">?</span>` looks up its text by
// key and gets `data-tip` populated. Texts can also be set inline via
// `data-tip="..."` directly (the key path is just the centralised
// alternative — no migration mandate).
let TOOLTIP_TEXTS = {};

/** Walk the DOM under `root` (default: document) and populate `data-tip`
 *  on every `[data-tip-key]` element from the loaded text map. Safe to
 *  call repeatedly — overwrites the existing `data-tip`. Call after
 *  inserting new tip elements (e.g. from `addEngineBlock`). */
export function inflateTooltips(root = document) {
    const els = root.querySelectorAll('[data-tip-key]');
    els.forEach(el => {
        const key = el.getAttribute('data-tip-key');
        const text = TOOLTIP_TEXTS[key];
        if (text != null) el.setAttribute('data-tip', text);
    });
}

/** Fetch the tooltip text bundle. Resolves to the loaded map (also
 *  cached internally). Idempotent — caller doesn't need to track it. */
export async function loadTooltips() {
    try {
        const r = await fetch('/data/tooltips.json', { cache: 'no-store' });
        if (!r.ok) {
            console.warn('tooltips.json: HTTP', r.status, '— inline data-tip values will still work');
            return TOOLTIP_TEXTS;
        }
        TOOLTIP_TEXTS = await r.json();
        inflateTooltips(document);
    } catch (e) {
        console.warn('tooltips.json: load failed', e);
    }
    return TOOLTIP_TEXTS;
}

export function initTooltip() {
    const popup = document.createElement('div');
    popup.id = 'tip-popup';
    document.body.appendChild(popup);

    function show(tip) {
        const text = tip.getAttribute('data-tip') || '';
        if (!text) return;
        popup.textContent = text;
        popup.style.display = 'block';
        // Measure popup AFTER content is set so getBoundingClientRect is accurate
        const tipR = tip.getBoundingClientRect();
        const popR = popup.getBoundingClientRect();
        const vw = window.innerWidth;
        const vh = window.innerHeight;
        const margin = 4;
        const gap = 6;
        // Default: centred horizontally on tip, opening downward.
        let left = tipR.left + tipR.width / 2 - popR.width / 2;
        let top  = tipR.bottom + gap;
        // Flip upward if the popup would clip the viewport bottom AND there's
        // more room above the tip.
        if (top + popR.height > vh - margin) {
            const aboveTop = tipR.top - gap - popR.height;
            if (aboveTop >= margin) top = aboveTop;
        }
        // Clamp into viewport horizontally.
        if (left < margin) left = margin;
        if (left + popR.width > vw - margin) left = vw - margin - popR.width;
        // Clamp vertically as a final safety net.
        if (top < margin) top = margin;
        if (top + popR.height > vh - margin) top = vh - margin - popR.height;
        popup.style.left = left + 'px';
        popup.style.top  = top  + 'px';
    }
    function hide() { popup.style.display = 'none'; }

    document.addEventListener('mouseover', (e) => {
        const tip = e.target.closest && e.target.closest('.tip');
        if (tip) show(tip);
    });
    document.addEventListener('mouseout', (e) => {
        const tip = e.target.closest && e.target.closest('.tip');
        if (tip) hide();
    });
    window.addEventListener('scroll', hide, true);
    window.addEventListener('resize', hide);
}
