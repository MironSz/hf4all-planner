/* SVG rendering of a Rocket's Fuel Strip (playmat, F). */
(function (HF) {
  'use strict';

  const UI = HF.ui;
  const FS = HF.FS;
  const CLASS_COL = { Wisp: '#7fd6ff', Probe: '#7fffa8', Scout: '#f4f1a0', Transport: '#ffb36b', Tug: '#ff7b7b' };

  /**
   * The original Fuel Strip (cut out of the rulebook; image and the position of
   * every black-line step come from the planner, tools/build_fuelstrip.py) with
   * the Dry and Wet Mass chits. fuel: Rocket fuel object; opts: { color (player
   * colour), pending: fractional steps about to be spent (ghost Wet chit) }.
   * The strip image is the first element of the one SVG, so the step positions
   * (fractions of the image) and the chits drawn on them can never drift apart,
   * whatever size the strip is shown at.
   */
  UI.fuelStrip = function (fuel, opts) {
    const D = window.HF_FUEL_STRIP;
    if (!D) return UI.fuelStripDrawn(fuel, opts);
    opts = opts || {};
    const W = D.w, H = D.h;
    const at = st => { const p = D.steps[Math.max(0, Math.min(FS.MAX_STEP, st))]; return [p[0] * W, p[1] * H]; };
    const cw = 62, ch = 31;  // chit size in image pixels; the printed ovals are about 48 x 27
    const OVAL_RX = 27, OVAL_RY = 16;  // fuel highlight: a ring just around a printed oval
    const vcol = Object.keys(HF_RULES.playerColors).find(k => HF_RULES.playerColors[k] === opts.color);
    const dryImg = vcol && UI.gfx && UI.gfx.src('Dry_' + (vcol === 'gray' ? 'grey' : vcol) + '.png');
    const wetImg = fuel && UI.gfx && UI.gfx.wet ? UI.gfx.wet(fuel.grade) : null;
    const GRADE = { water: '#2f86e0', dirt: '#8c8c8c', isotope: '#d6a520' };
    const chit = (x, y, img, fill, text, tip, cls) => '<g class="fs-chit' + (cls || '') + '" data-tip="' + HF.escape(tip) + '">' +
      '<ellipse cx="' + x.toFixed(1) + '" cy="' + y.toFixed(1) + '" rx="' + (cw / 2 + 5) + '" ry="' + (ch / 2 + 5) + '" class="fs-halo"/>' + (img
      ? '<image href="' + img + '" x="' + (x - cw / 2).toFixed(1) + '" y="' + (y - ch / 2).toFixed(1) + '" width="' + cw + '" height="' + ch + '"/>'
      : '<ellipse cx="' + x.toFixed(1) + '" cy="' + y.toFixed(1) + '" rx="' + cw / 2 + '" ry="' + ch / 2 + '" fill="' + fill + '" stroke="#111" stroke-width="4"/>' +
        '<text x="' + x.toFixed(1) + '" y="' + (y + 6).toFixed(1) + '" class="fs-chit-txt">' + text + '</text>') + '</g>';
    let s = '<svg class="fs-orig" viewBox="0 0 ' + W + ' ' + H + '" style="aspect-ratio:' + W + '/' + H + ';--pc:' + (opts.color || '#fff') + '">' +
      '<image href="' + D.img + '" x="0" y="0" width="' + W + '" height="' + H + '"/>';
    if (fuel) {
      const dryStep = FS.massToStep(fuel.dry);
      // The fuel on board: every black-line step between the chits.
      for (let st = dryStep + 1; st < fuel.wet; st++) {
        const [x, y] = at(st);
        s += '<ellipse cx="' + x.toFixed(1) + '" cy="' + y.toFixed(1) + '" rx="' + OVAL_RX + '" ry="' + OVAL_RY + '" class="fs-fuel-dot" stroke="' + (GRADE[fuel.grade] || GRADE.water) + '"/>';
      }
      const [dx, dy] = at(dryStep);
      const [wx, wy] = at(fuel.wet);
      const same = dryStep === fuel.wet;
      s += chit(dx, dy + (same ? 16 : 0), dryImg, opts.color || '#fff', 'DRY', 'Dry Mass chit: ' + fuel.dry, ' dry');
      if (opts.pending) {
        const ghost = Math.max(dryStep, fuel.wet - Math.ceil(opts.pending - 1e-9));
        const [gx, gy] = at(ghost);
        s += chit(gx, gy, wetImg, GRADE[fuel.grade] || GRADE.water, 'WET', 'Wet Mass after this move: ' + FS.label(ghost), ' ghost');
      }
      s += chit(wx, wy - (same ? 16 : 0), wetImg, GRADE[fuel.grade] || GRADE.water, 'WET',
        'Wet Mass chit: ' + FS.label(fuel.wet) + ' (' + FS.fuelSteps(fuel) + ' fuel steps, ' + (fuel.grade ? HF.cards.gradeName(fuel.grade) : 'empty') + ')', ' wet');
    }
    return s + '</svg>';
  };

  /** Drawn Fuel Strip (fallback when data/fuelstrip.js is missing). */
  UI.fuelStripDrawn = function (fuel, opts) {
    opts = opts || {};
    const Wd = 560, Hd = 118, padL = 12, padR = 12;
    const x = st => padL + st * (Wd - padL - padR) / FS.MAX_STEP;
    let s = '<svg class="fuelstrip" viewBox="0 0 ' + Wd + ' ' + Hd + '" width="100%">';
    let prev = 0;
    for (const c of FS.CLASSES) {
      s += '<rect x="' + x(prev) + '" y="8" width="' + (x(c.top) - x(prev)) + '" height="20" fill="' + CLASS_COL[c.name] + '" opacity=".75"/>' +
        '<text x="' + ((x(prev) + x(c.top)) / 2) + '" y="23" class="fs-class">' + c.name + ' ' + (c.mod > 0 ? '+' : '') + c.mod + '</text>';
      prev = c.top;
    }
    s += '<line x1="' + x(0) + '" y1="52" x2="' + x(FS.MAX_STEP) + '" y2="52" class="fs-line"/>';
    for (let st = 0; st <= FS.MAX_STEP; st++) s += '<line x1="' + x(st) + '" y1="48" x2="' + x(st) + '" y2="56" class="fs-tick"/>';
    for (let m = 1; m <= FS.MAX_MASS; m++) {
      if ((m > 10 && m < 16 && m % 2 === 1) || (m >= 16 && m % 4 !== 0)) continue;
      const st = FS.massToStep(m);
      s += '<line x1="' + x(st) + '" y1="40" x2="' + x(st) + '" y2="60" class="fs-mass-tick"/><text x="' + x(st) + '" y="76" class="fs-mass">' + m + '</text>';
    }
    const d23 = FS.massToStep(FS.MAX_DRY_TO_MOVE);
    s += '<line x1="' + x(d23) + '" y1="34" x2="' + x(d23) + '" y2="64" class="fs-max"/><text x="' + x(d23) + '" y="104" class="fs-note">max dry</text>';
    if (fuel) {
      const dry = FS.massToStep(fuel.dry);
      const col = opts.color || '#fff';
      s += '<line x1="' + x(dry) + '" y1="52" x2="' + x(fuel.wet) + '" y2="52" class="fs-fuel" stroke="' + ({ water: '#3da0ff', dirt: '#a0a0a0', isotope: '#f0c000' }[fuel.grade] || '#3da0ff') + '"/>';
      if (opts.pending) {
        const ghost = fuel.wet - opts.pending;
        s += '<line x1="' + x(ghost) + '" y1="52" x2="' + x(fuel.wet) + '" y2="52" class="fs-spent"/>';
        s += '<circle cx="' + x(ghost) + '" cy="52" r="7" class="fs-ghost"/>';
      }
      s += '<rect x="' + (x(dry) - 9) + '" y="80" width="18" height="18" fill="' + col + '" stroke="#111" stroke-width="2" rx="2" data-tip="Dry Mass chit: ' + fuel.dry + '"/>' +
        '<text x="' + x(dry) + '" y="93" class="fs-chit-t">D</text>';
      s += '<circle cx="' + x(fuel.wet) + '" cy="52" r="10" fill="' + ({ water: '#2f86e0', dirt: '#8c8c8c', isotope: '#d6a520' }[fuel.grade] || '#2f86e0') +
        '" stroke="#fff" stroke-width="2.5" data-tip="Wet Mass chit: ' + FS.label(fuel.wet) + '"/><text x="' + x(fuel.wet) + '" y="56" class="fs-chit-t">W</text>';
    }
    return s + '</svg>';
  };

  UI.fuelSummary = function (fuel) {
    if (!fuel) return 'No Fuel Strip';
    const wc = FS.weightClass(fuel.wet);
    return 'Dry <b>' + fuel.dry + '</b> · Wet <b>' + FS.label(fuel.wet) + '</b> · Fuel <b>' + FS.fuelSteps(fuel) + '</b> steps (' +
      (fuel.grade ? HF.cards.gradeName(fuel.grade) : 'empty') + ') · <b>' + wc.name + '</b> ' + (wc.mod > 0 ? '+' : '') + wc.mod;
  };
})(window.HF = window.HF || {});
