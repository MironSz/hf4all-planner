package com.hf4all.planner.server.dto;

import java.util.List;

public record TraverseRequest(
    String startNodeId,
    List<EngineSpec> engines,
    int fuel,
    boolean disableVenusFlyby
) {
    /** Back-compat constructor for callers that predate the disableVenusFlyby flag. */
    public TraverseRequest(String startNodeId, List<EngineSpec> engines, int fuel) {
        this(startNodeId, engines, fuel, false);
    }
}
