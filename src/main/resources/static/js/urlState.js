// Shareable URL hash for the active tab's user-input state.
//
// The URL fragment carries what a recipient needs to reproduce the plan
// locally — form (mass / fuel / engines), settings (jettison), site
// filter, selected start node, pinned endpoint, and chosen route index.
// The search tree itself is recomputed from those inputs on the
// recipient's side, not pasted, so big traverseResult payloads stay out
// of the URL.
//
// Deliberately NOT shared: the debug and show-fuel-strip toggles
// (per-user UI preferences) and the site-filter search query
// (typed-but-not-yet-applied input that's noisy to round-trip).
//
// Encoding is human-readable: each shareable field becomes its own
// `key=value` pair under the URL fragment. Engines (the only nested
// array) use `engine=base:5,fuel:2,solar:1,...` repeated once per engine.
// `history.replaceState` is used for writes so edits don't grow the
// back-stack and don't fire our own `hashchange` listener.
//
// Example URL:
//   #dry=4&fuel=15&start=138&engine=base:5,fuel:2&route=0
//
// Scope is intentionally the ACTIVE tab only — the rest of the user's
// tabs are a local concept; pasting a link should slot the plan into
// the recipient's current tab without disturbing their other tabs.

// The exact subset of tab state that's shareable. Adding a new knob
// requires (a) listing it here so callers can introspect, and (b)
// teaching {read,write}ShareToUrl below to (de)serialize it.
export const SHARE_FIELDS = [
    'dryMass', 'fuelText',
    'engines',
    'allowFuelJettison',
    'siteFilter',
    'selectedNode', 'pinnedEndpoint', 'selectedRouteIndex',
];

// ---- URL-fragment-friendly encoding -----------------------------------------
// Fragments per RFC 3986 may contain `: , /` without percent-encoding, but
// `URLSearchParams.toString` encodes them aggressively for x-www-form-urlencoded
// compatibility. We build the hash by hand so structural separators stay
// readable, while still escaping characters that would break URL parsing in
// user-controlled values (search query, fuel text).
// NOTE: `+` must stay percent-encoded — URLSearchParams decodes a literal
// `+` as a space, which would turn a shared fuel value like "1+5/6" into
// "1 5/6" on the recipient's side.
function urlEncodeReadable(s) {
    return encodeURIComponent(s)
            .replace(/%2C/g, ',')
            .replace(/%3A/g, ':')
            .replace(/%2F/g, '/');
}

// ---- Engine pack/unpack ----
// One engine becomes `base:N,fuel:N,solar:1,pivots:N,ab:1,abCost:N,abGain:N`.
// Both sides speak the TAB-STATE engine shape (the one snapshotted from the
// engine form: fuelConsumption / canAfterburn / ...) so a round-trip is
// lossless. Zero/false-valued fields are omitted, and afterburn cost/gain
// only appear when afterburn is actually enabled — an engine whose Afterburn
// box is unchecked shares nothing about it.
function serializeEngine(eng) {
    const parts = [];
    if (eng.baseThrust)            parts.push(`base:${eng.baseThrust}`);
    if (eng.fuelConsumption)       parts.push(`fuel:${eng.fuelConsumption}`);
    if (eng.solarPowered)          parts.push('solar:1');
    if (eng.bonusPivots)           parts.push(`pivots:${eng.bonusPivots}`);
    if (eng.canAfterburn) {
        parts.push('ab:1');
        if (eng.afterburnFuelCost)   parts.push(`abCost:${eng.afterburnFuelCost}`);
        if (eng.afterburnThrustGain) parts.push(`abGain:${eng.afterburnThrustGain}`);
    }
    if (eng.magSail)               parts.push('mag:1');
    if (eng.decommissionsOnAerobrake) parts.push('aero:1');
    return parts.join(',');
}

function parseEngine(s) {
    // Defaults mirror serializeEngine's omissions (0 / false), except the
    // afterburn cost/gain which default to the form's own defaults so an
    // `ab:1` without explicit values still yields a usable engine. Links
    // written before the `ab:` key existed carry stray abCost/abGain for
    // every engine; without `ab:1` those remain inert, matching the old
    // unchecked-checkbox behavior.
    const out = {
        baseThrust: 0,
        fuelConsumption: 0,
        solarPowered: false, bonusPivots: 0,
        canAfterburn: false, afterburnFuelCost: 1, afterburnThrustGain: 1,
        magSail: false, decommissionsOnAerobrake: false,
    };
    if (!s) return out;
    for (const part of s.split(',')) {
        const idx = part.indexOf(':');
        if (idx < 0) continue;
        const k = part.slice(0, idx);
        const v = part.slice(idx + 1);
        switch (k) {
            case 'base':    out.baseThrust = parseInt(v) || 0; break;
            case 'fuel':    out.fuelConsumption = parseInt(v) || 0; break;
            case 'solar':   out.solarPowered = (v === '1' || v === 'true'); break;
            case 'pivots':  out.bonusPivots = parseInt(v) || 0; break;
            case 'ab':      out.canAfterburn = (v === '1' || v === 'true'); break;
            case 'abCost':  out.afterburnFuelCost = parseInt(v) || 1; break;
            case 'abGain':  out.afterburnThrustGain = parseInt(v) || 1; break;
            case 'mag':     out.magSail = (v === '1' || v === 'true'); break;
            case 'aero':    out.decommissionsOnAerobrake = (v === '1' || v === 'true'); break;
        }
    }
    return out;
}

