// Shareable URL hash for the active tab's user-input state.
//
// The URL fragment carries everything a recipient needs to reproduce the
// plan locally — form (mass / fuel / engines), settings (no-Venus flyby,
// jettison, debug), site filter, search query, selected start node,
// pinned endpoint, and chosen route index. The search tree itself is
// recomputed from those inputs on the recipient's side, not pasted, so
// big traverseResult payloads stay out of the URL.
//
// Encoding: a versioned JSON object {v:1, s:{...}} → UTF-8 → base64url,
// living under "#s=...". `history.replaceState` is used for writes so
// edits don't grow the back-stack and don't fire our own `hashchange`
// listener (replaceState does NOT trigger hashchange).
//
// Scope is intentionally the ACTIVE tab only — the rest of the user's
// tabs are a local concept; pasting a link should slot the plan into
// the recipient's current tab without disturbing their other tabs.

export const SHARE_VERSION = 1;
const SHARE_KEY = 's';

// The exact subset of tab state that's shareable. Order doesn't matter;
// adding a new knob is a one-line edit here. Anything outside this list
// (zoom transform, traverseResult, the rest of the tab strip) stays
// local. Engine-block sub-fields are NOT enumerated — engines is a
// nested array and rides along whole.
export const SHARE_FIELDS = [
    'dryMass', 'fuelText',
    'engines',
    'disableVenusFlyby', 'allowFuelJettison', 'debug',
    'siteFilter', 'searchQuery',
    'selectedNode', 'pinnedEndpoint', 'selectedRouteIndex',
];

function pickShareable(tabState) {
    const out = {};
    for (const k of SHARE_FIELDS) {
        if (tabState[k] !== undefined) out[k] = tabState[k];
    }
    return out;
}

// --- base64url helpers (no padding, URL-safe alphabet) ---
function b64urlEncode(bytes) {
    let bin = '';
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function b64urlDecode(str) {
    let s = str.replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4) s += '=';
    const bin = atob(s);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
}

function encodePayload(payload) {
    return b64urlEncode(new TextEncoder().encode(JSON.stringify(payload)));
}
function decodePayload(enc) {
    return JSON.parse(new TextDecoder().decode(b64urlDecode(enc)));
}

/**
 * Read the share value from "#s=..." in the address bar. Returns the raw
 * inner state object (dryMass / fuelText / ...) or null when the hash is
 * absent, missing the `s` key, or malformed / wrong version.
 */
export function readShareFromUrl() {
    const hash = (window.location.hash || '').replace(/^#/, '');
    if (!hash) return null;
    const params = new URLSearchParams(hash);
    const enc = params.get(SHARE_KEY);
    if (!enc) return null;
    try {
        const payload = decodePayload(enc);
        if (!payload || payload.v !== SHARE_VERSION || typeof payload.s !== 'object') {
            return null;
        }
        return payload.s;
    } catch (err) {
        console.warn('share URL decode failed:', err);
        return null;
    }
}

/**
 * Write a tab's shareable fields into the URL hash via replaceState.
 * No-op when the resulting hash already matches what's in the address
 * bar (so this is cheap to call from a debounced persistTabs).
 */
export function writeShareToUrlFromTab(tabState) {
    if (!tabState) return;
    let enc;
    try {
        enc = encodePayload({ v: SHARE_VERSION, s: pickShareable(tabState) });
    } catch (err) {
        console.warn('share URL encode failed:', err);
        return;
    }
    const newHash = '#' + SHARE_KEY + '=' + enc;
    if (window.location.hash === newHash) return;
    history.replaceState(null, '',
            window.location.pathname + window.location.search + newHash);
}
