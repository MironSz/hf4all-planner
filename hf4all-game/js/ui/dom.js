/* DOM helpers: modals, toasts, tooltip, dice overlay. */
(function (HF) {
  'use strict';

  const esc = HF.escape;

  const UI = HF.ui = HF.ui || {};

  UI.$ = (sel, root) => (root || document).querySelector(sel);
  UI.$$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));

  /** Player colour swatch. */
  UI.dot = function (p, size) {
    return '<span class="pdot" style="--pc:' + HF.state.color(p) + (size ? ';--ps:' + size + 'px' : '') + '"></span>';
  };
  UI.pname = function (p) { return UI.dot(p) + '<b class="pname">' + esc(p.name) + '</b>'; };

  /* ---------- toasts ---------- */
  UI.toast = function (msg, kind) {
    const root = document.getElementById('toast-root');
    const el = document.createElement('div');
    el.className = 'toast ' + (kind || 'info');
    el.textContent = msg;
    root.appendChild(el);
    setTimeout(() => el.classList.add('gone'), 3800);
    setTimeout(() => el.remove(), 4400);
  };

  /* ---------- modal ---------- */
  let modalStack = [];
  /**
   * Open a modal. spec: { title, body (html), wide, buttons: [{ label, kind, onClick(close), disabled }],
   * onMount(root), dismissable (default true) }
   */
  UI.modal = function (spec) {
    const root = document.getElementById('modal-root');
    const wrap = document.createElement('div');
    wrap.className = 'modal-wrap';
    wrap.innerHTML =
      '<div class="modal ' + (spec.wide ? 'wide ' : '') + (spec.cls || '') + '" role="dialog" aria-modal="true">' +
      '<div class="modal-head"><h2>' + spec.title + '</h2>' +
      (spec.dismissable === false ? '' : '<button class="icon-btn modal-x" title="Close">✕</button>') + '</div>' +
      '<div class="modal-body">' + (spec.body || '') + '</div>' +
      '<div class="modal-foot"></div></div>';
    const foot = wrap.querySelector('.modal-foot');
    const close = () => { wrap.remove(); modalStack = modalStack.filter(x => x !== wrap); if (spec.onClose) spec.onClose(); };
    (spec.buttons || []).forEach(b => {
      const btn = document.createElement('button');
      btn.className = 'btn ' + (b.kind || '');
      btn.innerHTML = b.label;
      if (b.disabled) btn.disabled = true;
      if (b.id) btn.id = b.id;
      btn.addEventListener('click', () => b.onClick ? b.onClick(close, wrap) : close());
      foot.appendChild(btn);
    });
    if (!spec.buttons || !spec.buttons.length) foot.remove();
    if (spec.dismissable !== false) {
      wrap.querySelector('.modal-x').addEventListener('click', close);
      wrap.addEventListener('mousedown', e => { if (e.target === wrap) close(); });
    }
    root.appendChild(wrap);
    modalStack.push(wrap);
    if (spec.onMount) spec.onMount(wrap.querySelector('.modal'), close);
    return { close, root: wrap.querySelector('.modal') };
  };
  UI.closeAllModals = function () { UI.$$('#modal-root .modal-wrap').forEach(m => m.remove()); modalStack = []; };
  UI.hasModal = function () { return modalStack.length > 0; };

  UI.confirm = function (title, html, okLabel, onOk, kind) {
    UI.modal({ title, body: html, buttons: [
      { label: 'Cancel' },
      { label: okLabel || 'OK', kind: kind || 'primary', onClick: c => { c(); onOk(); } }] });
  };

  /** Number picker modal. */
  UI.askNumber = function (title, html, min, max, initial, okLabel, onOk) {
    UI.modal({
      title, body: html + '<div class="num-row"><input type="range" min="' + min + '" max="' + max + '" value="' + initial + '" class="num-range">' +
        '<input type="number" min="' + min + '" max="' + max + '" value="' + initial + '" class="num-input"></div>',
      buttons: [{ label: 'Cancel' }, { label: okLabel || 'OK', kind: 'primary', onClick: (c, w) => { const v = +w.querySelector('.num-input').value; c(); onOk(v); } }],
      onMount(root) {
        const r = root.querySelector('.num-range'), n = root.querySelector('.num-input');
        r.addEventListener('input', () => { n.value = r.value; });
        n.addEventListener('input', () => { r.value = n.value; });
      }
    });
  };

  /* ---------- tooltip ---------- */
  let tipEl = null, tipHtml = null, tipAt = null;
  // Beside the pointer on whichever side has room, and never past the window's edges.
  const placeTip = () => {
    if (!tipEl || tipEl.style.display === 'none' || !tipAt) return;
    const r = tipEl.getBoundingClientRect();
    const W = window.innerWidth, H = window.innerHeight, m = 6;
    let left = tipAt.x + 16 + r.width <= W - m ? tipAt.x + 16 : tipAt.x - r.width - 16;
    let top = tipAt.y + 14 + r.height <= H - m ? tipAt.y + 14 : tipAt.y - r.height - 14;
    tipEl.style.left = Math.max(m, Math.min(left, W - r.width - m)) + 'px';
    tipEl.style.top = Math.max(m, Math.min(top, H - r.height - m)) + 'px';
  };
  UI.tip = function (html, x, y) {
    if (!tipEl) { tipEl = document.createElement('div'); tipEl.className = 'tooltip'; document.body.appendChild(tipEl); }
    if (!html) { tipEl.style.display = 'none'; tipHtml = null; return; }
    if (html !== tipHtml) {
      tipHtml = html;
      tipEl.innerHTML = html;
      // A card picture grows the tip when it arrives: place it again then.
      tipEl.querySelectorAll('img').forEach(img => { if (!img.complete) img.addEventListener('load', placeTip, { once: true }); });
    }
    tipEl.style.display = 'block';
    tipAt = { x, y };
    placeTip();
  };
  // Generic [data-tip] hover tooltips.
  document.addEventListener('mouseover', e => {
    const t = e.target.closest && e.target.closest('[data-tip]');
    if (t) UI.tip(t.getAttribute('data-tip'), e.clientX, e.clientY);
  });
  document.addEventListener('mousemove', e => {
    const t = e.target.closest && e.target.closest('[data-tip]');
    if (t) UI.tip(t.getAttribute('data-tip'), e.clientX, e.clientY);
  });
  document.addEventListener('mouseout', e => {
    const t = e.target.closest && e.target.closest('[data-tip]');
    if (t && !(e.relatedTarget && t.contains(e.relatedTarget))) UI.tip(null);
  });

  /* ---------- dice ---------- */
  const PIPS = { 1: [4], 2: [0, 8], 3: [0, 4, 8], 4: [0, 2, 6, 8], 5: [0, 2, 4, 6, 8], 6: [0, 2, 3, 5, 6, 8] };
  UI.dieHtml = function (v, cls) {
    const img = UI.gfx && UI.gfx.die(v);
    if (img) return '<div class="die img ' + (cls || '') + '"><img src="' + img + '" alt="' + v + '"></div>';
    let s = '<div class="die ' + (cls || '') + '">';
    for (let i = 0; i < 9; i++) s += '<span class="' + (PIPS[v].includes(i) ? 'pip' : '') + '"></span>';
    return s + '</div>';
  };

  /** Show a batch of rolls [{label, value}] in a dismissable overlay. */
  UI.showRolls = function (rolls, after) {
    if (!rolls || !rolls.length) { if (after) after(); return; }
    const G = HF.app.G;
    const body = '<div class="rolls">' + rolls.map((r, i) => {
      const p = r.player != null && G ? G.players[r.player] : null;
      return '<div class="roll" style="animation-delay:' + (i * 0.25) + 's">' + UI.dieHtml(r.value, 'tumble') +
        '<div class="roll-label">' + (p ? UI.dot(p) + ' ' : '') + esc(r.label) + '</div></div>';
    }).join('') + '</div>';
    UI.modal({ title: 'Dice', body, cls: 'dice-modal', buttons: [{ label: 'Continue', kind: 'primary', onClick: c => c() }], onClose: after });
  };
})(window.HF = window.HF || {});