// ---- Reader ----
/**
 * Read share fields from `#key=value&...` in the address bar. Returns the
 * tab-state-shaped object (dryMass / fuelText / ...) or null when the hash
 * is absent or carries no recognised fields.
 */
export function readShareFromUrl() {
    const hash = (window.location.hash || '').replace(/^#/, '');
    if (!hash) return null;
    const params = new URLSearchParams(hash);
    if (![...params.keys()].length) return null;

    const out = {};
    if (params.has('dry'))     out.dryMass = parseInt(params.get('dry')) || 0;
    if (params.has('fuel'))    out.fuelText = params.get('fuel');
    if (params.has('start'))   out.selectedNode = params.get('start');
    if (params.has('pin'))     out.pinnedEndpoint = params.get('pin');
    if (params.has('route'))   out.selectedRouteIndex = parseInt(params.get('route')) || 0;
    if (params.has('jettison')) out.allowFuelJettison = params.get('jettison') !== '0';
    if (params.has('types') || params.has('hyd') || params.has('flags')
            || params.has('fmode') || params.has('syn')) {
        out.siteFilter = {
            types: params.has('types')
                    ? params.get('types').split(',').filter(Boolean)
                    : [],
            minHydration: params.has('hyd') ? params.get('hyd') : '',
            flags: params.has('flags')
                    ? params.get('flags').split(',').filter(Boolean)
                    : [],
            flagsMode: params.get('fmode') === 'all' ? 'all' : 'any',
            synodic: params.has('syn') ? params.get('syn') : 'any',
        };
    }
    const engineStrs = params.getAll('engine');
    if (engineStrs.length) out.engines = engineStrs.map(parseEngine);
    return Object.keys(out).length ? out : null;
}

// ---- Writer ----
function buildHash(tabState) {
    const parts = [];
    const push = (k, v) => parts.push(`${k}=${urlEncodeReadable(String(v))}`);

    if (tabState.dryMass != null)             push('dry', tabState.dryMass);
    if (tabState.fuelText != null && tabState.fuelText !== '') push('fuel', tabState.fuelText);
    if (tabState.selectedNode)                push('start', tabState.selectedNode);
    if (tabState.pinnedEndpoint)              push('pin', tabState.pinnedEndpoint);
    if (tabState.selectedRouteIndex)          push('route', tabState.selectedRouteIndex);
    // Always written — omitting the key would leave the recipient on their
    // own default, so "jettison off" would silently fail to transfer.
    push('jettison', tabState.allowFuelJettison !== false ? '1' : '0');
    if (tabState.siteFilter) {
        const t = tabState.siteFilter.types;
        if (t && t.length)                    push('types', t.join(','));
        const h = tabState.siteFilter.minHydration;
        if (h != null && h !== '')            push('hyd', h);
        const fl = tabState.siteFilter.flags;
        if (fl && fl.length)                  push('flags', fl.join(','));
        if (tabState.siteFilter.flagsMode === 'all') push('fmode', 'all');
        const sy = tabState.siteFilter.synodic;
        if (sy && sy !== 'any')               push('syn', sy);
    }
    if (Array.isArray(tabState.engines)) {
        for (const eng of tabState.engines) {
            const enc = serializeEngine(eng);
            if (enc) parts.push(`engine=${enc}`);
        }
    }
    return parts.join('&');
}

/**
 * Write a tab's shareable fields into the URL hash via replaceState.
 * No-op when the resulting hash already matches what's in the address
 * bar (so this is cheap to call from a debounced persistTabs).
 */
export function writeShareToUrlFromTab(tabState) {
    if (!tabState) return;
    const body = buildHash(tabState);
    const newHash = body ? '#' + body : '';
    if (window.location.hash === newHash) return;
    history.replaceState(null, '',
            window.location.pathname + window.location.search + newHash);
}
