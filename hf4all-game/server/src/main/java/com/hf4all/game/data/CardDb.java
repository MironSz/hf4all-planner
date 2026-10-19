package com.hf4all.game.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hf4all.game.util.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 77 patent cards and 6 Crew cards of {@code data/cards.js} (strict JSON,
 * shared with the browser client and tools/extract_cards.py).
 */
public final class CardDb {

    public static final List<String> DECKS = List.of("thruster", "robonaut", "refinery", "generator", "reactor", "radiator");

    private final Map<String, CardDef> defs = new LinkedHashMap<>();
    private final Map<String, String> deckOf = new LinkedHashMap<>();
    private final Map<String, List<CardDef>> decks = new LinkedHashMap<>();
    private final Map<String, Crew> crews = new LinkedHashMap<>();

    /** One patent card: both sides are complete descriptions. */
    public static final class CardDef {
        public String id;
        public String vassal;
        public CardSide w;
        public CardSide b;
    }

    /** One side of a patent card (field names as in data/cards.js). */
    public static final class CardSide {
        public String name;
        public Integer mass;
        public Integer rad;
        public String spec;
        public Thrust thrust;
        public Isru isru;
        public Req req;
        public Prov prov;
        public Mod mod;
        public Boolean solar;
        public Boolean sail;
        public Boolean magSail;
        public JsonObject ab;
        public String text;
        public Integer therms;
        public Light light;
    }

    public static final class Thrust {
        public int t;
        public double f;
        public String g;
        public Integer ab;
        public Boolean push;
        public Integer pivots;
    }

    public static final class Isru {
        public int r;
        public String k;
    }

    public static final class Req {
        public List<String> gen;
        public List<String> reactor;
        public Integer therms;
    }

    public static final class Prov {
        public List<String> gen;
        public List<String> reactor;
    }

    public static final class Mod {
        public Integer thrust;
        public Double fuelMul;
        public Boolean solar;
    }

    public static final class Light {
        public Integer mass;
        public Integer rad;
        public Integer therms;
    }

    public static final class Crew {
        public String id;
        public String num;
        public String color;
        public String vassal;
        public List<CrewSide> sides = new ArrayList<>();
    }

    public static final class CrewSide {
        public String faction;
        public String privilege;
        public String clout;
        public int mass;
        public int rad;
        public Isru isru;
        public String engine;
        public Thrust thrust;
    }

    public CardDb(Path cardsJs) {
        JsonObject root = Json.readJsData(cardsJs).getAsJsonObject();
        JsonObject d = root.getAsJsonObject("decks");
        for (String deck : d.keySet()) {
            List<CardDef> list = new ArrayList<>();
            for (JsonElement e : d.getAsJsonArray(deck)) {
                CardDef c = Json.GSON.fromJson(e, CardDef.class);
                list.add(c);
                defs.put(c.id, c);
                deckOf.put(c.id, deck);
            }
            decks.put(deck, list);
        }
        for (JsonElement e : root.getAsJsonArray("crews")) {
            Crew c = Json.GSON.fromJson(e, Crew.class);
            crews.put(c.id, c);
        }
    }

    public CardDef def(String id) { return defs.get(id); }
    public String deckOf(String id) { return deckOf.get(id); }
    public Crew crew(String id) { return crews.get(id); }
    public List<String> allIds() { return new ArrayList<>(defs.keySet()); }
    public List<CardDef> deck(String deck) { return decks.get(deck); }
    public List<Crew> crews() { return new ArrayList<>(crews.values()); }
}
