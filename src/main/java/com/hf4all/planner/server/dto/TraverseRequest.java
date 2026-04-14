package com.hf4all.planner.server.dto;

import java.util.List;

public record TraverseRequest(
    String startNodeId,
    List<EngineSpec> engines,
    int fuel
) {}
