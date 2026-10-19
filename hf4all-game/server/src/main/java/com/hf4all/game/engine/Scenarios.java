package com.hf4all.game.engine;

import com.hf4all.game.model.CardInst;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.Player;
import com.hf4all.game.model.Site;
import com.hf4all.game.model.Stack;
import com.hf4all.game.model.Turn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Demo scenarios for testing the game's flows by hand: each is the demo position
 * (DemoFactory: Amelia, Bo - the 1st Player - and Clara) changed so that one
 * decision of the game is one click away. Where dice decide, the RNG is set so the
 * next rolls are known (a "loaded die", said in the text). Every scenario has a
 * check that its decision really is reachable (ScenariosTest).
 */
public final class Scenarios {

    private Scenarios() {}

    /** What the title screen lists: id, group, title and what to do. */
    public record Info(String id, String group, String title, String text) {}

    private record Def(Info info, Supplier<GameState> build, Consumer<GameState> check) {}

    private static final String LEO = "90";
    private static final String LEO_AEROBRAKE = "956";  // aerobrake Hazard next to LEO
    private static final String LEO_BURN = "91";        // the cycler burn between LEO and the Van Allen belts (flare-shielded)
    private static final String FLYBY = "44";           // Earth-zone flyby next to the aerobrake below
    private static final String AEROBRAKE = "98";       // aerobrake Hazard entered from that flyby
    private static final String OPEN_SPACE = "18";      // an Earth-zone Hohmann, unshielded against flares
    private static final String UNITAS = "364";         // size 4 Site whose exit is no lander burn
    private static final String VAN_ALLEN = "92";       // radiation belt
    private static final String PHOBOS = "260";         // Bo's Factory (C)
    private static final String NEREUS = "333";         // Amelia's Claim (V)
    private static final String LUNA = "115";           // Clara's Factory + Colony (S, size 9)
    private static final String VESTA = "365";          // Clara's glitched Outpost
    private static final String CERES = "353";
    private static final String CERES_L = "354";        // Lagrange Space next to Ceres, Minerva and Aegis
    private static final String KREUTZ = "619";
    private static final int AMELIA = 0, BO = 1, CLARA = 2;

    private static final List<Def> DEFS = new ArrayList<>();

    private static void add(String id, String group, String title, String text, Supplier<GameState> build, Consumer<GameState> check) {
        DEFS.add(new Def(new Info(id, group, title, text), build, check));
    }

