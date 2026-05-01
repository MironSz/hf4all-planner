// Map rotation: Q/E key presses, the rotate buttons, the touch twist
// gesture, and the "0°" reset all funnel through nudgeRotation /
// setRotation, which kick off an ease-out animation that re-applies the
// CSS transform every frame.
//
// Note: this module imports `draw` from draw.js. ES modules support
// circular references for live bindings, so this is fine — draw is only
// invoked from within functions that fire after both modules are fully
// initialised.
import { state, ROTATION_ANIM_MS } from './state.js';
import { draw } from './draw.js';

// Compose the d3-zoom transform with the current map rotation. Rotation
// pivots about the image centre so the user's mental model of "the board
// turns under the cursor" holds regardless of pan/zoom.
export function applyMainTransform(x, y, k) {
    const cx = state.imgW / 2, cy = state.imgH / 2;
    const deg = state.mapRotation * 180 / Math.PI;
    state.mainEl.style.transform =
        `translate(${x}px,${y}px) scale(${k}) `
      + `translate(${cx}px,${cy}px) rotate(${deg}deg) translate(${-cx}px,${-cy}px)`;
}

// Apply rotation given the most recent zoom transform — used by the q/r
// key handlers so we don't need to reach into d3-zoom internals.
export function reapplyTransform() {
    if (!state.zoomSel || !state.zoomBehavior) return;
    const t = d3.zoomTransform(state.zoomSel.node());
    applyMainTransform(t.x, t.y, t.k);
}

// Adjust d3-zoom's translate so that, after applying an incremental
// rotation `deltaRad`, the canvas point currently at the centre of the
// visible viewport stays at the centre. This makes Q/E (and the touch
// twist) rotate around the user's current focus instead of around the
// image centre.
//
// Derivation: the CSS transform is
//   S = (x, y) + k·C + k·R(θ)·(P − C)
// where C is the image centre and R(θ) is the current rotation. For a
// small Δθ we want the screen point V (viewport centre) to map to the
// SAME canvas point P after rotation. Solving for the new (x', y'):
//   D       = V − (x, y) − k·C
//   (x',y') = V − k·C − R(Δθ)·D
export function adjustZoomForRotationDelta(deltaRad) {
    if (!state.zoomSel || !state.zoomBehavior || !state.container || deltaRad === 0) return;
    const tr = d3.zoomTransform(state.zoomSel.node());
    const rect = state.container.getBoundingClientRect();
    const vx = rect.width / 2, vy = rect.height / 2;
    const cx = state.imgW / 2,  cy = state.imgH / 2;
    const dx = vx - tr.x - tr.k * cx;
    const dy = vy - tr.y - tr.k * cy;
    const c = Math.cos(deltaRad), s = Math.sin(deltaRad);
    const newTx = vx - tr.k * cx - (c * dx - s * dy);
    const newTy = vy - tr.k * cy - (s * dx + c * dy);
    // Mutate d3-zoom's internal transform directly so we don't fire a
    // "zoom" event on every animation frame (would churn persistTabs).
    state.zoomSel.node().__zoom = d3.zoomIdentity.translate(newTx, newTy).scale(tr.k);
}

// Animate to an ABSOLUTE rotation. Useful for the "0°" reset button.
// Delegates to nudgeRotation so we go through the same easing pipeline
// and chain cleanly onto any in-flight animation.
export function setRotation(targetRad) {
    const baseline = state.rotAnim ? state.rotAnim.target : state.mapRotation;
    nudgeRotation(targetRad - baseline);
}

// Schedule a smooth easing of mapRotation from its current value to a new
// target. If we're already mid-animation, the new target chains onto the
// current angle so repeated key presses keep the motion fluid instead of
// snapping each step.
export function nudgeRotation(deltaRad) {
    const newTarget = (state.rotAnim ? state.rotAnim.target : state.mapRotation) + deltaRad;
    state.rotAnim = {
        start: state.mapRotation,
        target: newTarget,
        startTime: performance.now(),
    };
    if (!state.rotAnimScheduled) {
        state.rotAnimScheduled = true;
        requestAnimationFrame(stepRotationAnim);
    }
}

// Two-finger twist gesture for touchscreens. Runs alongside d3-zoom's
// built-in pinch (zoom) so the user can pinch + twist simultaneously —
// each gesture only reads what it cares about (distance vs angle).
let touchRotState = null;
function _touchAngle(t1, t2) {
    return Math.atan2(t2.clientY - t1.clientY, t2.clientX - t1.clientX);
}
export function bindTouchRotation(el) {
    el.addEventListener('touchstart', (e) => {
        if (e.touches.length === 2) {
            state.rotAnim = null; // abandon any key-driven easing
            touchRotState = {
                startAngle: _touchAngle(e.touches[0], e.touches[1]),
                baseRotation: state.mapRotation,
            };
        } else {
            touchRotState = null;
        }
    }, { passive: true });
    el.addEventListener('touchmove', (e) => {
        if (e.touches.length === 2 && touchRotState) {
            const cur = _touchAngle(e.touches[0], e.touches[1]);
            const newRotation = touchRotState.baseRotation
                              + (cur - touchRotState.startAngle);
            adjustZoomForRotationDelta(newRotation - state.mapRotation);
            state.mapRotation = newRotation;
            reapplyTransform();
            draw();
        }
    }, { passive: true });
    el.addEventListener('touchend', () => { touchRotState = null; }, { passive: true });
    el.addEventListener('touchcancel', () => { touchRotState = null; }, { passive: true });
}

function stepRotationAnim() {
    state.rotAnimScheduled = false;
    if (!state.rotAnim) return;
    const now = performance.now();
    const t = Math.min(1, (now - state.rotAnim.startTime) / ROTATION_ANIM_MS);
    // ease-out cubic — decelerates as it approaches the target
    const eased = 1 - Math.pow(1 - t, 3);
    const newRotation = state.rotAnim.start + (state.rotAnim.target - state.rotAnim.start) * eased;
    // Compensate the pan so the viewport centre stays put across this frame.
    adjustZoomForRotationDelta(newRotation - state.mapRotation);
    state.mapRotation = newRotation;
    reapplyTransform();
    draw();
    if (t < 1) {
        state.rotAnimScheduled = true;
        requestAnimationFrame(stepRotationAnim);
    } else {
        adjustZoomForRotationDelta(state.rotAnim.target - state.mapRotation);
        state.mapRotation = state.rotAnim.target;
        state.rotAnim = null;
        reapplyTransform();
        draw();
    }
}

// Rotate a canvas-space point about the image centre by the current
// mapRotation. Returns the viewport-equivalent (modulo zoom/pan) — used by
// the segment-label placer to lay out boxes that stay upright after CSS
// rotation rotates the canvas bitmap.
export function rotateAboutCentre(px, py, sign) {
    const cx = state.imgW / 2, cy = state.imgH / 2;
    const a = sign * state.mapRotation;
    const c = Math.cos(a), s = Math.sin(a);
    const dx = px - cx, dy = py - cy;
    return { x: cx + dx * c - dy * s, y: cy + dx * s + dy * c };
}
