package com.hf4all.game.model;

/**
 * A card in a stack. Patents: {@code {id, side: 'w'|'b', uid, orient?}}
 * (orient only for radiators). The Crew: {@code {id: 'crew', crew, face, glory, uid}}.
 */
public final class CardInst {
    public String id;
    public String side;
    public Integer uid;
    public String orient;
    public String crew;
    public Integer face;
    /** Zone id of the glory chit a Crew carries. */
    public String glory;
    /** Declared non-operational (J). */
    public Boolean nonOp;

    public CardInst() {}

    public static CardInst patent(String id, String side, int uid) {
        CardInst c = new CardInst();
        c.id = id;
        c.side = side;
        c.uid = uid;
        return c;
    }

    public static CardInst crew(String crewId, int face, int uid) {
        CardInst c = new CardInst();
        c.id = "crew";
        c.crew = crewId;
        c.face = face;
        c.uid = uid;
        return c;
    }

    public boolean isCrew() { return crew != null; }
    public boolean isLight() { return "light".equals(orient); }
}
