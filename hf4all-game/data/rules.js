/*
 * Static rule tables for the HF4A core game (rule references in brackets).
 * Glory values and the sunspot-cycle layout come from the components in the
 * official Vassal module; zone modifiers from the printed board.
 * in this repo and were chosen for this prototype.
 */
window.HF_RULES = {
  LEO: '90',
  // The cycler Space (the Lagrange burn next to LEO): Van Allen shielding against flares (K2d).
  CYCLER: '91',

  // Heliocentric zones, inner to outer (B7g). solar = Solar-Power thrust modifier
  // (H3c, null = not operational), flare = CME severity modifier (K2d).
  // glory = board position (pixels on the 5400x3619 board) of the glory chit (C7): the
  // centre of the printed glory circle (fitted to its ring of ticks on assets/board.jpg);
  // gloryVp = high-side value of the chit (low side is always 1 VP), as in the Vassal module.
  zones: [
    { id: 'mercury', name: 'Mercury', symbol: '☿', solar: 2, flare: 2, glory: [3865, 2490.5], gloryVp: 3 },
    { id: 'venus', name: 'Venus', symbol: '♀', solar: 1, flare: 1, glory: [3731.5, 2207.5], gloryVp: 2 },
    { id: 'earth', name: 'Earth', symbol: '⊕', solar: 0, flare: 0, glory: [3638.5, 1975.5], gloryVp: 2 },
    { id: 'mars', name: 'Mars', symbol: '♂', solar: -1, flare: -1, glory: [3517.5, 1599.5], gloryVp: 2 },
    { id: 'ceres', name: 'Ceres', symbol: '⚳', solar: -2, flare: -2, glory: [3265, 747.5], gloryVp: 2 },
    { id: 'jupiter', name: 'Jupiter', symbol: '♃', solar: -3, flare: -3, glory: [2994, 273.5], gloryVp: 3 },
    { id: 'saturn', name: 'Saturn', symbol: '♄', solar: -4, flare: -4, glory: [2655.5, 176.5], gloryVp: 4 },
    { id: 'uranus', name: 'Uranus', symbol: '♅', solar: -5, flare: -5, glory: [2289.5, 142.5], gloryVp: 5 },
    { id: 'neptune', name: 'Neptune', symbol: '♆', solar: null, flare: -6, glory: [1967, 154], gloryVp: 6 }
  ],

  // Heroism chits (L c): 2 VP each, awarded by vote, only with 3+ players.
  heroism: [
    { id: 'devastating', name: 'Devastating event', pos: [2700, 3487] },
    { id: 'loss', name: 'Loss of human life', pos: [2844, 3462] },
    { id: 'aiding', name: 'Aiding an opponent', pos: [2987, 3487] },
    { id: 'newbie', name: 'Newbie miscalculation', pos: [3137, 3487] }
  ],

  // Sol Sunspot Cycle (K1, D2), core placard. 12 yearly spaces clockwise from START;
  // season blue straddles START. An Event Roll is made when the cube moves onto an
  // `event` space (six event thresholds per cycle); moving from the last space back
  // to START crosses the seniority threshold.
  cycle: {
    spaces: [
      { season: 'blue' }, { season: 'blue', event: true }, { season: 'yellow' }, { season: 'yellow', event: true },
      { season: 'yellow' }, { season: 'yellow', event: true }, { season: 'red' }, { season: 'red', event: true },
      { season: 'red' }, { season: 'red', event: true }, { season: 'blue' }, { season: 'blue', event: true }
    ]
  },

  // Exploitation track (B5, I7d): stock price by number of Factories of a type.
  stockPrice: [10, 8, 5, 4],
  spectralTypes: {
    C: 'Carbon', S: 'Stone', M: 'Metal', V: 'Vestoid', D: 'Dark', H: 'Helium-3'
  },

  playerColors: {
    white: '#eceff4', yellow: '#f2c230', purple: '#9b6bea', green: '#43b05c', red: '#e0484a', gray: '#9aa3ad'
  },

  // Faction privileges (B6a + glossary).
  privileges: {
    blinkTelescope: { name: 'Blink Telescope', text: 'Raygun prospecting: one re-roll for the first Site of each prospect operation.' },
    dharmaRefuel: { name: 'Dharma Refuel', text: 'Your Crew gets double yield from site refuel while carrying a glory chit.' },
    felonious: { name: 'Felonious', text: 'You may commit Felonies: Claim Jump, Factory Hijack, Murder/Suicide.' },
    launchFees: { name: 'Launch Fees', text: 'Gain 1 Aqua whenever any player boosts one or more cards.' },
    marketeer: { name: 'Marketeer', text: 'You always win auction ties, even if you are not the initiator.' },
    mooncable: { name: 'Mooncable', text: 'Once per turn: dirt refuel an activated dirt thruster at LEO (7 tanks, or 1 tank for Crew).' },
    openSourceFinao: { name: 'Open Source FINAO', text: 'FINAO costs you only 3 Aquas.' },
    powersat: { name: 'Powersat', text: 'Pushable thrusters get +1 thrust at any range; safe factory-assist.' },
    secretaryGeneral: { name: 'Secretary-General', text: 'Start the game with two extra Aquas.' },
    scrumTroubleshooters: { name: 'Scrum Troubleshooters', text: 'Repair any Glitch anywhere.' },
    skunkworks: { name: 'Skunkworks', text: 'Ignore the academia hand limit for research auctions.' },
    taxes: { name: 'Taxes', text: 'Gain 1 Aqua for each Claim or Factory established by any player.' }
  },

  // Operations (I) - one per turn.
  operations: [
    { id: 'income', name: 'Income', ref: 'I1', text: 'Receive 1 Aqua.' },
    { id: 'auction', name: 'Research Auction', ref: 'I2', text: 'Auction the top card of a patent deck. Hand limit: fewer than 4 cards.' },
    { id: 'freeMarket', name: 'Free Market', ref: 'I3', text: 'Sell a Hand card (3 Aquas) or a Black-Side card in LEO (stock price).' },
    { id: 'boost', name: 'Boost', ref: 'I4', text: 'Pay Aquas equal to Mass to bring Hand cards into LEO.' },
    { id: 'refuel', name: 'Site Refuel', ref: 'I5', text: 'ISRU refuel (1 + Hydration - ISRU FTs), factory refuel (7 FTs) or air-eater scoop.' },
    { id: 'prospect', name: 'Prospect', ref: 'I6', text: 'ISRU card on a Site: roll <= Size (Size 6+ automatic) to place a Claim.' },
    { id: 'industrialize', name: 'Industrialize', ref: 'I7', text: 'Decommission an Operational robonaut + refinery on your Claim to build a Factory.' },
    { id: 'etProduction', name: 'ET Production', ref: 'I8', text: 'Bring a Hand card into play Black-Side up at your Factory of matching spectral type.' },
    { id: 'delivery', name: 'Delivery', ref: 'I9', text: 'Send a Black-Side card from a Factory to LEO for 2 FTs per zone from Earth (+1 if Size 7+).' },
    // A card Operation (shown only while the player has the card in play).
    { id: 'mineRevival', name: 'Mine Revival', ref: 'Termite Nest', card: true, text: 'Remove the busted disk from a Site of Size 2+ where your Operational Termite Nest stands and place your Claim there.' }
  ]
};