    static {
        add("classic", "Start", "Mid-game demo",
                "The demo position: year 9, Amelia (NASA) to play, Bo is the 1st Player. Nothing staged.",
                DemoFactory::build, g -> {});

        /* ---------------------------------------------------------- Operations (I) */
        add("op-income", "Operations", "Income (I1)",
                "Amelia's Operation: Income - the Aquas from her Claims and Factories.",
                DemoFactory::build, g -> need(g.turn.op == null, "operation free"));
        add("op-auction", "Operations", "Research auction and a tie (I2)",
                "Patents tab ▸ Auction. Everyone has 6 Aquas: bid the same as another player and, as the initiator, pick the winner (I2e).",
                () -> { GameState g = DemoFactory.build(); for (Player p : g.players) p.aquas = 6; return g; },
                g -> need(g.players.stream().filter(p -> Operations.canBid(g, p.idx)).count() >= 2, "two bidders"));
        add("op-free-market", "Operations", "Free Market: sell (I3)",
                "Sell a Hand card for 3 Aquas, or the Black-Side Femtochemistry in Amelia's LEO stack at the D stock price.",
                () -> { GameState g = DemoFactory.build(); pl(g, AMELIA).stacks.leo.cards.add(card(g, "CF076", "b")); return g; },
                g -> need(Operations.blackSalePrice(g, last(pl(g, AMELIA).stacks.leo)) != null && !pl(g, AMELIA).hand.isEmpty(), "something to sell"));
        add("op-boost", "Operations", "Boost with a radiator (I4)",
                "Boost Hand cards to LEO: Salt-cooled Reflux Tube is a radiator - choose its orientation. Launch Fees: Amelia gets an Aqua for any boost.",
                () -> { GameState g = DemoFactory.build(); pl(g, AMELIA).aquas = 14; return g; },
                g -> need(pl(g, AMELIA).hand.contains("CD096"), "radiator in hand"));
        add("op-refuel", "Operations", "Site refuel: ISRU or an opponent's Factory (I5)",
                "Amelia's Rocket is on Phobos, Bo's Factory. Site Refuel: ISRU with the Sandworm, or Bo's Factory - tick “Bo gives permission” (no Hijack: Amelia is not Felonious).",
                () -> {
                    GameState g = DemoFactory.build();
                    rocket(g, AMELIA, PHOBOS, 3, card(g, "CB059", "b"));
                    return g;
                },
                g -> {
                    List<Operations.RefuelOpt> o = Operations.refuelOptions(g);
                    need(o.stream().anyMatch(x -> x.kind.equals("isru")) && o.stream().anyMatch(x -> x.kind.equals("factory") && x.owner != null && x.owner == BO), "ISRU + Bo's Factory");
                });
        add("op-refuel-air", "Operations", "Air-Eater refuel and FINAO (I5c)",
                "Amelia's Rocket sits in the aerobrake next to LEO with an Air-Eater. Site Refuel: Hazard Roll or pay FINAO.",
                () -> {
                    GameState g = DemoFactory.build();
                    rocket(g, AMELIA, LEO_AEROBRAKE, 4, card(g, "CG115", "b"), card(g, "CT045", "b"));
                    return g;
                },
                g -> need(Operations.refuelOptions(g).stream().anyMatch(x -> x.kind.equals("airEater")), "air-eater option"));
        add("op-prospect", "Operations", "Prospect with a raygun (I6)",
                "Amelia's Rocket is at the Lagrange point by Ceres with a Lorentz-Propelled Microprobe (raygun, Nanites re-roll): prospect several Sites at once and choose which failure to re-roll.",
                () -> {
                    GameState g = DemoFactory.build();
                    rocket(g, AMELIA, CERES_L, 3, card(g, "CB067", "b"));
                    return g;
                },
                g -> need(Operations.prospectOptions(g).stream().anyMatch(o -> o.raygun && o.targets.size() >= 2 && Boolean.TRUE.equals(o.reroll)), "raygun with 2+ Sites"));
        add("op-prospect-luna", "Operations", "Prospect Luna: the 1st Player's permission (I6.1)",
                "Amelia's Rocket has landed on Luna (Shackleton, unclaimed here) with a Sandworm buggy. Prospect: tick “Bo gives permission” - Bo is the 1st Player.",
                () -> {
                    GameState g = DemoFactory.build();
                    Site s = StateOps.site(g, LUNA);
                    s.claim = null;
                    s.factory = false;
                    s.colony = false;
                    rocket(g, AMELIA, LUNA, 3, card(g, "CB059", "b"));
                    return g;
                },
                g -> need(g.firstPlayer != g.current && Operations.prospectOptions(g).stream().anyMatch(o -> o.targets.contains(LUNA)), "Luna target"));
        add("op-industrialize", "Operations", "Industrialize (I7)",
                "Amelia's Rocket is on her Claim on Nereus with two robonauts and two refineries: choose which pair builds the Factory (they are decommissioned).",
                () -> {
                    GameState g = DemoFactory.build();
                    rocket(g, AMELIA, NEREUS, 3, card(g, "CB059", "b"), card(g, "CB062", "b"), card(g, "CF070", "w"), card(g, "CF073", "b"));
                    return g;
                },
                g -> need(Operations.industrializeOptions(g).stream().anyMatch(o -> o.robos.size() >= 2 && o.refs.size() >= 2), "2 robonauts + 2 refineries"));
        add("op-et", "Operations", "ET production: own or an opponent's Factory (I8)",
                "Amelia has a Factory on Nereus (V). ET Production: Ablative Plate there, or Salt-cooled Reflux Tube (C) at Bo's Phobos Factory with his permission - and a radiator orientation.",
                () -> {
                    GameState g = DemoFactory.build();
                    StateOps.site(g, NEREUS).factory = true;
                    return g;
                },
                g -> {
                    List<Operations.EtOpt> o = Operations.etOptions(g);
                    need(o.stream().anyMatch(x -> x.owner == null && !x.cards.isEmpty()) && o.stream().anyMatch(x -> x.owner != null && !x.cards.isEmpty()), "own + opponent Factory");
                });
        add("op-delivery", "Operations", "Delivery (I9)",
                "Amelia's Outpost at her Nereus Factory holds a Black-Side card and FTs: deliver it to LEO.",
                () -> {
                    GameState g = DemoFactory.build();
                    StateOps.site(g, NEREUS).factory = true;
                    Stack o = Stack.at(NEREUS);
                    o.cards.add(card(g, "CF076", "b"));
                    o.fts = 6;
                    pl(g, AMELIA).stacks.out1 = o;
                    return g;
                },
                g -> need(Operations.deliveryOptions(g).stream().anyMatch(o -> o.affordable), "affordable delivery"));
        add("op-mine-revival", "Operations", "Mine Revival: a card Operation (Termite Nest)",
                "Amelia's Rocket stands on busted Unitas (size 4) with a Termite Nest powered by Ultracold Neutrons. The Mine Revival Operation removes the busted disk and places her Claim.",
                () -> {
                    GameState g = DemoFactory.build();
                    Site s = StateOps.site(g, UNITAS);
                    s.claim = null;
                    s.factory = false;
                    s.colony = false;
                    s.busted = true;
                    rocket(g, AMELIA, UNITAS, 3, card(g, "CF078", "b"), card(g, "CX083", "b"));
                    return g;
                },
                g -> need(!Operations.mineRevivalOptions(g).isEmpty(), "Mine Revival option"));
        add("op-jellybots", "Operations", "Industrialize as a free action (Solid Flame JELLYBOTS)",
                "Amelia's Rocket is on her Claim on Nereus with a Nanobot, a Solid Flame refinery (JELLYBOTS) and Ultracold Neutrons: industrializing there is a free action (Free actions ▸ Industrialize (Jellybots)), so the Operation stays unused.",
                () -> {
                    GameState g = DemoFactory.build();
                    rocket(g, AMELIA, NEREUS, 3, card(g, "CB062", "b"), card(g, "CF079", "b"), card(g, "CX083", "b"));
                    return g;
                },
                g -> need(Operations.industrializeOptions(g).stream().anyMatch(o -> Boolean.TRUE.equals(o.free)), "free industrialization"));

        /* ------------------------------------------------------------ Movement (H) */
        add("mv-engine", "Movement", "Engine builder: choose the supports (J1c)",
                "Amelia's Rocket has thrusters needing reactors and generators. Move ▸ Construct new engine: the Ablative Plate can fire with two different reactors.",
                () -> {
                    GameState g = DemoFactory.build();
                    rocket(g, AMELIA, LEO, 6, card(g, "CT052", "w"), card(g, "CX086", "w"), card(g, "CX091", "w"), card(g, "CG114", "b"),
                            card(g, "CT051", "w"), card(g, "CX090", "w"));
                    return g;
                },
                g -> {
                    int i = index(pl(g, AMELIA).stacks.rocket, "CT052");
                    Movement.Plan pl = Movement.plan(g, i, false, new Movement.PlanOpts());
                    need(pl.slots.stream().anyMatch(s -> s.options.size() >= 2), "a support choice");
                });
        add("mv-hazard", "Movement", "Aerobrake Hazard: FINAO or roll (H7)",
                "Amelia's Rocket coasts at a flyby next to an aerobrake. Move with the Crew into the aerobrake: pay FINAO or roll - loaded die: the roll is a 1 and the Rocket explodes.",
                () -> { GameState g = DemoFactory.build(); pl(g, AMELIA).stacks.rocket.node = FLYBY; rig(g, 1); return g; },
                g -> {
                    Movement.start(g, 0, false, null);
                    need(Movement.options(g).stream().anyMatch(o -> o.to.equals(AEROBRAKE) && o.legal && o.hazard != null), "hazard option");
                    Movement.step(g, AEROBRAKE, null);
                    need(g.booms != null && !g.booms.isEmpty(), "the Rocket is lost");
                });
        add("mv-belt", "Movement", "Radiation belt roll (H9)",
                "Move with Re Solar Moth (net 3) through the burn into the Van Allen belt. Loaded die: the Belt Roll is a 6 (+2 red season) - weak cards fry.",
                () -> { GameState g = DemoFactory.build(); rig(g, 6); return g; },
                g -> {
                    Movement.start(g, index(pl(g, AMELIA).stacks.rocket, "CT048"), false, null);
                    Movement.step(g, LEO_BURN, null);
                    Movement.step(g, VAN_ALLEN, null);
                    need(g.booms != null && !g.booms.isEmpty(), "cards lost in the belt");
                });
        add("mv-factory", "Movement", "Liftoff with an opponent's Factory (H6c, N5)",
                "Amelia's Rocket is on Unitas (size 4), where Clara has a Factory. Move with Re Solar Moth (net 1 out here): the liftoff needs Clara's Factory - tick her permission, then pay FINAO or take the Hazard Roll.",
                () -> {
                    GameState g = DemoFactory.build();
                    Site s = StateOps.site(g, UNITAS);
                    s.claim = CLARA;
                    s.factory = true;
                    pl(g, AMELIA).stacks.rocket.node = UNITAS;
                    return g;
                },
                g -> {
                    Movement.start(g, index(pl(g, AMELIA).stacks.rocket, "CT048"), false, null);
                    need(Movement.options(g).stream().anyMatch(o -> Boolean.TRUE.equals(o.factoryLiftoff) && o.factoryOwner != null && o.factoryOwner == CLARA), "factory liftoff");
                });
        add("mv-push", "Movement", "Negotiated push from a Powersat (N4a)",
                "Clara has the Powersat privilege. Move: tick “Clara gives permission” and see Re Solar Moth's net thrust change.",
                DemoFactory::build,
                g -> {
                    Movement.PlanOpts push = new Movement.PlanOpts();
                    push.push = true;
                    need(StateOps.hasPowersat(g, pl(g, CLARA)) && Movement.plan(g, index(pl(g, AMELIA).stacks.rocket, "CT048"), false, push).ok, "push plan");
                });

        /* -------------------------------------------------------- Free actions (G) */
        add("fa-fuel", "Free actions", "Fuel and cargo (G1, G2)",
                "Amelia's Rocket and Outpost 1 are in LEO with FTs and a heavy radiator: load fuel, FT ↔ fuel, jettison, turn the radiator light, swap the Rocket with the Outpost.",
                () -> {
                    GameState g = DemoFactory.build();
                    Player a = pl(g, AMELIA);
                    CardInst rad = card(g, "CD093", "w");
                    rad.orient = "heavy";
                    a.stacks.rocket.cards.add(rad);
                    a.stacks.rocket.fts = 4;
                    StateOps.syncRocketDry(a);
                    Stack o = Stack.at(LEO);
                    o.cards.add(card(g, "CG119", "b"));
                    o.fts = 3;
                    a.stacks.out1 = o;
                    return g;
                },
                g -> need(pl(g, AMELIA).stacks.rocket.ftsOr0() > 0 && pl(g, AMELIA).stacks.out1 != null, "FTs and an Outpost"));
        add("fa-glory", "Free actions", "Take a glory chit (G5)",
                "Amelia's Crew has just landed on Ceres, which no Human visited: Free actions ▸ Take glory.",
                () -> {
                    GameState g = DemoFactory.build();
                    pl(g, AMELIA).stacks.rocket.node = CERES;
                    g.turn.gloryEligible = CERES;
                    return g;
                },
                g -> need(Boolean.TRUE.equals(g.glory.get("ceres")) && CERES.equals(g.turn.gloryEligible), "glory eligible"));
        add("fa-colony", "Free actions", "Build a Colony (G3)",
                "Amelia's Crew is at her Factory on Nereus: Free actions ▸ Build colony (the Crew is decommissioned).",
                () -> {
                    GameState g = DemoFactory.build();
                    StateOps.site(g, NEREUS).factory = true;
                    pl(g, AMELIA).stacks.rocket.node = NEREUS;
                    return g;
                },
                g -> need(StateOps.site(g, NEREUS).factory && NEREUS.equals(StateOps.crewLocation(g, pl(g, AMELIA)).node()), "crew at own factory"));
        add("fa-claim-jump", "Free actions", "Claim jump (Felony, G4)",
                "Bo (Felonious) has flown his Crew to Amelia's Claim on Nereus; no Human of hers is there: Free actions ▸ Claim jump.",
                () -> {
                    GameState g = DemoFactory.build();
                    pl(g, BO).stacks.rocket.node = NEREUS;
                    turnOf(g, BO);
                    return g;
                },
                g -> need(StateOps.canFelony(g, pl(g, BO)) && NEREUS.equals(StateOps.crewLocation(g, pl(g, BO)).node()), "Bo's crew on Nereus"));
        add("fa-murder", "Free actions", "Murder / Suicide (Felony)",
                "Bo's Rocket at Deimos carries his Crew and Amelia's: Free actions ▸ Murder/Suicide - decommission either.",
                () -> {
                    GameState g = DemoFactory.build();
                    Player a = pl(g, AMELIA);
                    CardInst crew = a.stacks.rocket.cards.remove(0);
                    StateOps.syncRocketDry(a);
                    pl(g, BO).stacks.rocket.cards.add(crew);
                    StateOps.syncRocketDry(pl(g, BO));
                    turnOf(g, BO);
                    return g;
                },
                g -> need(Actions.murderOptions(g).stream().filter(o -> o.ok).count() >= 2, "two victims"));
        add("fa-glitch-repair", "Free actions", "Glitch repair (G7)",
                "Clara's Crew has reached her glitched Outpost on Vesta: Free actions ▸ Repair glitch.",
                () -> {
                    GameState g = DemoFactory.build();
                    Player c = pl(g, CLARA);
                    CardInst crew = c.stacks.leo.cards.remove(0);
                    c.stacks.out2.cards.add(0, crew);
                    turnOf(g, CLARA);
                    return g;
                },
                g -> need(pl(g, CLARA).stacks.out2.glitch && StateOps.humansAt(g, VESTA).contains(CLARA), "human at the glitch"));
        add("fa-dirt", "Free actions", "Dirt refuel (G1c)",
                "Amelia's Rocket is at her Nereus Factory with a Pulsed Plasmoid (dirt thruster): Free actions ▸ Dirt refuel.",
                () -> {
                    GameState g = DemoFactory.build();
                    StateOps.site(g, NEREUS).factory = true;
                    rocket(g, AMELIA, NEREUS, 2, card(g, "CT047", "b"));
                    return g;
                },
                g -> need(Actions.dirtRefuelInfo(g).ok, "dirt refuel"));
        add("fa-mooncable", "Free actions", "Mooncable (NASRDA privilege)",
                "Amelia plays NASRDA Astronauts here: her Rocket in LEO has a dirt thruster - Free actions ▸ Mooncable.",
                () -> {
                    GameState g = DemoFactory.build();
                    Player a = pl(g, AMELIA);
                    a.crew = "CR";
                    a.face = 1;
                    a.faction = "NASRDA Astronauts";
                    a.privilege = "mooncable";
                    a.stacks.rocket.cards.set(0, CardInst.crew("CR", 1, a.stacks.rocket.cards.get(0).uid));
                    a.stacks.rocket.cards.add(card(g, "CT047", "b"));
                    StateOps.syncRocketDry(a);
                    return g;
                },
                g -> need(StateOps.privilegeActive(g, pl(g, AMELIA), "mooncable"), "mooncable"));
        add("fa-heroism", "Free actions", "Heroism vote (L c)",
                "Free actions ▸ Heroism vote: nominate a player; the table votes.",
                DemoFactory::build,
                g -> need(g.config.heroism && g.heroism.containsValue(null), "unawarded chits"));
        add("fa-discard", "Free actions", "Discard your Factory or Claim (G6a)",
                "Click Nereus on the map: Amelia may discard her Factory cube or her Claim disk there.",
                () -> { GameState g = DemoFactory.build(); StateOps.site(g, NEREUS).factory = true; return g; },
                g -> need(StateOps.site(g, NEREUS).claim == AMELIA, "own claim"));

        /* ------------------------------------------------------ Year end, events (K) */
        add("ev-year-end", "Year end & events", "End of the cycle: seniority and the 1st Player",
                "Amelia is the last player of the year: End turn. The Sunspot Cube passes START, a Seniority Disk is removed and the new year starts with Bo, the 1st Player.",
                () -> { GameState g = DemoFactory.build(); g.cyclePos = 11; g.seniority = 3; return g; },
                g -> {
                    int year = g.year;
                    Events.endTurn(g);
                    need(g.seniority == 2 && g.cyclePos == 0 && g.year == year + 1 && g.current == g.firstPlayer && g.gameOver == null, "new year with the 1st Player");
                });
        add("ev-glitch", "Year end & events", "Glitch event: choose the stack (K2b)",
                "End turn: loaded Event Roll 3 - Glitch. Amelia's two Outposts away from Humans tie: she chooses which one is glitched.",
                () -> {
                    GameState g = DemoFactory.build();
                    Player a = pl(g, AMELIA);
                    Stack o1 = Stack.at(LEO_BURN), o2 = Stack.at(LEO_AEROBRAKE);
                    o1.cards.addAll(List.of(card(g, "CG119", "b"), card(g, "CD097", "w")));
                    o2.cards.addAll(List.of(card(g, "CF071", "w"), card(g, "CB065", "w")));
                    a.stacks.out1 = o1;
                    a.stacks.out2 = o2;
                    g.cyclePos = 8;
                    rig(g, 3);
                    return g;
                },
                g -> { Events.endTurn(g); need(g.pending != null && g.pending.type.equals("glitchEvent"), "glitch tie"); });
        add("ev-pad", "Year end & events", "Pad explosion: choose the lost card (K2c)",
                "End turn: loaded Event Roll 4 - Pad Explosion. Clara and Amelia each have tied heaviest cards in LEO: each chooses which one is lost.",
                () -> { GameState g = DemoFactory.build(); g.cyclePos = 8; rig(g, 4); return g; },
                g -> { Events.endTurn(g); need(g.pending != null && g.pending.type.equals("padExplosion") && g.pending.queue.size() == 2, "two pad choices"); });
        add("ev-budget", "Year end & events", "Budget cuts: everyone discards (K2)",
                "End turn: loaded Event Roll 5 in the yellow season - Budget Cuts. Each player with a Hand discards a card of their choice, in turn order.",
                () -> { GameState g = DemoFactory.build(); g.cyclePos = 4; rig(g, 5); return g; },
                g -> { Events.endTurn(g); need(g.pending != null && g.pending.type.equals("budgetCuts"), "budget cuts"); });
        add("ev-flare", "Year end & events", "Solar flare (K2d)",
                "Amelia's Rocket waits in open space by Earth. End turn: loaded Event Roll 5 in the red season and CME severity 6 - unshielded cards burn.",
                () -> {
                    GameState g = DemoFactory.build();
                    pl(g, AMELIA).stacks.rocket.node = OPEN_SPACE;
                    g.cyclePos = 6;
                    rig(g, 5, 6);
                    return g;
                },
                g -> { Events.endTurn(g); need(g.booms != null && !g.booms.isEmpty(), "flare losses"); });
        add("ev-anarchy", "Year end & events", "Anarchy (K2e)",
                "End turn: loaded Event Roll 5 in the blue season - Anarchy: everyone is Felonious until the blue season ends.",
                () -> { GameState g = DemoFactory.build(); g.cyclePos = 10; rig(g, 5); return g; },
                g -> { Events.endTurn(g); need(g.anarchy, "anarchy"); });
        add("ev-kreutz", "Year end & events", "Kreutz sungrazer melts",
                "Amelia has an Outpost on the Kreutz Sungrazer. End turn: the yellow season ends, the comet passes perihelion and the Outpost is lost.",
                () -> {
                    GameState g = DemoFactory.build();
                    Stack o = Stack.at(KREUTZ);
                    o.cards.addAll(List.of(card(g, "CB062", "b"), card(g, "CG119", "b")));
                    o.fts = 3;
                    pl(g, AMELIA).stacks.out1 = o;
                    g.cyclePos = 5;
                    return g;
                },
                g -> { Events.endTurn(g); need(g.booms != null && g.booms.stream().anyMatch(b -> "kreutz sungrazer".equals(b.why())), "comet losses"); });
        add("ev-game-end", "Year end & events", "End of the game and final scores (M)",
                "The last Seniority Disk is on the track: Amelia ends the turn and the game ends with the final scores.",
                () -> { GameState g = DemoFactory.build(); g.cyclePos = 11; g.seniority = 1; return g; },
                g -> { Events.endTurn(g); need(g.gameOver != null, "game over"); });

        /* ----------------------------------------------------------- Negotiation (N) */
        add("deal", "Negotiation", "A deal between players (N)",
                "Bo's Rocket is in LEO next to Amelia's: 🤝 Deal - trade Aquas, Hand cards, cards and FTs between the Rockets, a whole stack or a Claim.",
                () -> { GameState g = DemoFactory.build(); pl(g, BO).stacks.rocket.node = LEO; return g; },
                g -> need(LEO.equals(pl(g, BO).stacks.rocket.node) && LEO.equals(pl(g, AMELIA).stacks.rocket.node), "rockets together"));
    }

