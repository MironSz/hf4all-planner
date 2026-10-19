package com.hf4all.game.engine;

import com.hf4all.game.data.GameData;
import com.hf4all.game.model.CardInst;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.Player;
import com.hf4all.game.model.Site;
import com.hf4all.game.model.Stack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The ready-made mid-game demo position (the browser's js/ui/demo.js build()). */
public final class DemoFactory {

    private DemoFactory() {}

    public static GameState build() {
        GameState g = StateOps.newGame(20261016L, 4, List.of(
                new StateOps.PlayerConfig("Amelia", "CW", 0),
                new StateOps.PlayerConfig("Bo", "CP", 0),
                new StateOps.PlayerConfig("Clara", "CG", 0)));
        Set<String> used = new HashSet<>();
        Player a = g.players.get(0), b = g.players.get(1), c = g.players.get(2);
        String leo = GameData.get().map.leo;

        // Amelia (NASA Astronauts): a fuelled prospecting Rocket about to leave LEO, with a Claim on Nereus.
        CardInst ca = a.stacks.leo.cards.remove(0);
        a.stacks.rocket = Stack.at(leo);
        a.stacks.rocket.cards.addAll(List.of(ca, inst(g, used, "CT048", "w"), inst(g, used, "CB061", "w"), inst(g, used, "CG106", "w")));
        StateOps.syncRocketDry(a);
        FuelTrack.addFuel(a.stacks.rocket.fuel, 4, "water");
        a.stacks.leo.cards.add(inst(g, used, "CF070", "w"));
        a.hand = new ArrayList<>(List.of(take(used, "CT052"), take(used, "CX086"), take(used, "CD096")));
        a.aquas = 5;
        StateOps.site(g, "333").claim = 0;

        // Bo (Taikonauts): Factory on Phobos, an outpost of FTs and ET products, Rocket on Deimos.
        CardInst cb = b.stacks.leo.cards.remove(0);
        cb.glory = "mars";
        g.glory.put("mars", false);
        Site phobos = StateOps.site(g, "260");
        phobos.claim = 1;
        phobos.factory = true;
        StateOps.site(g, "261").claim = 1;
        b.stacks.out1 = Stack.at("260");
        b.stacks.out1.fts = 7;
        b.stacks.out1.cards.addAll(List.of(inst(g, used, "CG104", "b"), inst(g, used, "CB062", "b")));
        b.stacks.rocket = Stack.at("261");
        b.stacks.rocket.cards.addAll(List.of(cb, inst(g, used, "CT051", "w"), inst(g, used, "CX090", "w")));
        StateOps.syncRocketDry(b);
        FuelTrack.addFuel(b.stacks.rocket.fuel, 4, "water");
        b.hand = new ArrayList<>(List.of(take(used, "CB063"), take(used, "CF074")));
        b.aquas = 9;

        // Clara (ESA Space Unionists): lunar Factory with a Colony, LEO depot, a glitched Outpost at Vesta, a busted Apophis.
        Site luna = StateOps.site(g, "115");
        luna.claim = 2;
        luna.factory = true;
        luna.colony = true;
        luna.visited = new ArrayList<>(List.of(2));
        StateOps.site(g, "206").busted = true;
        CardInst ct054 = inst(g, used, "CT054", "w");
        CardInst cx080 = inst(g, used, "CX080", "w");
        CardInst rad = inst(g, used, "CD092", "w");
        rad.orient = "heavy";
        c.stacks.leo.cards.addAll(List.of(ct054, cx080, rad));
        c.stacks.out2 = Stack.at("365");
        c.stacks.out2.fts = 3;
        c.stacks.out2.glitch = true;
        c.stacks.out2.cards.addAll(List.of(inst(g, used, "CB067", "w"), inst(g, used, "CG111", "w")));
        StateOps.site(g, "365").claim = 2;
        c.glories.add(new Player.Glory("earth", 1));
        g.glory.put("earth", false);
        c.hand = new ArrayList<>(List.of(take(used, "CX091")));
        c.aquas = 4;

        for (List<String> deck : g.decks.values()) deck.removeIf(used::contains);
        g.cyclePos = 8;
        g.current = 0;
        g.turnNo = 25;
        g.log = new ArrayList<>();
        g.year = 8;
        Dice.log(g, "Demo position: year 9, start of the red season.", null, "system");
        Dice.log(g, "Amelia claims Nereus!", 0, "good");
        Dice.log(g, "Amelia ends the turn.", 0, "system");
        Dice.log(g, "The Sunspot Cube advances to space 9 (season red).", null, "system");
        Dice.log(g, "Event: Glitch - Clara: Outpost 2.", null, "event");
        g.year = 9;
        Dice.log(g, "Year 9 begins.", null, "system");
        Dice.log(g, "Bo builds a Factory on Phobos (C stock price now 8).", 1, "good");
        Dice.log(g, "Bo's Crew takes the Mars glory chit!", 1, "good");
        Dice.log(g, "Bo ends the turn.", 1, "system");
        Dice.log(g, "Clara founds a Colony on Luna: Shackleton polar rim!", 2, "good");
        Dice.log(g, "Clara ends the turn.", 2, "system");
        return g;
    }

    private static String take(Set<String> used, String id) {
        used.add(id);
        return id;
    }

    private static CardInst inst(GameState g, Set<String> used, String id, String side) {
        return CardInst.patent(take(used, id), side, Operations.newUid(g));
    }
}
