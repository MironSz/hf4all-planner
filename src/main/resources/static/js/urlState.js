// Shareable URL hash for the active tab's user-input state.
//
// The URL fragment carries everything a recipient needs to reproduce the
// plan locally — form (mass / fuel / engines), settings (no-Venus flyby,
// jettison, debug), site filter, search query, selected start node,
// pinned endpoint, and chosen route index. The search tree itself is
// recomputed from those inputs on the recipient's side, not pasted, so
// big traverseResult payloads stay out of the URL.
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

export const SHARE_VERSION = 2;

// The exact subset of tab state that's shareable. Adding a new knob
// requires (a) listing it here so callers can introspect, and (b)
// teaching {read,write}ShareToUrl below to (de)serialize it.
export const SHARE_FIELDS = [
    'dryMass', 'fuelText',
    'engines',
    'disableVenusFlyby', 'allowFuelJettison', 'debug',
    'siteFilter', 'searchQuery',
    'selectedNode', 'pinnedEndpoint', 'selectedRouteIndex',
];

// ---- URL-fragment-friendly encoding -----------------------------------------
// Fragments per RFC 3986 may contain `: , /` without percent-encoding, but
// `URLSearchParams.toString` encodes them aggressively for x-www-form-urlencoded
// compatibility. We build the hash by hand so structural separators stay
// readable, while still escaping characters that would break URL parsing in
// user-controlled values (search query, fuel text).
function urlEncodeReadable(s) {
    return encodeURIComponent(s)
            .replace(/%2C/g, ',')
            .replace(/%3A/g, ':')
            .replace(/%2F/g, '/')
            .replace(/%2B/g, '+');
}

// ---- Engine pack/unpack ----
// One engine becomes `base:N,fuel:N[/D],solar:1,pivots:N,abCost:N,abGain:N`.
// Default-valued fields are omitted so a vanilla engine is just `base:5,fuel:2`.
function serializeEngine(eng) {
    const parts = [];
    if (eng.baseThrust)            parts.push(`base:${eng.baseThrust}`);
    const num = (eng.fuelConsumptionNum != null) ? eng.fuelConsumptionNum : 0;
    const den = (eng.fuelConsumptionDen != null) ? eng.fuelConsumptionDen : 1;
    if (num)                       parts.push(den === 1 ? `fuel:${num}` : `fuel:${num}/${den}`);
    if (eng.solarPowered)          parts.push('solar:1');
    if (eng.bonusPivots)           parts.push(`pivots:${eng.bonusPivots}`);
    if (eng.afterburnFuelCost)     parts.push(`abCost:${eng.afterburnFuelCost}`);
    if (eng.afterburnThrustGain)   parts.push(`abGain:${eng.afterburnThrustGain}`);
    return parts.join(',');
}

function parseEngine(s) {
    const out = {
        baseThrust: 0,
        fuelConsumptionNum: 0, fuelConsumptionDen: 1,
        solarPowered: false, bonusPivots: 0,
        afterburnFuelCost: 0, afterburnThrustGain: 0,
    };
    if (!s) return out;
    for (const part of s.split(',')) {
        const idx = part.indexOf(':');
        if (idx < 0) continue;
        const k = part.slice(0, idx);
        const v = part.slice(idx + 1);
        switch (k) {
            case 'base':   out.baseThrust = parseInt(v) || 0; break;
            case 'fuel': {
                if (v.includes('/')) {
                    const [n, d] = v.split('/');
                    out.fuelConsumptionNum = parseInt(n) || 0;
                    out.fuelConsumptionDen = parseInt(d) || 1;
                } else {
                    out.fuelConsumptionNum = parseInt(v) || 0;
                    out.fuelConsumptionDen = 1;
                }
                break;
            }
            case 'solar':   out.solarPowered = (v === '1' || v === 'true'); break;
            case 'pivots':  out.bonusPivots = parseInt(v) || 0; break;
            case 'abCost':  out.afterburnFuelCost = parseInt(v) || 0; break;
            case 'abGain':  out.afterburnThrustGain = parseInt(v) || 0; break;
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
    if (params.has('q'))       out.searchQuery = params.get('q');
    if (params.has('noVenus')) out.disableVenusFlyby = params.get('noVenus') !== '0';
    if (params.has('jettison')) out.allowFuelJettison = params.get('jettison') !== '0';
    if (params.has('debug'))   out.debug = params.get('debug') !== '0';
    if (params.has('types') || params.has('hyd')) {
        out.siteFilter = {
            types: params.has('types')
                    ? params.get('types').split(',').filter(Boolean)
                    : [],
            minHydration: params.has('hyd') ? params.get('hyd') : '',
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
    if (tabState.searchQuery)                 push('q', tabState.searchQuery);
    if (tabState.disableVenusFlyby)           push('noVenus', '1');
    if (tabState.allowFuelJettison)           push('jettison', '1');
    if (tabState.debug)                       push('debug', '1');
    if (tabState.siteFilter) {
        const t = tabState.siteFilter.types;
        if (t && t.length)                    push('types', t.join(','));
        const h = tabState.siteFilter.minHydration;
        if (h != null && h !== '')            push('hyd', h);
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
