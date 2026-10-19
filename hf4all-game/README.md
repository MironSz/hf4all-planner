# High Frontier 4 All - hot-seat browser table

A playable browser version of the **High Frontier 4 All core game** (no modules) for
several players sharing one screen: every player takes their turn at the same browser
window, one after another.

This directory is a **separate project** with its own Java game server (`server/`). The
server holds every game and runs every rule; the browser only renders the state and sends
commands. The server reuses the mission planner by compiling its sources into its own
build:
- the map graph (`SolarMap`, `MapLoader`, `MapNode`, `SiteData`) and seasons (`Season`);
- the movement rules (`planner.rules.MoveRules`, shared with the planner's `Pathfinder`):
  net thrust, burn and fuel costs (H5), lanes, Pivots and U-turns (H4), flyby / Oberth /
  Mag Sail bonuses (H8), Venus and synodic gates, powered / aerobrake landing and liftoff
  (H6), where a move may stop, and the Belt Roll (H10). Where the game applies a rule
  differently from the planner, `MoveRules` takes the variant as a parameter and the game
  passes its own (the constants at the top of `Movement`): no thrust gate on lander burns,
  the Europa / Io aerobrake fix, a one-way path keeps the Bonus Burns, sails burn up in
  aerobrakes only, an actual die for the Belt Roll. Pivots are paid the same way in both:
  the Bonus Pivot if one is left (always spent), else the move's Bonus Burns, else paid
  burns;
- the engine model (`EngineSpec`): the activated thruster of a move is built as one;
- the Fuel Strip (`FuelStrip`): geometry, chit position, weight classes and the jettison
  ladder (the "jettison N steps for the lighter class" hint). The browser gets the same
  table from the server (`data/fuelrules.js`, generated from `FuelStrip`);
- exact fractions (`Fraction`).

The planner was changed only by moving those rules out of `Pathfinder` into `MoveRules`; its
results, search statistics and tests are unchanged. Planner-only parts stay out of the
game: the route search itself and its turn-start boost menu (`ThrustBoost`: afterburn and
jettison chosen automatically), and the solar shut-down threshold (the game reads it from
its zones: no solar number in the Neptune zone, while Uranus' -5 is an ordinary modifier).

---

## Running it

Start the game server (Java 21+, Maven) inside `hf4all-game/server`:

```
mvn exec:java@run                    # http://localhost:8097/
mvn exec:java@run -Dexec.args=9000   # another port
```

and open **http://localhost:8097/**. The server serves the browser client from this
directory and keeps the games as JSON files in `server/games/` (not in git), so a game
survives a restart; the title screen lists them (*Continue a game*). `POST /api/stop`
(local requests only) stops the server. The page no longer works from `file://`.

From the title screen choose **New hot-seat game**, or **🎬 Demo scenarios…** (also in the
in-game Menu) to land straight in a year-9 position with factories, outposts, glories and a
fuelled rocket - either the plain mid-game demo or one of the scenarios below.

### Demo scenarios (testing the flows by hand)

Each scenario is the demo position (Amelia to play, Bo the 1st Player, Clara) changed so that
one decision or flow of the game is one click away; it opens with a short note on what to do.
Where dice decide, the die is loaded: the RNG state is set so the next rolls are known, and
the note says so. The server builds them (`server/.../engine/Scenarios.java`) and
`ScenariosTest` checks that each one really reaches its decision.

| Group | Scenarios |
|---|---|
| Operations | income; research auction with a tie (equal Aquas, the initiator picks); free market (Hand card / Black-Side card in LEO); boost with a radiator orientation (Launch Fees); site refuel - ISRU or an opponent's Factory with permission; Air-Eater refuel with FINAO or the Hazard Roll; raygun prospect of several Sites with the Nanites re-roll; prospecting Luna with the 1st Player's permission; industrialize choosing a robonaut and a refinery; ET production at your own or an opponent's Factory; delivery; Mine Revival (Termite Nest, a card Operation); industrializing as a free action (Solid Flame JELLYBOTS) |
| Movement | the engine builder with a support choice; aerobrake Hazard with FINAO or a loaded roll of 1 (the Rocket explodes); a radiation belt roll that fries weak cards; liftoff with an opponent's Factory (permission + Hazard Roll); a negotiated Powersat push |
| Free actions | fuel and cargo (load, FT ↔ fuel, jettison, light radiator, swap); take a glory chit; build a colony; claim jump and Murder/Suicide (Bo, Felonious); glitch repair (Clara); dirt refuel; Mooncable; heroism vote; discarding your Factory / Claim |
| Year end & events | the end of the Sunspot Cycle (a Seniority Disk goes, the 1st Player starts the year - the 1st Player does not change in the core game); Glitch event tie; pad explosion ties (Clara, then Amelia, choose the lost card); budget cuts; solar flare; Anarchy; the Kreutz sungrazer melting; the end of the game |
| Negotiation | a deal with both Rockets in LEO |

### Original card art and game graphics (one-time setup)

The cards are shown with their original artwork when `assets/cards/` exists, and the
playmat, Fuel Strip, Dry/Wet chits, map pieces, beads, dice, glory chits and faction logos
use the original graphics when `assets/gfx/` exists. The images
are © ION Game Design / Sierra Madre Games and are **not stored in git**
(`.gitignore`); recreate them locally from the official Vassal module ("High Frontier 4
All" by Stefano Tine', released with permission from ION/SMG):

```
python tools/extract_cards.py --download
```

This downloads `High_frontier4_1.6.1.vmod` (75 MB) from the Vassal module library into
`.cache/`, checks its SHA-256, and writes 166 card images (77 patent cards x 2 sides + 6
Crew cards x 2 factions) plus 92 graphics into `assets/gfx/`. If you already have the
module file: `python tools/extract_cards.py path/to/High_frontier4_1.6.1.vmod`. Without the
images the game draws everything itself; *Menu → Original game graphics* switches between
the two.

`data/playmat.js` (in git: coordinates only, no artwork) says where each stack slot (and,
unused since the playmat switched strips, each Fuel Strip position and Net Thrust space)
sits on those graphics. It was generated by
`python tools/build_playmat.py <module>` from the module's Playmat board definition.

The Fuel Strip on the playmat, in the ship dock and in the fuel dialogs is the planner's cut-out of the
rulebook's strip (`assets/fuel-strip.png`, the planner's `static/docs/fuel-strip-transparent.png`)
with the position of every one of its 57 black-line steps (`data/fuelstrip.js`, from the
planner's chit-editor data `static/data/chits-edited.json`). Both are in git, like the
planner's own copies, and are refreshed with `python tools/build_fuelstrip.py` (reads the
planner, writes only into `hf4all-game/`). The Dry and Wet Mass chits sit on those steps.

Developer shortcuts (URL parameters):

| URL | Opens |
|---|---|
| `?demo=1` | a new copy of the demo position (made by the server) |
| `?demo=<scenario id>` (e.g. `ev-pad`, `mv-hazard`, `op-et`) | a demo scenario with its note; ids from `GET /api/scenarios` |
| `?demo=1&tab=hand` (`patents`, `players`, `log`, `help`; `mat` opens the playmat) | demo on a side-panel tab |
| `?demo=1&open=move` | demo with a movement in progress (move options on the map) |
| `?demo=1&open=movePrep` / `auction` / `boost` / `card` / `site` / `score` / `setup` / `library` / `spaces` / `negotiate` | demo with that dialog or screen open |
| `?demo=1&open=move2` | two steps into a movement, with the "after this step" preview on the next Space |
| `?demo=1&open=handover` / `keys` | hand-over screen with the "since your last turn" digest / keyboard help |
| `?demo=1&open=playmat` (`&p=1`) | the playmat of the active player (of player 1) |
| `?demo=1&...&gfx=0` | any of the above with the original graphics switched off (drawn fallbacks) |
| `?demo=1&open=murder` | Murder/Suicide dialog (turns on Anarchy and parks Amelia's Rocket on Nereus first) |

Tests: `mvn test` in `server/`:

- **golden-trace parity**: about 8,000 engine calls recorded from the original browser
  rules engine (seeded random games with 2-5 players, the demo position with Anarchy
  switched on and off, and the scripted checks of the former smoke test) are replayed on
  the Java engine from the same state with the same arguments; the resulting state
  (including every log line), return value or rule-error message must be identical;
- **map parity**: the map built on the planner's `SolarMap` gives the same nodes, names,
  routes, lane labels, synodic gates and Adjacent Sites as the browser's `mapgraph.js`;
- **demo parity** and an **HTTP test** of the API (new game, commands, 409 / 422 / 403,
  undo, dice making undo final, queries, export / import, restart persistence);
- **rule fixes** (`RuleFixesTest`): rules the first port got wrong, found in a later review
  of the whole rulebook - one scenario each (lander burns before an aerobrake, no
  aerobrake on Europa / Io, factory refuel into a new Outpost, no fuel lost past Wet 32,
  Glitch Roll on all Colocated stacks, Glitch event with Colocated Humans and ties,
  cycler flare shielding, Air-Eater rounding, J5d modifiers) and `MinorRuleFixesTest`
  for the smaller ones (Neptune push, Powersat in Anarchy, the Luna Felony, the kreutz
  sungrazer's Claim, Claim disks and Factory cubes, raygun range, pad explosion ties,
  Nanites re-roll choice, heroism timing, selling from the Bank, ET production into a
  glitched Rocket, Fogg's heavy radiators, Heatsink Fountains in Operations);
- **card Abilities** (`CardAbilitiesTest`): the review of every printed Ability - Mine
  Revival, Jellybots, the Magnetoshell's industrialization ban, flare / belt immunity
  only while Operational, Divining Nubots and Scoop on Colocated ISRU cards, the
  Atmospheric Scoop's Adjacent Sites, Scavenging on ISRU and Factory refuels, Project
  Valkyrie's Felony, the Magnetocaloric
  Refrigerator cooling its own generator.

**Planner cross-check** (`server/src/test/java/.../engine/PlannerCrossCheck.java`, a
program, not part of `mvn test`): Rockets are assembled in the game, their engines typed
into the planner (`EngineSpec`, Dry Mass, fuel steps, start Space, season) and every route
the planner returns is replayed in the game step by step - the planner's engine,
afterburn and jettison per turn, the same route through the bend points, each move ending
where the planner ends a turn, the same fuel at the end. Hazards are passed with FINAO,
dice are loaded to 1, a new turn just advances the Sunspot Cube (no events), and fuel the
game saved is jettisoned at a turn start (a free action) to match the planner's weight
class. Belt Rolls do not decide whether a route works: the planner reports only the worst
roll a route can meet (`worstRadRoll`, by design), so cards a Belt Roll destroys are put
back, and the planner's `worstRadRoll` is compared with the game's worst roll (a 6) on the
same route instead. It prints, per case, how many routes work and, for the rest, the root
cause.
After `mvn -q test-compile`, from `server/`:

```bash
java -Xmx8g -Dplanner.search.max.turns=4 -cp "target/classes;target/test-classes;../../src/main/resources;$HOME/.m2/repository/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar" com.hf4all.game.engine.PlannerCrossCheck
```

The fixtures live in `server/src/test/resources/fixtures/` (`map.json`, `demo.json`,
`traces/*.json.gz`). They were recorded once from the original browser engine, which has
since been removed: the Java server is the only implementation of the rules, and the
fixtures stay as frozen regression tests. A trace record is
`{fn, args, before, after, result, error}` (`before` = `"$prev"` for the previous
`after`, `after` = `"$same"` when nothing changed). The 30 records whose outcome changed
on purpose with those rule fixes are listed, with the reason, in
`TraceReplayTest.RULE_FIXES`: they are still replayed but not compared.

---

## Project layout

```
hf4all-game/
  index.html            entry point (loads everything below in order)
  css/game.css          the whole look: dark "mission control" theme, cards, modals, HUD
  assets/board.jpg      the HF4A map image (same file the planner uses)
  assets/cards/         original card images - NOT in git, made by tools/extract_cards.py
  assets/gfx/           original playmat / chit / piece / logo graphics - NOT in git, same tool
  assets/fuel-strip.png the Fuel Strip cut out of the rulebook (same file the planner uses)
  data/
    map.js              GENERATED graph: 1486 nodes, edges, Hohmann lane labels, zones
    cards.js            the 77 core patent cards + 6 Crew cards, transcribed (strict JSON)
    playmat.js          GENERATED positions on the original playmat graphics (Fuel Strip steps etc.)
    fuelstrip.js        GENERATED positions of the 57 steps on assets/fuel-strip.png (from the planner)
    fuelrules.js        (served by the game server, not a file) the strip's intervals and weight classes from FuelStrip
    rules.js            zones, glory/heroism chits, sunspot cycle, stock prices, privileges
  js/
    util.js             namespace, formatting helpers
    fuelstrip.js        Fuel Strip geometry for drawing (labels, positions, classes)
    mapgraph.js         map geometry and names for drawing (routes through bend points)
    cards.js            card faces and stats for display
    state.js            read-only helpers over the state (stack positions, cargo mass, ...)
    net/api.js          HTTP client of the game server
    net/remote.js       the engine as seen by the browser: commands are recorded and sent
                        to the server, queries answer from the server's view
    ui/*.js             app controller, board, side panel, dialogs, negotiation screen, setup, cards, demo,
                        ship dock + drag and drop (dock.js), minimap, turn assistant (assist.js),
                        live auction (auction.js), playmat view (playmat.js), original graphics (gfx.js)
  UI-DESIGN.md          interface audit, design decisions and further UI proposals
  tools/
    build_map.py        regenerates data/map.js from the planner data (read-only access)
    extract_cards.py    recreates assets/cards/ and assets/gfx/ from the official Vassal module
    build_playmat.py    regenerates data/playmat.js from the module's Playmat board
    build_fuelstrip.py  copies the planner's Fuel Strip image + step positions (read-only access)
  server/               the Java game server (Maven project, see below)
    pom.xml             compiles the planner's sources (../../src/main/java) into the build
    src/main/java/com/hf4all/game/
      Main.java         starts the server (port 8097)
      data/             Rules (data/rules.js), CardDb (data/cards.js),
                        GameMap = the planner's SolarMap + game overlay (zones, site flags, routes)
      model/            the game state, field for field the JSON the client renders
      engine/           the rules: FuelTrack (on the planner's FuelStrip), Dice (mulberry32),
                        Cards (J), StateOps, Actions (G), Movement (H, Fraction fuel),
                        Operations (I), Events (D, K, M), Negotiation (N), DemoFactory,
                        Scenarios (the demo scenarios, with loaded dice)
      api/              Rpc (commands and queries by name), ViewModel, GameService
                        (versions, undo, persistence)
      server/           GameServer (JDK HttpServer), ApiHandler, StaticHandler
      store/            GameStore: one JSON file per game, written atomically
    src/test/           parity tests (golden traces, map, demo), rule-fix and scenario tests, the HTTP API test
```

### Architecture

- **The server holds the game.** The whole game is one JSON object `G` (players, stacks,
  Fuel Strips, decks, sites, RNG state, log) - in Java the `model/` classes, serialized
  field for field as before, so saved files and the client's renderers are unchanged.
  The server keeps a version number per game (optimistic concurrency) and the undo
  history, and writes the game to disk after every command.
- **The browser sends commands.** `HF.app.act(fn)` runs `fn` against stand-ins of the
  engine (`js/net/remote.js`): the commands it issues (`ops.boost`, `movement.step`, ...)
  are sent as one step to `POST /api/games/{id}/commands`; the answer is the new state plus
  a *view* (scores, card statuses, which Operations and free actions are possible and why,
  thrust and move options, ...) computed by the server. A rule violation comes back as
  `422 {error}` with the rule reference ("burn limit 4 reached (H5c)") and is shown as a
  toast; nothing changes. Dialog previews with choices (support chain, afterburn) ask
  `POST /api/games/{id}/query`.
- **Hot-seat now, seats ready.** Every command may carry `seat` (the acting player); the
  server then checks that it is that player's decision. The hot-seat client omits it.
- **Dice are final.** All randomness goes through a seeded RNG stored in `G` (mulberry32,
  the same sequence as the former browser engine); an action that rolls a die clears the undo
  history. Undo is otherwise unlimited within a turn (Ctrl+Z, and "Step back" during movement).
- **Movement is priced, not searched.** `Movement.evaluate()` is a pure function that
  prices one route from the current move state (burns, bonus burns, pivots, fuel,
  landing method, hazards, belts, flybys, synodic gates, lander-burn look-ahead) with the
  planner's `MoveRules`. The map shows every neighbouring Space with its cost; the player
  clicks the next Space. The planner's route search is deliberately not part of the game.

### Game server API

| Request | Does |
|---|---|
| `GET /api/games` | the saved games, most recent first |
| `POST /api/games` `{players:[{name,crew,face}], cycles, seed}` | new game |
| `POST /api/games/demo` `{scenario?}` | a new copy of the demo position, or of a demo scenario (the reply adds `scenario`: id, group, title, text) |
| `GET /api/scenarios` | the demo scenarios: id, group, title, what to do |
| `POST /api/games/import` `{state}` | a saved game file becomes a new game |
| `GET /api/games/{id}` | `{id, version, canUndo, state, view}` |
| `POST /api/games/{id}/commands` `{version?, seat?, cmd, args}` or `{batch:[{cmd,args}]}` | run commands as one step; `422 {error}` on a rule violation, `409` on a stale version, `403` when the seat may not decide; the reply adds `events`: new dice `rolls`, the sunspot `lastEvent` and `booms` (cards lost against their owner's will, for the explosion effect - never stored in the state) |
| `POST /api/games/{id}/undo` `{version?}` | undo the last step of the turn (not past a die roll) |
| `POST /api/games/{id}/query` `{cmd, args}` | a read-only question, e.g. `movement.plan` with a support chain |
| `GET /api/games/{id}/export` / `DELETE /api/games/{id}` | download / delete a game |

Command and query names are `module.function` (`movement.step`, `ops.prospect`,
`negotiation.giveSite`, ...) with positional arguments; the full list is
`server/.../api/Rpc.java`.

### Map data

`tools/build_map.py` reads the planner's `data-hf4-v2.json` and writes `data/map.js`:

- node types (burn, hohmann, lagrange, site, radhaz, flyby, venus, decorative), landing
  burns with thrust requirements, flybys, Solar Oberth, synodic comets, site size /
  spectral type / hydration / flags;
- Hohmann **lane labels** (same lane = straight through, different lane = Pivot) and
  one-way **aerobrake** edges;
- hazards are split into *aerobrake* (on one-way paths) and *crash* hazards;
- **heliocentric zones**: Mercury-Jupiter come from the planner's solar modifiers; the
  planner stores -4 for everything beyond Jupiter, so Saturn / Uranus / Neptune are told
  apart by sampling the board image's background band colour around each node.
  Zone modifiers follow the printed board: Saturn -4, Uranus -5, Neptune no solar power.

Decorative nodes (route bend points) are collapsed into routes between real Spaces, so a
Spacecraft can never stop on them and the drawn path follows the printed curves.

---

## The experience (screens)

1. **Title** - new game, continue a game the server keeps, load a file, demo scenarios, rules summary.
2. **Setup** - 1-5 players; each is dealt a Crew card and picks one of its two faction
   sides (privilege, clout, crew thruster shown); game length 4 / 2 / 1 sunspot cycles;
   seed; optional "pass the device" screen. The best clout becomes 1st Player.
3. **Game table**
   - **Top bar** - the head of the right column (the map uses the whole height on the
     left; on narrow screens the bar sits on top): sunspot-cycle dial (seasons, event spaces,
     cube in the 1st Player's colour, seniority disks in the centre), year / season / Anarchy,
     🤝 Deal (the Negotiation screen), Undo, Save and Menu as icons, and the turn order with
     live VP (a chip opens that player's playmat).
   - **Board** - the printed map with pan / zoom (wheel, drag, arrow keys, buttons, "centre on me",
     "centre on Earth"). Overlays: Rockets and Outpost chits in player colours, Claim disks,
     Factory cubes, Colony domes, Busted markers, glory chits still on the map (centred on
     the printed glory circles), glitch markers; the heroism chits are the medals printed on
     the map (hover one for its name). Hovering any Space explains it (type, zone, hazards, ownership, stacks
     there, and - while moving - exactly what moving there costs or why it is illegal).
     A "Spaces" toggle colour-codes every Space by type with a legend.
     Rockets, Outposts, Claims (the Factory cube standing on the disk, the Colony dome on the
     cube) and Busted
     markers stand exactly on their Space - the planner map's position, i.e. the hex centre;
     only when several pieces share a Space are they fanned out around it. Figures grow when zoomed out, carry the player's initial,
     and the current player's Rocket has a halo. A **minimap** (top right) shows the whole board and the viewport;
     click or drag it to pan.
     **Explosions**: cards lost against their owner's will (hazard roll, radiation belt, solar
     flare, glitch, pad explosion, sail burn-up, ...) blow up on the map in a comic-book burst
     at their stack's Space - a shouted word by cause (KABOOM!, ZAP!, FRZZT!, BZZT!), debris,
     smoke, a shaking map and a caption naming the lost cards (`HF.ui.fx.demo('hazard')` in
     the console shows one).
   - **Playmat** (`P`, the dock, the player chips or the **Mat** button of the side panel) -
     a view over the whole map column (the side panel stays) of a
     player's mat as on the table, with the original graphics: the rulebook's Fuel Strip with
     the Dry Mass chit (player colour) and the Wet Mass chit (blue water, black dirt, gold
     isotope) on their printed steps and the fuel on board highlighted between them (net
     thrust is in the caption under it, the dock and the movement HUD); the LEO slot with Aquas beads and glory
     chits, the Rocket, Outpost 1 and Outpost 2 slots with their cards cascading as real
     stacks, and the Hand. Drag cards and FT beads between stacks on the same Space (cargo
     transfer, free action), onto an empty slot to create it, onto the Fuel Strip to load
     fuel, or a stack card onto the Hand to decommission it; drag a Hand card onto LEO to
     boost it, onto *Sell* (Free Market) or *Discard*; click a Hand card for Boost / Sell /
     Discard / both sides; click FT beads to jettison them. Hovering a card shows it large
     with its Operational status. Other players' mats are read-only (player chips at the
     top). It also opens during a movement, read-only (no free actions mid-move, H1c), with
     the movement's numbers and a ghost Wet chit for the fuel the move will burn. `Esc`
     returns to the map.
   - **Ship dock** - a strip under the board with the active player's Rocket: the full Fuel
     Strip (large, with the Dry and Wet chits) and, in the empty corner under its curve, the
     weight class, Dry/Wet mass, fuel steps, the engines that can fire (net thrust, burns of
     fuel; "🔧 Build an engine" while a thruster needing supports has none) and the Move /
     Load fuel / engine builder / Playmat buttons; card-art thumbnails with Operational dots,
     the other own stacks on the same Space, a "New outpost" slot and the Hand. Cards and FT
     chips are **dragged** between stacks; FTs dropped on the Fuel Strip are loaded as fuel.
     Collapsible (`D`). During a movement it stays available, collapsed by default and
     read-only (the movement HUD sits above it).
   - **Site inspector** - click a Site: size, spectral type, hydration, flags, landing and
     prospect thresholds, delivery cost, stock price, owner, every stack there.
   - **Movement HUD** - while moving: activated thruster, burn pips (used / limit), bonus
     burns and pivots, a fuel bar, fuel per burn, weight class, Step back (`Backspace`),
     Cancel (`Esc`), End movement (`Enter`); hovering a Space previews "after this step"
     burns and fuel; entered Spaces are numbered on the map. Legal next Spaces pulse on the map with cost badges
     (`1B` burn, `b` bonus burn, ✦ bonus pivot, ☠ hazard, ☢ belt, `+2` flyby, ⬇ landing);
     illegal neighbours are marked and explain why on hover.
   - **Side panel tabs**
     - *Turn* - player header (privilege, Aquas, hand, VP), clickable **Next steps** hints
       computed from the position (move with which thruster, empty tanks, jettison to a
       lighter class, site Operations possible here, glory, glitches, flare exposure), the turn checklist: Movement,
       the nine Operations (each greyed out with the reason when impossible), contextual
       free actions (take glory, build colony, claim jump, Murder/Suicide, dirt refuel,
       Mooncable, glitch repair, heroism vote), End turn, recent log.
     - *Mat* - not a tab: the button opens the playmat (above), as do `2` and `P`.
     - *Hand* - full cards, White/Black-Side toggle, discard.
     - *Patents* - the six decks with the always-visible top card and Auction buttons.
     - *Players* - a stacked **VP race** with the game clock, all factions, VP breakdowns, stack locations, exploitation tracks,
       glory chits, heroism chits; "Negotiate a deal" and a Deal button per opponent.
     - *Log* - filterable (all / dice / events / current player).
     - *Help* - how to use the app: a turn, moving, cards / cargo / fuel, Operations and
       auctions, deals, the map, undo / saving / settings, and the keyboard table. (The core
       rules summary stays on the title screen.)
   - **Other side of every card** - hold `Alt`: every card on the screen (Hand, stacks,
     dock, playmat, tooltips, dialogs) shows its other side (White ↔ Black, Crew faction
     sides) until the key is released.
   - **Dialogs** - movement preparation (only the player's **engines**, each with a full
     net-thrust breakdown, afterburn and the negotiated push; a 🗑 deletes a built engine),
     the **engine builder** (pick a thrust-triangle card of the Rocket and one support per
     requirement, recursively - J1c; a requirement only one card can meet fills itself; the
     engine is saved with the Rocket until one of its cards leaves it; thrust triangles that
     need no supports - Crew, sails, plain thrusters - are always available and need no
     building), another player's **permission** as an orange tick box that starts unticked
     every time (an opponent's Factory, prospecting Luna, a Powersat push; a Factory Hijack
     is the alternative tick) and FINAO-or-roll before using an opponent's Factory or
     entering hazards, cargo transfer, fuel loading with a live Fuel Strip
     preview, jettison preview, the nine operations (auction with every player's bid and
     tie-break, free market, boost with radiator orientation and running cost, site refuel,
     prospect with success odds, industrialize with a preview of what is decommissioned,
     ET production, delivery - refuel and ET production also at an opponent's Factory with
     permission or a hijack), Murder/Suicide, budget cuts, sunspot events, dice, heroism
     vote, card details, card library, final scores.
   - **Negotiation screen (N)** - pick a giver and a receiver (any two players, on anyone's
     Turn) and carry out the deal item by item: Aquas (N1); Hand cards (N2) with the
     academia re-trade lock shown on locked cards; stack cards and FTs into the receiver's
     stack on the same Space or a new Rocket / Outpost there, including a Crew that stays
     under its owner's control (N3d); a whole Rocket / Outpost; a Claim with its Factory and
     Colony (N3, with the disk / cube limits). Paused during movement and pending decisions.
   - **Live auction** - the lot shows the patent and the bonus supports the winner also
     takes (I2g: the open top card of each support deck it needs). The device goes round the
     table: open, Raise / Match / Hold / Pass,
     automatic I2e tie-breaks (initiator, Marketeer, otherwise the initiator picks), undo
     bid; "Enter final bids instead…" keeps the quick form.
   - **Keyboard** - `M` move, `E` end turn (asks first if the move or Operation is unused),
     `Z`/`Ctrl+Z` undo, `1`-`7` tabs, `D` dock, arrows pan (Shift: farther), `F` fit, `C` centre,
     `+`/`-` zoom, `N` deal, `?` Help tab,
     hold `Alt` for the other side of every card.
4. **Hand-over** - "Year 5 · Yellow season - pass the device to Bo" between turns, with the
   player's Aquas / Hand / VP / Rocket and **"Since your last turn"**: everything that
   happened since they last ended a turn.
5. **Game over** - ranking with the full M2 breakdown (claims, factories, colonies, rocket,
   stock price, colony bonus, glory, heroism, Aqua tiebreak).

---

## Rules coverage (core game)

Implemented:

- **Setup (C)**: Crew deal & faction side, 6 Aquas (+2 Secretary-General), clout → 1st
  Player, shuffled decks, glory chits, heroism chits with 3+ players.
- **Year (D)**: turns clockwise from the 1st Player; sunspot cube advance; events;
  seniority disks; game end after 4 cycles (configurable).
- **Stacks (E)**: LEO / Rocket / 2 Outposts, creation by transfer, empty stacks removed,
  decommission rules (Crew back to LEO, ablating heavy radiators, lost FTs and fuel).
- **Mass & fuel (F)**: the printed non-linear Fuel Strip, Dry ≤ 23 to move, red-line mass
  adjustments, fuel grades and mixing, weight classes.
- **Free actions (G)**: cargo transfer (with infectious glitches), load fuel, FT↔fuel,
  jettison fuel / FTs / cards, radiator to light, Phileas Fogg (also Fogg's heavy radiators
  once the Wet Mass chit is gray: the shed mass becomes fuel), dirt refuel, swap
  Rocket/Outpost, build colony, claim jump (Felony), take glory, glitch repair, discard
  Hand cards, discard your own Factory cube or Claim disk (G6a, from the Site inspector;
  a colonized Factory is permanent).
- **Felonies** (Felonious privilege, or everyone during Anarchy): claim jump; prospecting
  Luna without the 1st Player's permission (your Human on the Site, no enemy Human); Murder /
  Suicide once per Turn (your Crew anywhere outside LEO, or an opponent's Crew riding in
  your stack while your own Human is there; the Crew returns to its owner's LEO and a
  carried glory chit scores its low side); Factory Hijack - factory-assisted landing or
  liftoff, factory refuel and ET production at an opponent's Factory without permission
  when your Human is on the Site (aboard the landing Rocket counts) and no enemy Human is.
  Without a hijack, using an opponent's Factory needs the owner's permission (N5 / N6),
  ticked in the dialog every time.
- **Movement (H)**: activation of any Operational thrust triangle (crew, thrusters,
  robonauts), net thrust = base + afterburn + weight class + solar + push + wrench
  supports, fuel multipliers, burn limit, coasting, Hohmann pivots / bonus pivots, free
  turns, no U-turn, aerobrake one-way paths, lander burns (no stop, no bonus burns,
  half-burns, and no fixed thrust requirement: the look-ahead checks the move can be
  completed, e.g. a powered landing below or an aerobrake path), powered / aerobrake (only
  along one-way aerobrake paths) / factory-assisted landing and liftoff,
  hazard rolls with FINAO, sail burn-up, flybys, Venus flyby in blue only, Solar Oberth,
  Solar-Powered cards in the Neptune zone only when pushed by a Powersat (-6),
  radiation belts (+2 in red), Mag Sail belt bonus, flyby glitch trigger, synodic comets,
  fractional fuel rounded up at the end of the move.
- **Operations (I)**: income, research auction (academia limit, initiator rules, ties,
  Marketeer, bonus supports), free market (hand card 3 / Black-Side card at stock price
  from any of your stacks in the Bank, e.g. a Rocket in LEO),
  boost (radiator orientation, Launch Fees), ISRU / factory (also into a new Outpost at a
  Factory where you have no stack, E6c) / air-eater refuel, prospect
  (raygun adjacency - an Atmospheric Site only at range zero -, buggy re-roll, Blink
  Telescope, Nanites re-roll on the failed Site the player puts first, Luna permission,
  busting, Taxes; with no Claim disk left a success can take the disk from one of your
  unindustrialized Claims), industrialize (support chains decommissioned, radiators exempt),
  ET production (into an Outpost; choosing the Rocket is a cargo transfer, a Glitch
  Trigger for a glitched Rocket), delivery (2 FT per zone, +1 for Size 7+).
- **Supports (J)**: generator / reactor subtypes, support chains, mutual pulsed support,
  cooling Therms, afterburn Therm, solar cards dead in the Neptune zone unless pushed,
  heatsink fountains (they flip to light the first time their Therm is needed, in a move
  or an Operation). For movement the player builds **engines** (J1c): a thrust-triangle
  card with one explicitly chosen support per requirement, recursively, priced live
  (thrust modifiers, fuel multipliers, solar power and the cooling the chain needs). Engines
  are kept per Rocket (`Stack.engines`, by card uid, so they survive reordering) and dropped
  automatically when one of their cards leaves the Rocket; a move starts only with a built
  engine or a thrust triangle that needs no supports.
- **Negotiation (N)**: Aquas (N1), Hand cards with the academia re-trade restriction
  (N2a, lifted when the original holder's next Turn starts), stack cards / FTs, whole
  stacks and Claims with Factories and Colonies (N3) with component limits (N3b), Crew
  in an opponent's stack stays under its owner's control (N3d: still their Human for
  felonies, glory, parades and decommissioning, and the host's for glitch repair and
  thrust activation), negotiated push (N4a), factory-assist / on-site Factory use with
  permission (N5, N6).
- **Events (K)**: inspiration, glitch (stacks with Colocated Humans are skipped; a tie is
  the player's choice), pad explosion (a tie between the heaviest cards is the player's
  choice), anarchy, budget cuts (each player chooses), solar
  flare with zone modifiers and LEO / cycler / site / planetary-belt shielding. A Glitch
  Roll hits the matching cards in all of the player's stacks on the Glitch's Space.
- **Glory & heroism (L)** (heroism votes only before the last year) and **scoring (M)**.
- **Card Abilities** - each works only while its card is Operational (J2e); "Colocated"
  means any of the player's own stacks on the same Space: Nanites re-roll; the size-roll
  modifiers (Thorium Breeder, Foamed Nickel, Comet Lichen, Superlens); Power Girdle /
  Ionosat (permanent Powersat); Arcology; **Mine Revival** (Termite Nest: a card
  Operation, in the Operation grid only while the player has the card in play - removes
  the busted disk from a Site of Size 2+ and places a Claim); **Jellybots** (Solid Flame:
  industrializing on its Space is a free action, also after the Turn's Operation);
  Divining Nubots (-1) and Scoop (-2 at Aerostat Sites) for every Colocated ISRU card;
  Atmospheric Scoop (Aerostat Sites on or Adjacent to its Space have Hydration 2);
  Scavenging (doubles every Site refuel - ISRU and Factory); MagBeam push; Air-Eaters; aerobrake-safe generators;
  the Magnetoshell Plasma Parachute supports nothing during industrialization; sails are
  immune to flares and Belt Rolls (not in the Neptune zone unless pushed - there they are
  not Operational); Mag Sail belt Bonus Burns; Heatsink Fountains; the Magnetocaloric
  Refrigerator cools its own supports; Project Valkyrie, when activated, decommissions the
  cards with Rad-Hard < 4 in all the player's stacks on the Rocket's Space (LEO stack
  included when it starts in LEO) - killing a Human that way is a Felony (J1c), so the
  engine cannot start without the Felonious privilege or Anarchy.
- All twelve faction privileges (a Powersat gained from a card is an Ability and survives
  Anarchy). The kreutz sungrazer loses its Factory at perihelion but keeps the Claim.

Simplified or not yet implemented (candidates for the next iteration):

- Buggy-road multi-site prospecting and Crew buggy movement (the map data has no buggy
  roads).
- Isotope fuel / gold FTs (no core thruster burns isotope fuel), Acetylene rocketplanes.
- Negotiation: promises and permissions are the table's word (the permission choices
  are confirmed by the active player); a whole stack can only be handed over into a free
  Rocket / Outpost slot (the temporary third Outpost of N3b is not modelled); a Claim and
  its Factory always share one owner (the split colours of G6a are not modelled); the
  negotiated privileges N4b-N4e, ability sharing (N7) and N8 glitch repair are not
  implemented.
- Industrialize still decommissions the automatic support chains (only movement has the
  hand-picked chain).

### Card data

`data/cards.js` holds the real core-game cards, transcribed from the card faces in the
official Vassal module: 77 patent cards (12 thrusters, 12 robonauts, 12 refineries, 12
reactors, 12 radiators, 17 generators), both sides each, with ids = printed card numbers
(`CT044` = CT044F / CT044R), and the 6 Crew cards (12 factions with their real privileges,
clout, Crew thrust triangles and ISRU platforms). Glory chit values (Mercury 3, Venus /
Earth / Mars / Ceres 2, Jupiter 3, Saturn 4, Uranus 5, Neptune 6) and the sunspot cycle
(six event thresholds per cycle, blue season straddling START) come from the module's
components. *Menu → Card library* browses every card.

Every number on the cards is used by the engine (mass, rad-hardness, thrust triangles
with afterburn / push / bonus pivots / solar power, fuel grade, ISRU rating and platforms,
supports needed and provided - several subtypes per card -, wrench thrust and fuel
modifiers, both radiator orientations with their own rad-hardness). Printed abilities
that the engine applies: DeCom sails, flare & belt immunity, Mag Sail belt bonus burns,
air-eaters, safe aerobraking (Magnetoshell / Granular Rainbow), heatsink fountains,
MagBeam push (+3 thrust, -1 ISRU), Divining Nubots (-1 ISRU), SCOOP (-2 ISRU / Hydration 2
at aerostats), size-roll modifiers (Thorium Breeder, Foamed Nickel, Comet Lichen,
Superlens), Nanites re-roll, Scavenging (double refuel), Arcology (no robonaut in the inner
zones), Power Girdle / Ionosat (permanent Powersat) and Project Valkyrie. Display-only
for now: Mine Revival (Termite Nest), Jellybots (Solid Flame) and the "cannot support
Bernals" restriction (Bernals are Module 2).

---

## Decisions taken while designing

- Plain HTML/CSS/JS with no framework or build step, so it runs from a double-click and
  never interferes with the planner's Maven build or static assets.
- English UI, matching the rulebook terms and the planner.
- The original board image as the play surface (instead of a redrawn map) so every icon
  players know is where they expect it; the engine uses the planner-derived graph.
- Click-by-click movement with live cost preview instead of automatic route finding:
  routes are the player's decisions (and the planner's job).
- Undo everywhere until a die is rolled; seeded dice so a saved game replays faithfully.
- Hot-seat friendly: open information (hands are open in HF), optional hand-over screen,
  every opponent's mat inspectable.
