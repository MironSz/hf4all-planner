/*
 * Original game graphics (assets/gfx, extracted from the official Vassal module by
 * tools/extract_cards.py - not in git). Probed once at startup: when they are
 * missing, or the "Original game graphics" setting is off, every helper returns
 * null and the UI keeps its drawn fallbacks.
 */
(function (HF) {
  'use strict';

  const UI = HF.ui;
  const esc = HF.escape;
  const DIR = 'assets/gfx/';
  const LOGO = {
    'NASA Astronauts': 'NASA', 'ISRO Glavcosmonauts': 'ISRO', 'United Nations Cosmonauts': 'UN', 'B612 Foundation': 'B612',
    Taikonauts: 'CNSA', ROSCOSMOS: 'ROSCOSMOS', 'ESA Space Unionists': 'ESA', 'Anonymous P2P': 'P2P',
    'Shimizu Corp Entrepreneurs': 'Shimizu', 'NASRDA Astronauts': 'NASRDA', SpaceX: 'SpaceX', 'Norse Astronauts': 'NORSE'
  };
  const PIECE = { rocket: 'rocket-', claim: 'claim-', factory: 'factory-', colony: 'colony-', outpost1: 'outpost1_', outpost2: 'outpost2_', out1: 'outpost1_', out2: 'outpost2_', dry: 'Dry_' };

  const Gfx = {
    ok: false,

    /** Graphics present and switched on. */
    on() { return Gfx.ok && !(HF.app && HF.app.settings && HF.app.settings.cardArt === false); },

    probe(after) {
      const img = new Image();
      img.onload = () => { Gfx.ok = true; Gfx.apply(); if (after) after(); };
      img.onerror = () => { Gfx.ok = false; Gfx.apply(); };
      img.src = DIR + 'aqua-bead.png';
    },

    apply() { document.body.classList.toggle('gfx', Gfx.on()); },

    src(name) { return Gfx.on() ? DIR + name : null; },

    /** Vassal colour name of a player ('gray' is 'grey' there). */
    vcol(p) { return p.color === 'gray' ? 'grey' : p.color; },

    /** rocket | outpost1 (out1) | outpost2 (out2) | claim | factory | colony | dry, in the player's colour. */
    piece(kind, p) { return PIECE[kind] ? Gfx.src(PIECE[kind] + Gfx.vcol(p) + '.png') : null; },

    /** Wet Mass chit by fuel grade (blue water, black dirt, gold isotope). */
    wet(grade) { return Gfx.src({ dirt: 'Wet_black.png', isotope: 'Wet_gold.png' }[grade] || 'Wet_blue.png'); },

    logo(faction) { return LOGO[faction] ? Gfx.src('logo-' + LOGO[faction] + '.png') : null; },
    /** Logo path before the probe has finished (screens drawn at startup); pair it with onerror. */
    logoTry(faction) { return LOGO[faction] && HF.app.settings.cardArt !== false ? DIR + 'logo-' + LOGO[faction] + '.png' : null; },
    die(v) { return Gfx.src('die' + v + '.png'); },
    /** Glory chit, zone face (on the map / carried) or VP face (scored in LEO). */
    glory(zoneId) { return Gfx.src('Glory-' + HF.zone(zoneId).name + '.png'); },
    gloryVp(vp) { return Gfx.src('Glory-' + Math.max(2, Math.min(6, vp)) + 'VP.png'); },

    /** Faction logo <img> in the player's colour ring, or the colour dot. */
    logoHtml(p, size) {
      const s = Gfx.logo(p.faction);
      size = size || 18;
      if (!s) return UI.dot(p, size);
      return '<img class="flogo" src="' + s + '" width="' + size + '" height="' + size + '" alt="" style="--pc:' + HF.state.color(p) + '" title="' + esc(p.faction) + '">';
    },

    /** Inline icon <img> (aqua bead, VP star, first player...) or the fallback text. */
    icon(name, fallback, cls) {
      const s = Gfx.src(name);
      return s ? '<img class="gico ' + (cls || '') + '" src="' + s + '" alt="">' : fallback;
    }
  };

  UI.gfx = Gfx;
})(window.HF = window.HF || {});