    /* ---------------------------------------------------------------- access */

    public static List<Info> list() {
        List<Info> out = new ArrayList<>();
        for (Def d : DEFS) out.add(d.info());
        return out;
    }

    public static Info info(String id) { return def(id).info(); }

    /** A new game in the scenario's position, with the scenario noted in the log. */
    public static GameState build(String id) {
        Def d = def(id);
        GameState g = d.build().get();
        if (!d.info().id().equals("classic")) Dice.log(g, "Scenario: " + d.info().title() + " - " + d.info().text(), null, "system");
        return g;
    }

    /** Runs the scenario's check on g (tests): throws when its decision is not reachable. */
    static void check(String id, GameState g) { def(id).check().accept(g); }

    private static Def def(String id) {
        for (Def d : DEFS) if (d.info().id().equals(id)) return d;
        throw new RuleError("Unknown demo scenario " + id + ".");
    }

    /* --------------------------------------------------------------- helpers */

    private static void need(boolean ok, String what) {
        if (!ok) throw new IllegalStateException("Scenario not ready: " + what);
    }

    private static Player pl(GameState g, int i) { return g.players.get(i); }

    private static CardInst last(Stack s) { return s.cards.get(s.cards.size() - 1); }

    private static int index(Stack s, String id) {
        for (int i = 0; i < s.cards.size(); i++) if (id.equals(s.cards.get(i).id)) return i;
        throw new IllegalStateException("No " + id + " in the stack.");
    }

