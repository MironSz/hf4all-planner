package com.hf4all.planner.server.dto;

public record EngineSpec(
    int netThrust,
    int fuelConsumption,
    boolean solarPowered,
    int bonusPivots
) {}
