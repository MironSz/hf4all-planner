// Canvas core: zoom binding, click/move handlers, the find-nearest-node
// helper, and the clearRoutes reset.
import { state } from './state.js';
import { cfgI, cfgF } from './config.js';
import { applyMainTransform, bindTouchRotation } from './rotation.js';
import { draw } from './draw.js';
import { updateRouteInfo, updateDebugRoute } from './routeInfo.js';
import { fireTraverse } from './traverse.js';
import { persistTabs } from './tabs.js';
import { findHoveredRouteSegment } from './endpointFuelStripOverlay.js';

export function initCanvas() {
    state.imgW = state.bgImg.naturalWidth  || cfgI('ui.image.fallback.width',  5000);
    state.imgH = state.bgImg.naturalHeight || cfgI('ui.image.fallback.height', 5000);
    state.canvas.width = state.imgW;
    state.canvas.height = state.imgH;

    const z = d3.zoom()
        .scaleExtent([cfgF('ui.zoom.min', 0.1), cfgF('ui.zoom.max', 1.5)])
        .translateExtent([[0, 0], [state.imgW, state.imgH]])
        // Allow wheel even with ctrlKey so d3-zoom captures and preventDefaults
        // the event — otherwise the browser's native page zoom kicks in and
        // scales the sidebar too. Trackpad pinch gestures also fire wheel
        // events with ctrlKey=true, and this routes them to map zoom.
        .filter((event) => !event.button)
        .on('zoom', (event) => {
            const {x, y, k} = event.transform;
            applyMainTransform(x, y, k);
            // User-driven pan/zoom updates the active tab's transform.
            // sourceEvent is null when we set the transform programmatically
            // during tab activation — skip those to avoid persistence churn.
            if (event.sourceEvent && state.tabsReady) persistTabs();
        });

    state.zoomBehavior = z;
    state.zoomSel = d3.select(state.container);
    bindTouchRotation(state.container);
    state.zoomSel.call(z)
        .call(z.translateTo,
              cfgF('ui.viewport.initial.x.ratio', 0.85) * state.imgW,
              cfgF('ui.viewport.initial.y.ratio', 0.80) * state.imgH);

    state.canvas.addEventListener('click', onCanvasClick);
    state.canvas.addEventListener('mousemove', onCanvasMouseMove);
    draw();
}

// Find nearest node to map coordinates.
// filterSet: if provided, only consider nodes whose id is a key in this object.
function findNearest(mapX, mapY, maxDist, filterSet) {
    let nearest = null;
    let nearestDist = Infinity;
    for (const [id, p] of Object.entries(state.mapData.points)) {
        if (p.type === 'decorative') continue;
        if (filterSet && !(id in filterSet)) continue;
        const px = p.x * state.imgW;
        const py = p.y * state.imgH;
        const dist = Math.hypot(px - mapX, py - mapY);
        if (dist < nearestDist) {
            nearestDist = dist;
            nearest = id;
        }
    }
    return (nearest && nearestDist < maxDist) ? nearest : null;
}

// Click: select start node, or pin endpoint when a start already exists.
function onCanvasClick(event) {
    if (!state.mapData) return;

    // If we already have a start + routes, clicking pins an endpoint
    if (state.selectedNode && state.reachableNodes) {
        const epId = findNearest(event.offsetX, event.offsetY, cfgI('ui.node.click.radius', 40), state.reachableNodes);
        if (epId) {
            state.pinnedEndpoint = epId;
            state.selectedRouteIndex = 0;
            draw();
            updateDebugRoute();
            updateRouteInfo();
            persistTabs();
            return;
        }
        return; // click on nothing — ignore (ESC to clear)
    }

    // No start yet — select start node
    const nodeId = findNearest(event.offsetX, event.offsetY, cfgI('ui.start.node.click.radius', 20));
    if (!nodeId) return;

    state.selectedNode = nodeId;
    state.hoveredNode = null;
    state.pinnedEndpoint = null;
    state.selectedRouteIndex = 0;
    state.traverseResult = null;

    const p = state.mapData.points[nodeId];
    let info = nodeId.substring(0, 8) + '... (' + (p.type || '?') + ')';
    if (p.siteName) info = p.siteName + ' (' + p.type + ')';
    document.getElementById('node-info').textContent = 'Start: ' + info;

    draw();
    fireTraverse();
}

// Hover: highlight route to hovered endpoint.
function onCanvasMouseMove(event) {
    let needsDraw = false;
    if (state.mapData && state.reachableNodes) {
        const nodeId = findNearest(event.offsetX, event.offsetY, cfgI('ui.node.click.radius', 40), state.reachableNodes);
        if (nodeId !== state.hoveredNode) {
            state.hoveredNode = nodeId;
            // Each new hover starts a fresh preview at the best route.
            // (When pinned, the pinned route's selectedRouteIndex stays
            // sticky regardless of where the cursor is.)
            if (!state.pinnedEndpoint) state.selectedRouteIndex = 0;
            needsDraw = true;
            updateDebugRoute();
            updateRouteInfo();
        }
    }
    // Route-segment hover detection: only when an endpoint is pinned
    // (no route line drawn otherwise) AND the node-hover above didn't
    // already pick up a node — covers the gap when the cursor is
    // between two consecutive route nodes.
    if (state.pinnedEndpoint && !state.hoveredNode && state.mapData) {
        const seg = findHoveredRouteSegment(
                event.offsetX, event.offsetY,
                cfgI('ui.route.segment.hover.radius', 18));
        const newStartId = seg ? seg.startId : null;
        if (newStartId !== state.hoveredRouteSegmentStartId) {
            state.hoveredRouteSegmentStartId = newStartId;
            state.hoveredRouteSegmentProj   = seg ? { x: seg.projX, y: seg.projY } : null;
            needsDraw = true;
            updateRouteInfo();   // re-renders the fuel-strip panel
        } else if (seg) {
            // Same segment, cursor moved along it — refresh the dot
            // position so it tracks the cursor. The fuel-strip panel
            // depends only on segment IDENTITY, so no re-render needed.
            state.hoveredRouteSegmentProj = { x: seg.projX, y: seg.projY };
            needsDraw = true;
        }
    } else if (state.hoveredRouteSegmentStartId) {
        // Cursor moved away from segments-only territory — clear.
        state.hoveredRouteSegmentStartId = null;
        state.hoveredRouteSegmentProj   = null;
        needsDraw = true;
        updateRouteInfo();
    }
    if (state.debugMode && state.mapData) {
        const dbgId = findNearest(event.offsetX, event.offsetY, cfgI('ui.start.node.click.radius', 20));
        if (dbgId !== state.debugHoveredNode) {
            state.debugHoveredNode = dbgId;
            needsDraw = true;
        }
    }
    if (needsDraw) draw();
}

export function clearRoutes() {
    state.selectedNode = null;
    state.pinnedEndpoint = null;
    state.hoveredNode = null;
    state.selectedRouteIndex = 0;
    state.traverseResult = null;
    state.treeIndex = null;
    state.reachableNodes = null;
    document.getElementById('node-info').textContent = 'Click a node on the map to select start';
    document.getElementById('status').textContent = '';
    draw();
    updateDebugRoute();
    updateRouteInfo();
    persistTabs();
}
