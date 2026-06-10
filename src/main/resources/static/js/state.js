// Shared mutable state. Every module imports the same `state` object
// reference, so writes from any module are observable to all.
//
// Keep this object the single home for cross-module mutable values. Module-
// private state (e.g. tabs persist debounce, hex-mask SVG groups) lives
// inside the relevant module file as plain `let` bindings.
export const state = {
    // --- Map data + current selection ---
    mapData: null,
    selectedNode: null,    // starting node id
    hoveredNode: null,     // node under cursor (temporary, while mouse is near)
    pinnedEndpoint: null,  // clicked final destination (stays until ESC or new click)
    selectedRouteIndex: 0, // which Pareto-optimal route to show (arrow keys)
    traverseResult: null,  // response from /api/traverse
    treeIndex: null,       // id → {node, parent} built from tree
    reachableNodes: null,  // nodeId → [treeNodeId, ...] for all nodes in tree
    debugMode: false,
    debugHoveredNode: null, // nearest node under cursor (debug only, unfiltered)

    // --- Image / canvas dimensions ---
    imgW: 0,
    imgH: 0,

    // --- Map rotation ---
    mapRotation: 0,        // radians; q/e keys nudge by ±ROTATION_STEP_RAD
    rotAnim: null,         // active rotation animation, or null when idle
    rotAnimScheduled: false,

    // --- d3-zoom binding (set in initCanvas) ---
    zoomBehavior: null,
    zoomSel: null,

    // --- Tabs ---
    tabs: [],
    activeTab: null,
    tabSeq: 1,             // monotonic id source
    pendingFetchToken: 0,  // bumped on tab switch to discard stale fetches
    traverseAbort: null,   // AbortController for the in-flight /api/traverse stream
    suppressFire: false,   // skip fireTraverse during DOM rebuilds
    tabsReady: false,      // gate snapshots until first activate

    // --- Endpoint fuel-strip overlay hover state ---
    hoveredRouteSegmentStartId: null, // mapNodeId at the START of the hovered segment
    hoveredRouteSegmentProj: null,    // {x,y} in image-pixel coords: cursor projection
    fuelStripHoverIndicatorId: null,  // mapNodeId for the green-dot indicator

    // --- Flight animation state ---
    // Set by startFlightAnimation when a route is pinned; cleared at end
    // of the run or when the pin clears. While non-null, draw.js draws
    // the junker on top of the route line via drawFlight().
    flightAnim: null,

    // --- DOM refs (populated once by main.js) ---
    bgImg: null,
    canvas: null,
    ctx: null,
    flightCanvas: null,    // overlay canvas for the junker flight sprite
    flightCtx: null,
    mainEl: null,
    container: null,

    // --- Hooks fired at the end of draw(). Replaces the original
    //     monkey-patch that wrapped draw() to call updateHexMasks.
    drawHooks: [],
};

// HF4A weight-class rotation step — q/e keys + rotate buttons nudge by this.
export const ROTATION_STEP_RAD = Math.PI / 12; // 15°
export const ROTATION_ANIM_MS  = 180;          // ease-out duration per nudge
