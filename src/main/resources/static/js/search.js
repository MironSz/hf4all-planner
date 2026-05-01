// Search box: filter sites by name or id, focus + zoom map on pick.
// On pick we also kick the hex-mask "spotlight" so every other site dims
// briefly. Persists every keystroke into the active tab so a tab restore
// re-opens with the same query.
import { state } from './state.js';
import { cfgI, cfgF } from './config.js';
import { startSearchSpotlight } from './hexMask.js';
import { persistTabs } from './tabs.js';

export function initSearchBox() {
    const input = document.getElementById('search-input');
    const list  = document.getElementById('search-suggestions');
    let suggestions = []; // array of {id, label}
    let activeIdx = -1;

    function renderList() {
        if (suggestions.length === 0) { list.style.display = 'none'; return; }
        list.innerHTML = '';
        suggestions.forEach((s, i) => {
            const row = document.createElement('div');
            row.textContent = s.label;
            row.style.cssText = 'padding:4px 8px;cursor:pointer;color:#ccc;'
                    + (i === activeIdx ? 'background:#1b3a70;color:#fff;' : '');
            row.addEventListener('mousedown', (ev) => {
                ev.preventDefault(); // keep focus in input
                choose(i);
            });
            list.appendChild(row);
        });
        list.style.display = 'block';
    }

    function updateSuggestions() {
        const q = input.value.trim().toLowerCase();
        if (!state.mapData || !q) { suggestions = []; activeIdx = -1; renderList(); return; }
        const matches = [];
        for (const [id, p] of Object.entries(state.mapData.points)) {
            if (p.type === 'decorative') continue;
            const name = p.siteName || '';
            if (name.toLowerCase().includes(q) || id.toLowerCase().startsWith(q)) {
                matches.push({
                    id,
                    label: name ? `${name}  [${p.type}]` : `${id.substring(0, 14)}  [${p.type}]`
                });
                if (matches.length >= cfgI('ui.search.max.results', 20)) break;
            }
        }
        suggestions = matches;
        activeIdx = matches.length > 0 ? 0 : -1;
        renderList();
    }

    function focusNode(id) {
        const p = state.mapData && state.mapData.points && state.mapData.points[id];
        if (!p || !state.zoomBehavior || !state.zoomSel) return;
        // Centre the view on the target node AND zoom in: search picks
        // are usually for inspecting a specific site, so we want
        // detail-level zoom rather than just panning. Clamp to scaleExtent
        // and never zoom OUT below the user's current scale (so picking a
        // site while already zoomed deeper preserves the deeper level).
        const rect = state.container.getBoundingClientRect();
        const cur  = d3.zoomTransform(state.zoomSel.node());
        const minK = cfgF('ui.zoom.min', 0.1);
        const maxK = cfgF('ui.zoom.max', 1.5);
        const desired = cfgF('ui.search.zoom.scale', 1.2);
        const k = Math.max(cur.k, Math.min(maxK, Math.max(minK, desired)));
        const tx = rect.width  / 2 - k * (p.x * state.imgW);
        const ty = rect.height / 2 - k * (p.y * state.imgH);
        const target = d3.zoomIdentity.translate(tx, ty).scale(k);
        state.zoomSel.transition().duration(cfgI('ui.zoom.transition.ms', 400))
            .call(state.zoomBehavior.transform, target);
    }

    function choose(i) {
        if (i < 0 || i >= suggestions.length) return;
        const pick = suggestions[i];
        input.value = pick.label.split('  ')[0];
        suggestions = [];
        renderList();
        focusNode(pick.id);
        input.blur();
        // Brief spotlight: dim every other site so the picked one
        // stands out the moment the user lands on it.
        startSearchSpotlight(pick.id);
    }

    input.addEventListener('input', updateSuggestions);
    input.addEventListener('focus', updateSuggestions);
    input.addEventListener('blur', () => {
        // Delay hide so mousedown on a row can still fire
        setTimeout(() => { list.style.display = 'none'; }, cfgI('ui.search.blur.delay.ms', 150));
    });
    input.addEventListener('keydown', (e) => {
        if (e.key === 'ArrowDown') {
            if (suggestions.length === 0) return;
            activeIdx = (activeIdx + 1) % suggestions.length;
            renderList();
            e.preventDefault();
        } else if (e.key === 'ArrowUp') {
            if (suggestions.length === 0) return;
            activeIdx = (activeIdx - 1 + suggestions.length) % suggestions.length;
            renderList();
            e.preventDefault();
        } else if (e.key === 'Enter') {
            if (activeIdx >= 0) choose(activeIdx);
            else if (suggestions.length > 0) choose(0);
            e.preventDefault();
        } else if (e.key === 'Escape') {
            input.blur();
            list.style.display = 'none';
        }
    });

    // Persist every keystroke into the active tab.
    input.addEventListener('input', persistTabs);
}
