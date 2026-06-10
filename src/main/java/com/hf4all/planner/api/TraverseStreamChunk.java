package com.hf4all.planner.api;

import java.util.List;
import java.util.Map;

/**
 * One message in the streaming {@code /api/traverse} response. The endpoint
 * emits these as NDJSON — one JSON object per line — so the browser can render
 * routes as the search deepens instead of waiting for the whole run.
 *
 * <p><b>Delta semantics.</b> A Pareto-optimal route that finishes in {@code N}
 * mission years is never dominated later (a deeper state has a strictly greater
 * {@code turn} and so cannot dominate on that dimension), so each year's Pareto
 * subtree is a <em>superset</em> of the previous year's. Each chunk therefore
 * carries only what is <em>new</em> since the previous one — {@code addedNodes}
 * and {@code addedEndpoints} — and the client accumulates them. The whole tree
 * crosses the wire exactly once. A closing {@code done == true} chunk flushes
 * any remaining subtree (routes deeper than the last partial year) and marks the
 * result complete.
 *
 * @param year           deepest fully-planned mission year covered so far
 *                       (1-based; {@code maxYear} on the final chunk)
 * @param maxYear        search depth cap ({@code planner.search.max.turns}) —
 *                       the "Y" in the UI's "Planned X / Y years" bar
 * @param done           {@code true} only for the final chunk
 * @param startNodeId    id of the start node (the accumulated tree's root)
 * @param addedNodes     tree nodes new this chunk, ordered parents-before-children
 * @param addedEndpoints newly Pareto-optimal endpoints: map node id → new tree
 *                       node ids to append to that node's route list
 * @param status         {@code "ok"}, or an error message on a validation failure
 */
public record TraverseStreamChunk(
    int year,
    int maxYear,
    boolean done,
    String startNodeId,
    List<StreamNode> addedNodes,
    Map<String, List<Integer>> addedEndpoints,
    String status
) {}
