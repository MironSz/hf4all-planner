package com.hf4all.game.model;

import java.util.ArrayList;
import java.util.List;

/** One faction at the table ({@code G.players[i]}). */
public final class Player {
    public int idx;
    public String name;
    public String color;
    /** Crew card id (CW, CY, ...) and the face (faction side) picked at setup. */
    public String crew;
    public int face;
    public String faction;
    public String privilege;
    public String clout;
    /** Aquas: the FTs in LEO, also the money. */
    public int aquas;
    public List<String> hand = new ArrayList<>();
    public Stacks stacks = new Stacks();
    public List<Glory> glories = new ArrayList<>();
    public List<String> heroism = new ArrayList<>();
    /** Permanent Powersat privilege (POWER GIRDLE / IONOSAT). */
    public Boolean powersat;

    public static final class Glory {
        public String zone;
        public int vp;
        public Glory() {}
        public Glory(String zone, int vp) { this.zone = zone; this.vp = vp; }
    }

    /** The four stacks of the playmat; rocket and outposts are null when not in play. */
    public static final class Stacks {
        public Stack leo = new Stack();
        public Stack rocket;
        public Stack out1;
        public Stack out2;

        public Stack get(String key) {
            return switch (key) {
                case "leo" -> leo;
                case "rocket" -> rocket;
                case "out1" -> out1;
                case "out2" -> out2;
                default -> throw new IllegalArgumentException("No stack " + key);
            };
        }

        public void set(String key, Stack s) {
            switch (key) {
                case "leo" -> leo = s;
                case "rocket" -> rocket = s;
                case "out1" -> out1 = s;
                case "out2" -> out2 = s;
                default -> throw new IllegalArgumentException("No stack " + key);
            }
        }
    }
}
