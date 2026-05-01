// Tree-walking helpers shared by the canvas, route-info, draw, fuel-strip
// overlay, and hex-mask modules. Pulled into their own file so the
// dependency edge is one-way (everyone imports from here; this module
// imports nothing but state).
import { state } from './state.js';

// Build flat index from tree: id → {node, parent} (iterative to handle deep trees).
// Also builds reachableNodes: mapNodeId → [treeNodeId, ...] for all nodes in tree.
export function buildTreeIndex(tree) {
    const index = {};
    const reachable = {};
    const stack = [{ node: tree, parent: null }];
    while (stack.length > 0) {
        const { node, parent } = stack.pop();
        index[node.id] = { node, parent };
        const mapId = node.nodeId;
        if (!reachable[mapId]) reachable[mapId] = [];
        reachable[mapId].push(node.id);
        for (const child of (node.children || [])) {
            stack.push({ node: child, parent: node });
        }
    }
    state.reachableNodes = reachable;
    return index;
}

// Path from tree root to a given tree node id.
export function getPathToRoot(treeNodeId) {
    if (!state.treeIndex) return [];
    const path = [];
    let entry = state.treeIndex[treeNodeId];
    while (entry) {
        path.unshift(entry.node);
        entry = entry.parent ? state.treeIndex[entry.parent.id] : null;
    }
    return path;
}

// --- Active endpoint -------------------------------------------------
//   - When an endpoint is pinned, it stays pinned regardless of hover.
//     The displayed route, route-info card, and arrow-key cycling all
//     stay locked on the pinned endpoint until the user clears with
//     Esc / starts a new route.
//   - With no pin, hovering a reachable node previews the best route
//     to it.
export function getActiveEndpoint() {
    if (state.pinnedEndpoint) return state.pinnedEndpoint;
    if (state.hoveredNode && state.reachableNodes && state.reachableNodes[state.hoveredNode]) {
        return state.hoveredNode;
    }
    return null;
}

// Get tree node IDs for a given map node (endpoints first, then all reachable).
// Multiple tree nodes can share the same physical map node but trace the
// exact same visible map-node sequence back to root (they differ only in
// internal search-state). Dedupe by path so arrow-key cycling only shows
// genuinely distinct routes.
export function getTreeNodeIds(nodeId) {
    let rawIds = null;
    if (state.traverseResult && state.traverseResult.endpoints && state.traverseResult.endpoints[nodeId]) {
        rawIds = state.traverseResult.endpoints[nodeId];
    } else if (state.reachableNodes && state.reachableNodes[nodeId]) {
        rawIds = state.reachableNodes[nodeId];
    }
    if (!rawIds) return null;
    if (rawIds.length <= 1) return rawIds;
    const seen = new Set();
    const unique = [];
    for (const tid of rawIds) {
        const seq = getPathToRoot(tid).map(n => n.nodeId).join('|');
        if (!seen.has(seq)) {
            seen.add(seq);
            unique.push(tid);
        }
    }
    return unique;
}

// Active route index — always returns state.selectedRouteIndex.
//
// For hovered (un-pinned) endpoints we used to hard-return 0 here, which
// meant arrow keys couldn't drive the preview without first pinning. The
// hover-driven reset to 0 now lives in onCanvasMouseMove (so a fresh
// hover still starts at the best route), and arrow keys can cycle freely
// without changing pin status.
export function getActiveRouteIndex() {
    return state.selectedRouteIndex;
}
