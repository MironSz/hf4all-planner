/* Rendering of patent and Crew cards (full and mini). */
(function (HF) {
  'use strict';

  const UI = HF.ui;
  const esc = HF.escape;
  const GRADE_COLOR = { water: '#2f86e0', dirt: '#8c8c8c', isotope: '#d6a520' };
  const GEN = { electric: ['⚡E', 'Electric generator'], pulsed: ['⚡P', 'Pulsed generator'] };
  const RCT = { stationary: ['☢S', 'Stationary reactor'], burst: ['☢B', 'Burst-plasma reactor'], exotic: ['☢X', 'Exotic reactor'] };

  UI.thrustSvg = function (t, size) {
    if (!t) return '';
    size = size || 64;
    const col = GRADE_COLOR[t.g] || '#888';
    const h = size * 0.86;
    return '<svg class="thrust-tri" width="' + size + '" height="' + (h + 6) + '" viewBox="0 0 100 92" data-tip="' +
      esc('Thrust triangle: base thrust ' + t.t + ', fuel consumption ' + HF.fmtNum(t.f) + ' steps/burn, ' + HF.cards.gradeName(t.g) + ' grade' +
        (t.ab ? '. Afterburn: +1 thrust for ' + t.ab + ' fuel steps' : '') + (t.push ? '. Pushable (+1 with a Powersat)' : '') +
        (t.pivots ? '. ' + t.pivots + ' Bonus Pivot' : '')) + '">' +
      '<polygon points="50,4 97,88 3,88" fill="' + col + '" stroke="#111" stroke-width="3"/>' +
      '<text x="30" y="76" class="tt-num">' + t.t + '</text>' +
      '<text x="70" y="76" class="tt-num">' + HF.fmtNum(t.f) + '</text>' +
      (t.ab ? '<circle cx="50" cy="22" r="11" fill="#ff7a1a" stroke="#111" stroke-width="2"/><text x="50" y="27" class="tt-ab">' + t.ab + '</text>' : '') +
      (t.push ? '<text x="50" y="58" class="tt-push">⇉</text>' : '<circle cx="50" cy="56" r="3" fill="#111"/>') +
      (t.pivots ? '<text x="88" y="24" class="tt-piv">✦</text>' : '') +
      '</svg>';
  };

  UI.reqBadges = function (req) {
    if (!req) return '';
    const out = [];
    if (req.gen && req.gen.length) out.push('<span class="badge gen" data-tip="Needs ' + req.gen.map(g => GEN[g][1]).join(' or ') + '">' + req.gen.map(g => GEN[g][0]).join('/') + '</span>');
    if (req.reactor && req.reactor.length) out.push('<span class="badge rct" data-tip="Needs ' + req.reactor.map(g => RCT[g][1]).join(' or ') + '">' + req.reactor.map(g => RCT[g][0]).join('/') + '</span>');
    if (req.therms) out.push('<span class="badge therm" data-tip="Needs ' + req.therms + ' Therm' + (req.therms > 1 ? 's' : '') + ' of cooling">❄' + req.therms + '</span>');
    return out.join('');
  };

  UI.provBadge = function (s) {
    const out = [];
    for (const g of (s.prov && s.prov.gen) || []) out.push('<span class="badge prov gen" data-tip="Provides: ' + GEN[g][1] + '">' + GEN[g][0] + '</span>');
    for (const r of (s.prov && s.prov.reactor) || []) out.push('<span class="badge prov rct" data-tip="Provides: ' + RCT[r][1] + '">' + RCT[r][0] + '</span>');
    if (s.deck === 'radiator') out.push('<span class="badge prov therm" data-tip="Provides cooling Therms">❄' + (s.therms || 0) + '</span>');
    return out.join('');
  };

  UI.modBadge = function (m) {
    if (!m) return '';
    const parts = [];
    if (m.thrust) parts.push((m.thrust > 0 ? '+' : '') + m.thrust + ' thrust');
    if (m.fuelMul && m.fuelMul !== 1) parts.push('×' + HF.fmtNum(m.fuelMul) + ' fuel');
    if (m.solar) parts.push('☀ solar thrust');
    if (!parts.length) return '';
    return '<span class="badge wrench" data-tip="Movement-modifying support (J5): applies when in the activated thruster\'s support chain">🔧 ' + parts.join(', ') + '</span>';
  };

  UI.isruBadge = function (isru) {
    if (!isru) return '';
    const kinds = HF.cards.platforms(isru);
    const ic = kinds.map(k => ({ raygun: '☄', buggy: '⛟', missile: '➶' }[k] || '')).join('');
    return '<span class="badge isru" data-tip="ISRU ' + isru.r + ' (' + kinds.join(' + ') + ' platform' + (kinds.length > 1 ? 's' : '') +
      '): can prospect & refuel on Sites with Hydration ≥ ' + isru.r + '">' + ic + ' ISRU ' + isru.r + '</span>';
  };

  /**
   * Original card art (assets/cards, extracted by tools/extract_cards.py) for a
   * stats object, or null when art is switched off / not applicable. A missing
   * file silently falls back to the drawn card (see cardFull).
   */
  UI.artSrc = function (s) {
    if (HF.app && HF.app.settings && HF.app.settings.cardArt === false) return null;
    if (s.deck === 'crew') return s.crewId ? HF.cards.crewImage(s.crewId, s.face) : null;
    return s.cardId && s.cardId !== 'crew' ? HF.cards.image(s.cardId, s.sideKey) : null;
  };

  /**
   * Stats of the other side of a card: a patent's Black-Side for its White-Side
   * and back (same radiator orientation), a Crew card's other faction. Null for
   * anything without a reverse. Shown while Alt is held (body.flip).
   */
  UI.otherSide = function (s) {
    if (!s) return null;
    if (s.deck === 'crew') return s.crewId != null && s.face != null ? HF.cards.stats({ crew: s.crewId, face: 1 - s.face }) : null;
    if (!s.cardId || s.cardId === 'crew') return null;
    return HF.cards.stats({ id: s.cardId, side: s.sideKey === 'b' ? 'w' : 'b', orient: s.orient });
  };

  /** Tooltip HTML showing a card's art (used on mini cards); holding Alt shows the other side. */
  UI.artTip = function (s) {
    const src = UI.artSrc(s);
    if (!src) return '';
    const o = UI.otherSide(s);
    const osrc = o && UI.artSrc(o);
    const img = (x, path, cls) => '<img class="tip-card' + (x.orient === 'heavy' ? ' rot' : '') + (cls || '') + '" src="' + path + '" onerror="this.remove()">';
    return '<div class="tip-cards' + (osrc ? ' has-alt' : '') + '">' + img(s, src, ' front-side') + (osrc ? img(o, osrc, ' alt-side') : '') + '</div>' +
      (osrc ? '<div class="tip-alt">hold <kbd>Alt</kbd> for the other side</div>' : '');
  };

  /**
   * Full card. s = stats object. opts: { status, statusOk, footer, cls, attrs, noArt }
   * With card art available the original card image is shown; the drawn card is
   * the fallback. Radiators in their heavy orientation are shown rotated (the
   * printed upright half is the light orientation).
   */
  UI.cardFull = function (s, opts) {
    opts = opts || {};
    const art = opts.noArt ? null : UI.artSrc(s);
    const o = opts.noAlt ? null : UI.otherSide(s);
    const oart = o && !opts.noArt ? UI.artSrc(o) : null;
    const extra = (opts.status ? '<div class="card-status ' + (opts.statusOk ? 'ok' : 'bad') + '">' + esc(opts.status) + '</div>' : '') + (opts.footer || '');
    return '<div class="cardwrap' + (s.orient === 'heavy' ? ' rot' : '') + (o ? ' has-alt' : '') + ' ' + (opts.cls || '') + '" ' + (opts.attrs || '') + '>' +
      (art ? '<img class="card-art" src="' + art + '" alt="' + esc(s.name) + '" draggable="false" onload="this.parentNode.classList.add(\'art-ok\')" onerror="this.remove()">' : '') +
      UI.drawnCard(s, '') +
      (oart ? '<img class="card-art alt-side" src="' + oart + '" alt="' + esc(o.name) + '" draggable="false" onload="this.parentNode.classList.add(\'alt-art-ok\')" onerror="this.remove()">' : '') +
      (o ? UI.drawnCard(o, ' alt-side') : '') +
      extra + '</div>';
  };

  /** The drawn card (when there is no art, or art is switched off). */
  UI.drawnCard = function (s, cls) {
    const info = HF.cards.DECK_INFO[s.deck];
    const isCrew = s.deck === 'crew';
    const side = isCrew ? 'crew' : (s.sideKey === 'b' ? 'b' : 'w');
    const light = s.light ? '<div class="orient" data-tip="Radiator orientations: heavy / light">heavy ' + (s.mass) + 'M ❄' + s.therms +
      ' · light ' + s.light.mass + 'M ❄' + s.light.therms + '</div>' : '';
    return '<div class="card side-' + side + (cls || '') + '" style="--deck:' + info.color + '">' +
      '<div class="card-band"><span>' + info.name.replace(/s$/, '') + (isCrew ? '' : (side === 'b' ? ' · Black-Side' : ' · White-Side')) + '</span>' +
      (s.spec ? '<span class="spec" data-tip="Spectral type ' + s.spec + (s.spec === 'ANY' ? ' (any Factory)' : ' - ET produced at ' + s.spec + ' Factories') + '">' + s.spec + '</span>' : '') + '</div>' +
      '<div class="card-stats"><span class="mass" data-tip="Mass (boost cost, Dry Mass)">' + (s.mass ?? 0) + '</span>' +
      '<span class="rad" data-tip="Rad-hardness">' + (s.rad ?? '-') + '</span>' + UI.provBadge(s) +
      (s.solar ? '<span class="badge solar" data-tip="Solar-Powered (H3c): thrust modified by zone, not Operational in Neptune zone">☀</span>' : '') + '</div>' +
      '<div class="card-name">' + esc(s.name) + '</div>' +
      (isCrew ? '<div class="crew-sub">' + esc(s.engine || '') + ' · clout ' + esc(s.clout) + '</div>' : '') +
      '<div class="card-body">' +
      '<div class="card-left">' + UI.reqBadges(s.req) + UI.isruBadge(s.isru) + UI.modBadge(s.mod) + light + '</div>' +
      '<div class="card-right">' + UI.thrustSvg(s.thrust, 58) + (isCrew ? '<div class="humans" data-tip="Humans on board">▲</div>' : '') + '</div>' +
      '</div>' +
      (s.text ? '<div class="card-text">' + esc(s.text) + '</div>' : '') +
      (isCrew && s.privilege ? '<div class="card-text priv"><b>' + esc(HF_RULES.privileges[s.privilege].name) + ':</b> ' + esc(HF_RULES.privileges[s.privilege].text) + '</div>' : '') +
      '</div>';
  };

  /** One-line card for stack listings. inst = card instance. */
  UI.cardMini = function (inst, opts) {
    opts = opts || {};
    const s = HF.cards.stats(inst);
    const info = HF.cards.DECK_INFO[s.deck];
    const t = s.thrust ? '<span class="mc-thrust" style="--g:' + (GRADE_COLOR[s.thrust.g] || '#888') + '">' + s.thrust.t + '·' + HF.fmtNum(s.thrust.f) + '</span>' : '';
    const tags = [];
    if (s.isru) tags.push('ISRU ' + s.isru.r);
    for (const g of (s.prov && s.prov.gen) || []) tags.push(GEN[g][0]);
    for (const r of (s.prov && s.prov.reactor) || []) tags.push(RCT[r][0]);
    if (s.deck === 'radiator') tags.push('❄' + s.therms + (inst.orient === 'light' ? ' light' : ''));
    if (inst.glory) tags.push('★ ' + HF.zone(inst.glory).name + ' glory');
    const o = UI.otherSide(s);
    return '<div class="mcard side-' + (inst.crew ? 'crew' : inst.side) + (o ? ' has-alt' : '') + (opts.selected ? ' selected' : '') + (opts.cls ? ' ' + opts.cls : '') +
      '" style="--deck:' + info.color + '" ' + (opts.attrs || '') + '>' +
      (opts.checkbox ? '<input type="checkbox" class="mc-check" ' + (opts.selected ? 'checked' : '') + '>' : '') +
      '<span class="mc-mass front-side" data-tip="Mass">' + (s.mass ?? 0) + '</span>' + (o ? '<span class="mc-mass alt-side" data-tip="Mass (other side)">' + (o.mass ?? 0) + '</span>' : '') +
      '<span class="mc-name front-side"' + (UI.artSrc(s) ? ' data-tip="' + esc(UI.artTip(s)) + '"' : '') + '>' + esc(s.name) + '</span>' +
      (o ? '<span class="mc-name alt-side">' + esc(o.name) + '</span>' : '') + t +
      '<span class="mc-tags">' + tags.join(' · ') + '</span>' +
      (opts.status != null ? '<span class="mc-status ' + (opts.statusOk ? 'ok' : 'bad') + '" data-tip="' + esc(opts.status) + '">' + (opts.statusOk ? '●' : '○') + '</span>' : '') +
      '</div>';
  };

  /** Modal with both sides of a patent card. */
  UI.showCard = function (id) {
    const w = HF.cards.sideStats(id, 'w'), b = HF.cards.sideStats(id, 'b');
    UI.modal({ title: esc(w.name), body: '<div class="card-pair">' + UI.cardFull(w) + UI.cardFull(b) + '</div>' +
      '<p class="hint">White-Side is built on Earth and boosted to LEO; Black-Side is ET-produced at a Factory of spectral type <b>' + b.spec + '</b>.</p>' });
  };
})(window.HF = window.HF || {});
