// First-run "How to use" window.
//
// Auto-opens the instructions window the first time someone opens the
// planner (and again after the stored flag expires, or after the version
// suffix below is bumped). After that it only opens when the user clicks
// the sidebar "How to use" button. The window is dismissed by the "x" in
// its bottom-right corner, which stamps a versioned, TTL'd flag in
// localStorage so the choice survives reloads and shared links.

// Versioned key — bump the suffix whenever the instructions change enough
// that returning users should see the window again.
const HOWTO_KEY = 'hf4a.howtoSeen.v1';
const HOWTO_TTL_MS = 14 * 24 * 60 * 60 * 1000; // 14 days

// True if the user has dismissed the window and the flag is still valid.
function hasSeen() {
    let raw;
    try { raw = localStorage.getItem(HOWTO_KEY); } catch (e) { return false; }
    if (!raw) return false;
    let rec;
    try { rec = JSON.parse(raw); } catch (e) { return false; }
    if (!rec || rec.seen !== true) return false;
    // Backfill: an entry from an older format may lack a ttl/ts. Stamp one
    // now and treat the dismissal as still valid.
    if (typeof rec.ttl !== 'number' || typeof rec.ts !== 'number') {
        markSeen();
        return true;
    }
    // Expired → behave as if never seen so the window re-opens.
    if (Date.now() > rec.ts + rec.ttl) return false;
    return true;
}

function markSeen() {
    const rec = { seen: true, ts: Date.now(), ttl: HOWTO_TTL_MS };
    try { localStorage.setItem(HOWTO_KEY, JSON.stringify(rec)); } catch (e) {}
}

function openHowTo() {
    const overlay = document.getElementById('howto-overlay');
    if (overlay) overlay.style.display = 'flex';
}

function closeHowTo() {
    const overlay = document.getElementById('howto-overlay');
    if (overlay) overlay.style.display = 'none';
    markSeen();
}

export function initHowTo() {
    const btn = document.getElementById('howto-btn');
    const closeBtn = document.getElementById('howto-close');
    if (btn) btn.addEventListener('click', openHowTo);
    if (closeBtn) closeBtn.addEventListener('click', closeHowTo);
    // First run (or expired / version bumped) → show it unprompted.
    if (!hasSeen()) openHowTo();
}
