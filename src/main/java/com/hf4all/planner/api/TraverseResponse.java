package com.hf4all.planner.api;

import java.util.List;
import java.util.Map;

public record TraverseResponse(
    String startNodeId,
    PathNode tree,
    Map<String, List<Integer>> endpoints,
    String status
) {}
