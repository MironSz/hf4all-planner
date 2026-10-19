package com.hf4all.game.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An engine the player built for their Rocket (J1c): a thrust-triangle card and
 * the support picked for each requirement of its chain. Cards are named by uid
 * ({@code "uid:gen|reactor" -> provider uid}) so the choice survives the stack
 * being reordered; an engine whose card left the Rocket is dropped.
 */
public final class Engine {
    public String id;
    public int thruster;
    public Map<String, Integer> picks = new LinkedHashMap<>();
}