    /** A card taken out of every deck, Hand and stack, so it exists once, as a new instance. */
    private static CardInst card(GameState g, String id, String side) {
        for (List<String> deck : g.decks.values()) deck.remove(id);
        for (Player p : g.players) {
            p.hand.remove(id);
            for (String k : StateOps.STACKS) {
                Stack s = p.stacks.get(k);
                if (s != null && s.cards.removeIf(c -> id.equals(c.id)) && k.equals("rocket")) StateOps.syncRocketDry(p);
            }
        }
        return CardInst.patent(id, side, Operations.newUid(g));
    }

    /** Player pi's Rocket at node: their Crew, the given cards and `tanks` of water. */
    private static void rocket(GameState g, int pi, String node, int tanks, CardInst... cards) {
        Player p = pl(g, pi);
        StateOps.CrewLoc at = StateOps.crewLocation(g, p);
        CardInst crew = g.players.get(at.pi()).stacks.get(at.key()).cards.remove(at.idx());
        Stack s = Stack.at(node);
        s.cards.add(crew);
        s.cards.addAll(Arrays.asList(cards));
        p.stacks.rocket = s;
        StateOps.syncRocketDry(p);
        FuelTrack.addFuel(s.fuel, tanks, "water");
        for (Player q : g.players) StateOps.cleanupStacks(g, q);
    }

    private static void turnOf(GameState g, int pi) {
        g.current = pi;
        g.turn = Turn.fresh();
    }

    /** Set the RNG so the next d6 rolls come up as given (a loaded die). */
    private static void rig(GameState g, int... d6) {
        GameState t = new GameState();
        for (long r = 1; r < 50_000_000L; r++) {
            t.rng = r;
            boolean ok = true;
            for (int v : d6) if (1 + Dice.nextInt(t, 6) != v) { ok = false; break; }
            if (ok) { g.rng = r; return; }
        }
        throw new IllegalStateException("No RNG state rolls " + Arrays.toString(d6));
    }
}
