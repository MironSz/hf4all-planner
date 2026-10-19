# HF4A hot-seat: interface design notes

This file covers the second interface pass: what the audit of the first version found,
what changed, why, and further proposals that are not built yet. The README describes
the screens. This file describes the reasoning behind them.

## 1. Audit of the first version

The first version followed the rules correctly, but it was built like a set of forms:

| Problem | Effect at the table |
|---|---|
| The Rocket, the thing every player cares about most, only appeared in the *Mat* tab, as text. | Players kept switching tabs to answer "where am I, how much fuel, what thrust?" |
| Cargo moves went through a Transfer dialog with checkboxes. | Three clicks and a form for "put this card in my Rocket". |
| The movement HUD showed eight numbers in boxes and no preview. | You only found out what a step cost after taking it, then used Step back. |
| The auction was a form: "enter every player's *final* bid". | At a hot-seat table nobody knows their final bid up front. In practice the device went round anyway, outside the program. |
| The hand-over screen only said "pass the device to Bo". | After passing the device, the next player had to read the log to see what had changed. |
| Nothing told a new player what was possible right now. | The nine Operation buttons were greyed out with reasons, but nothing pointed at the *good* move. |
| No keyboard shortcuts except Ctrl+Z. | Slow for experienced players. |
| The board had no overview at high zoom. Player figures shrank to dots when zoomed out, and all players looked alike apart from colour. | Players got lost on a 5400-px map, and figures were hard to tell apart for colour-blind players. |
| With the original card art available, cards still appeared as text rows almost everywhere. | The physical game's main pleasure, looking at your ship, was missing. |

## 2. What changed

### 2.1 Ship dock (bottom of the board)
An always-visible strip for the **active player**. It shows:

- the weight class, Dry and Wet mass, and a one-line **fuel gauge** (weight-class bands, Dry chit, fuel bar);
- each Operational thrust triangle as a chip: *name · net thrust · burns of fuel*. Clicking a chip opens the movement picker;
- the Rocket's cards as **card-art thumbnails**, with an Operational dot (green or red) and a glory star;
- every other own stack on the same Space, as drop targets;
- a "＋ New outpost" slot;
- the Hand as a small fanned stack (click it to open the Hand tab).

The dock hides itself during movement, because the movement HUD takes its place. It can be collapsed with ▾ or `D`, and the choice is remembered. On narrow screens it moves below the board instead of covering it.

*Why a dock and not a bigger side panel:* the side panel is for *decisions* (operations,
patents, players). The dock is for *state* that you glance at many times per turn. Splitting
them means the side panel can stay on whichever tab you are using.

### 2.2 Drag and drop for cargo (dock and Mat tab)
- Drag a card thumbnail (or a list row in the Mat tab) onto another stack on the same Space. This calls `transfer`, the same engine call the dialog uses.
- Drag a card onto an **empty slot** (no Rocket, or Outpost 1/2 "not in play") to create that stack on the Space (E2c/E6).
- Drag an **FT chip** (or "5 Aquas" in LEO) onto a stack: the app asks how many, then moves them as cargo.
- Drop FTs **on the fuel gauge or the Fuel Strip** to load them as fuel (G1b), or convert them if they are already in the Rocket (G2a).
- Valid targets are outlined green while dragging; invalid ones (not on the same Space, the source itself) fade out.
- The old dialogs stay as the accessible and touch path. Drag and drop is an accelerator only.

### 2.3 Mat tab: List / Cards view
A segmented switch shows stacks either as the compact list rows (with stats) or as
card-art thumbnails. The choice is remembered. Viewing an opponent's mat is read-only.

### 2.4 Movement HUD v2
- **Burn pips**: one pip per burn up to the burn limit, filled as burns are used. Bonus burns appear as green dots and bonus pivots as amber diamonds.
- A **fuel bar** for spent / available fuel. **Hover preview**: pointing at a Space shows the extra fuel in a lighter colour, and a line reading *"→ Lunar orbit: 1 burn · then burns 3/6, fuel 4/10 · flyby +1"*, or why the move is illegal.
- **Step numbers** are drawn on the Spaces already entered, so the path reads 1 → 2 → 3 on the map.
- Keys: `Enter` ends the move, `Backspace` steps back, `Esc` cancels before the first step.
- During movement the minimap moves up so it never covers the HUD.

### 2.5 Next-step hints ("Next steps" in the Turn tab)
These are computed from the game state, not scripted, and each one is clickable:
- "Move your Rocket: SpaceX, net thrust 15, fuel for 2 burns" opens the movement picker;
- empty tanks: load fuel, or a warning that no FTs are on this Space;
- "Jettisoning 2 fuel steps drops you to the Scout class (+1 net thrust)", shown only when that is actually within 3 steps;
- site Operations that are possible right now (industrialize, prospect, ET production, delivery, refuel);
- "Auctions are locked (4+ Hand cards)";
- a glory chit that can be taken; glitched stacks that can be repaired; in a red season, which of your stacks a flare can hit;
- "Done for this year? End your turn."

The hints can be hidden with ✕, and the Menu turns them back on.

