/*
 * Minimap: a thumbnail of the whole board with the current viewport and the
 * players' figures. Click or drag on it to move the view.
 */
(function (HF) {
  'use strict';

  const UI = HF.ui;
  const S = HF.state;
  const MAP = HF.map;

  const MM = {
    el: null, w: 210,

    mount(container) {
      const h = Math.round(MM.w * MAP.H / MAP.W);
      container.insertAdjacentHTML('beforeend',
        '<div class="minimap" style="width:' + MM.w + 'px;height:' + h + 'px" data-tip="Minimap: click or drag to move the view">' +
        '<div class="mm-img"></div><svg class="mm-svg" viewBox="0 0 ' + MAP.W + ' ' + MAP.H + '"></svg><div class="mm-view"></div>' +
        '<button class="mm-x" title="Hide minimap">−</button></div>' +
        '<button class="icon-btn mm-show" data-tip="Show the minimap">⧉</button>');
      MM.el = container.querySelector('.minimap');
      MM.h = h;
      let drag = false;
      const go = (e) => {
        const r = MM.el.getBoundingClientRect();
        const fx = (e.clientX - r.left) / r.width, fy = (e.clientY - r.top) / r.height;
        UI.board.centerOnPoint(fx * MAP.W, fy * MAP.H);
      };
      MM.el.addEventListener('pointerdown', e => {
        if (e.target.closest('.mm-x')) return;
        drag = true; MM.el.setPointerCapture(e.pointerId); go(e); e.stopPropagation();
      });
      MM.el.addEventListener('pointermove', e => { if (drag) go(e); });
      MM.el.addEventListener('pointerup', () => { drag = false; });
      MM.el.querySelector('.mm-x').addEventListener('click', () => MM.setHidden(true));
      container.querySelector('.mm-show').addEventListener('click', () => MM.setHidden(false));
      MM.setHidden(!!(HF.app.settings && HF.app.settings.minimapHidden), true);
    },

    setHidden(hidden, silent) {
      if (!MM.el) return;
      MM.el.style.display = hidden ? 'none' : '';
      MM.el.parentElement.querySelector('.mm-show').style.display = hidden ? '' : 'none';
      MM.el.parentElement.style.setProperty('--mm-h', hidden ? '0px' : (MM.h + 10) + 'px');
      if (!silent) { HF.app.settings.minimapHidden = hidden; HF.app.saveSettings(); }
    },

    /** Called by the board after every pan / zoom. */
    view(v, vpW, vpH) {
      if (!MM.el) return;
      const k = MM.w / MAP.W;
      const x = -v.x / v.s, y = -v.y / v.s, w = vpW / v.s, h = vpH / v.s;
      const r = MM.el.querySelector('.mm-view');
      r.style.left = Math.max(0, x * k) + 'px';
      r.style.top = Math.max(0, y * k) + 'px';
      r.style.width = Math.min(MM.w, w * k) + 'px';
      r.style.height = Math.min(MM.h, h * k) + 'px';
    },

    render(G) {
      if (!MM.el) return;
      let s = '';
      G.players.forEach((p, pi) => {
        for (const key of ['rocket', 'out1', 'out2']) {
          const st = p.stacks[key];
          if (!st) continue;
          const n = MAP.node(st.node);
          s += key === 'rocket'
            ? '<circle cx="' + n.px + '" cy="' + n.py + '" r="' + (pi === G.current ? 95 : 70) + '" fill="' + S.color(p) + '" stroke="#000" stroke-width="22"/>'
            : '<rect x="' + (n.px - 50) + '" y="' + (n.py - 50) + '" width="100" height="100" fill="' + S.color(p) + '" stroke="#000" stroke-width="18"/>';
        }
      });
      if (G.move) {
        const n = MAP.node(G.move.node);
        s += '<circle cx="' + n.px + '" cy="' + n.py + '" r="150" fill="none" stroke="#4dff9a" stroke-width="30"/>';
      }
      MM.el.querySelector('.mm-svg').innerHTML = s;
    }
  };

  UI.minimap = MM;
})(window.HF = window.HF || {});