### 2.6 Turn flow
- **Hand-over digest**: the pass-the-device screen now shows the player's Aquas, Hand, VP and Rocket location, and **"Since your last turn"**: every log line since that player last ended a turn (other players' actions, year end, events), without dice noise.
- **End-turn guard**: ending a turn with the Operation or the move unused asks for confirmation. There is a "Don't ask again" option, and the Menu has a toggle for it.

### 2.7 Live research auction (I2)
A bidding table replaces the "enter final bids" form:
- The device goes around the table starting with the initiator, who **must open** (0 or more).
- On their turn a player can **Raise** (stepper, Enter), **Match** the high bid, **Hold** (if already at the high bid) or **Pass** (permanent; a passing high bidder's bid still stands).
- The auction ends when every player still in has acted since the last raise or match. This reproduces the I2e example exactly: 0 → Bo 1 → match → Bo 2 → Clara matches → the initiator chooses.
- Ties are resolved automatically (initiator, then Marketeer). Otherwise the initiator picks one of the tied players with a button.
- Players over the academia limit are shown but greyed out, and a bid log runs under the table. *Undo bid* reverts the last action, and *Award* applies the result through the engine.
- "Enter final bids instead…" keeps the old quick form for groups that bid out loud.

### 2.8 Board orientation
- **Minimap** (top right): board thumbnail, all Rockets and Outposts, the viewport rectangle, and the move marker. Click or drag on it to pan. It can be hidden (⧉ shows it again). The site inspector opens under it.
- The board can scroll **above the dock**, so Spaces near the bottom edge (Earth, the heroism chits) are never trapped under the dock. Centring takes the dock into account.
- **Figures scale with zoom**: at overview zoom, figures are drawn up to 2.2× larger.
- Rockets carry the **player's initial**, and Outposts read e.g. "B1", so players can be told apart without relying on colour.
- The current player's Rocket has a slowly rotating **halo**.

### 2.9 Players tab: VP race
A stacked bar per player, ranked: tokens (Claims, Factories, Colonies, Rocket), stock value,
colony bonus, glory, heroism. Hovering a segment shows its VP. A game clock bar shows
the year out of the total and the Seniority Disks left.

### 2.10 Keyboard
`M` move · `E` end turn · `Enter` / `Backspace` / `Esc` during a move · `Z` / `Ctrl+Z` undo ·
`1`–`7` tabs · `P` playmat · `D` dock · `F` fit map · `C` centre on me · `+` / `-` zoom · `N` deal · `?` help.
Shortcuts are ignored while typing in an input or while a dialog is open. The tabs show icons and
their number in the tooltip.

### 2.11 Playmat view and original game graphics (second request)
The user asked for a view "like in the game": the four piles (LEO, Rocket, two Outposts), the
Hand, and the Fuel Strip with the Wet and Dry Mass chits. In this view cards move between
stacks as the move-cargo free action, and the Hand can be managed. The user also asked for the
game's own symbols and graphics wherever possible.

- **Where the graphics come from.** `tools/extract_cards.py` now also copies 92 graphics from the official Vassal module into `assets/gfx/` (not in git): the printed playmat slots, the Fuel Strip, Dry chits per player colour, Wet chits per fuel grade, Rocket figures, Outpost / Claim / Factory / Colony pieces, Aqua and isotope beads, dice, glory and heroism chits, faction logos, the first-player marker and the Sunspot Cycle board. Positions on those images come from the module's own Playmat board definition (`tools/build_playmat.py` → `data/playmat.js`): all 57 Fuel Strip spaces and the Net Thrust track. Chits therefore sit exactly on the printed spaces, with no hand-measured coordinates.
- **Playmat** (`P`, top bar, dock, Mat tab; `Esc` back to the map). It is a full-screen overlay rather than a tab, because the strip and five card-sized slots need the whole screen to read like the table.
  - **Top:** the original Fuel Strip. The Dry chit is in the player's colour; the Wet chit is blue (water), black (dirt) or gold (isotope). During a move, a ghost Wet chit shows the fuel that will be burned. The player's Rocket figure stands on the Net Thrust space of the current move, or of the best Operational thruster. With no Rocket, both chits wait in *Chit Storage*, as on the real mat.
    - *Later change:* the playmat (like the Mat tab and the fuel dialogs) now shows the planner's cut-out of the rulebook's Fuel Strip (`assets/fuel-strip.png` + `data/fuelstrip.js`, `tools/build_fuelstrip.py`) instead of the Vassal strip. That strip has no Net Thrust track, so the Rocket figure and Chit Storage are gone from the mat; net thrust is in the caption, the dock and the movement HUD.
  - **Right panel:** faction logo, Aquas, VP, thrusters, the fuel actions, and a large preview of the hovered card with its Operational status.
  - **Bottom:** LEO (Aquas beads in the printed AQUAS tab, scored glories in the GLORIES circle), Rocket, Outpost 1, Outpost 2 and Hand. Cards cascade down the printed slots, offset like real overlapping cards so each card's header (type, Mass, Rad-hard) stays visible. Each slot grows with its stack. Under each slot are the map piece, its location (a link to the map), mass, cooling, glitch state and the FT beads.
- **Manipulation**, all through the same engine calls as the dialogs:
  - card or FT beads to another stack on the same Space: transfer (G1);
  - card to an empty slot: creates that stack;
  - beads to the strip: load fuel (G1b / G2a);
  - stack card to the Hand: voluntary decommission, after a confirmation;
  - Hand card to LEO: opens Boost with that card ticked;
  - Hand card to *Sell*: Free Market (I3);
  - Hand card to *Discard*: G6.
  
  Invalid targets fade while dragging. The Sell target is disabled once the Operation is used.
- **Graphics elsewhere** (switch: *Menu → Original game graphics*):
  - aqua beads wherever Aquas are shown;
  - Rocket and Outpost pieces on the map, with a small initial badge for colour-blind players;
  - Claim discs, Factory cubes and Colony domes (drawn twice, because the Vassal pieces are translucent);
  - the busted marker and glory chits on the map;
  - real dice;
  - faction logos in the Turn tab, Players tab, dock, hand-over and setup;
  - the first-player marker;
  - the printed Sunspot Cycle in the year tooltip.
  
  Without `assets/gfx/`, every one of these falls back to the drawn version.

## 3. Design rules this pass followed

1. **Every new interaction is an accelerator, never the only path.** Drag and drop, keys, hint chips and the minimap all duplicate something reachable by plain clicks, which keeps touch and keyboard-only play possible.
2. **The engine stays the single source of truth.** Every new UI path calls the same `HF.actions` / `HF.ops` / `HF.movement` functions through `HF.app.act`, so undo, autosave, dice finality and rule errors behave as before. The live auction keeps its round state in the dialog and only calls `resolveAuction`.
3. **Show the consequence before the click**, as in the move preview, the jettison-to-lighter-class hint, drop-target highlighting and the auction winner line.
4. **No planner.** The hints and previews only look one step ahead (the Space under the mouse, the current Operations). They never search routes.
5. **Hot-seat aware.** The hand-over digest, the end-turn guard and the round-robin auction exist because one device is passed around.

## 4. Further proposals (not built yet)

Ordered by expected value per effort.

| # | Proposal | Why |
|---|---|---|
| 1 | **Auction fit preview**: next to the auctioned card, show "with your Rocket: would be Operational / missing a reactor; +3 net thrust over your best". | The most common question during an auction. Needs only `cards.analyze` on a hypothetical stack. |
| 2 | **What-if in the Mat**: drag a Hand card onto the Rocket as a *ghost* to see the new weight class, net thrust and Operational status before boosting. | Planning ships is the core skill of HF4A. Uses the same analysis as #1. |
| 3 | **Prospect-target overlay** (board toggle): highlight unclaimed Sites where *your* ISRU would work (hydration ≥ ISRU), labelled with size. | A filter, not a route search, so it stays outside the planner. It answers "where can I go to claim?" |
| 4 | **Turn timeline / undo list**: a list of this turn's actions (from the snapshots already kept) with "jump back to here". | Undo is unlimited until a die is rolled, but today it only steps back one action at a time. |
| 5 | **End-of-year summary** at the year boundary: VP change per player, stock prices that moved, events, next season. | It gives the table a natural pause and recap. |
| 6 | **Threat feed**: "Bo's Crew stands on your Claim (claim jump possible)", "Clara's Rocket is in your Factory's Space". | Felonies and Factory use are easy to miss in an open-information game. |
| 7 | **Touch polish**: long-press for tooltips, pinch zoom, touch drag and drop (pointer-events instead of HTML5 DnD), larger hit areas on Spaces. | HTML5 drag and drop does not work with touch, so the dialogs remain the touch path for now. |
| 8 | **Accessibility**: a colour-blind palette preset, per-player figure patterns, a reduced-motion setting (halo, dice tumble), font scaling. | Initials already help. Palettes and patterns would finish the job. |
| 9 | **Animated movement replay**: the Rocket glides along the numbered path when a move ends, and in the hand-over screen for the opponents' moves since your last turn. | Shows what others did on the map, not only in text. |
| 10 | **Log scrubber / replay** from saved snapshots, also useful for teaching. | The state is already one JSON object, so snapshots are cheap. |
| 11 | **Remote play later**: since every change is an `act(fn)` on one state object, actions could be serialised into a command log. | Keeps the door open without changing the hot-seat design. |

## 5. Known limits of this pass

- HTML5 drag and drop is mouse-only (see #7). On touch devices use the Transfer / Load fuel dialogs, which are unchanged.
- In the live auction, Pass is final. The rulebook allows bidding in any order, so a group that wants to re-enter after passing should use "Enter final bids instead…".
- The dock shows the active player only. Opponents' ships are on the map (hover) and in the Mat tab via the player chips.
- The playmat caption shows net thrust only for the active player. Net thrust is computed by the movement engine for the player whose turn it is.
- The playmat's drawn fallback (no `assets/`) shows plain slots and cards with only Mass, name and type; the full drawn card appears in the hover preview.
