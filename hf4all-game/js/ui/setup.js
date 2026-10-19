/* Title screen and new-game setup (C: Core Setup). */
(function (HF) {
  'use strict';

  const UI = HF.ui;
  const esc = HF.escape;

  function shuffled(arr) {
    const a = arr.slice();
    for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; }
    return a;
  }

  const SU = {
    st: null,

    title(root) {
      root.innerHTML =
        '<div class="title-screen"><div class="title-bg"></div><div class="title-card">' +
        '<div class="logo">HIGH <span>FRONTIER</span></div><div class="logo-sub">4 ALL · hot-seat table</div>' +
        '<p class="tagline">Bid for patents, boost them to LEO, fly water-hungry rockets across the solar system, prospect, industrialize - and outlive your rivals for 48 years.</p>' +
        '<div class="title-btns">' +
        '<button class="btn primary big" data-t="new">New hot-seat game</button>' +
        '<button class="btn big" data-t="demo">🎬 Demo scenarios…</button>' +
        '<button class="btn big" data-t="load">Load game file…</button>' +
        '<button class="btn big ghost" data-t="rules">Rules summary</button></div>' +
        '<div class="saved-games"></div>' +
        '<p class="small muted">Core game only (no modules). One browser window, every player takes turns at the same screen; the game server keeps every game. ' +
        'Original card art: run <code>python tools/extract_cards.py --download</code> once (see README).</p></div></div>';
      root.querySelector('.title-card').addEventListener('click', e => {
        const b = e.target.closest('button');
        if (!b) return;
        if (b.dataset.t === 'new') HF.app.showSetup();
        if (b.dataset.t === 'load') HF.app.loadFile();
        if (b.dataset.t === 'demo') HF.app.demoPicker();
        if (b.dataset.t === 'rules') UI.modal({ title: 'Rules summary', wide: true, body: UI.panels.rules() });
        if (b.dataset.resume) HF.app.resume(b.dataset.resume);
        if (b.dataset.del) {
          UI.confirm('Delete game', 'Delete this game from the server for good?', 'Delete', () =>
            HF.api.remove(b.dataset.del).then(() => SU.title(root)).catch(err => UI.toast(err.message, 'bad')), 'danger');
        }
      });
      SU.savedGames(root.querySelector('.saved-games'));
    },

    /** The games kept by the server, most recent first. */
    savedGames(el) {
      HF.api.games().then(list => {
        if (!list.length) return;
        el.innerHTML = '<div class="sg-h">Continue a game</div>' + list.slice(0, 8).map(g =>
          '<div class="sg-row"><button class="btn sm primary" data-resume="' + esc(g.id) + '">Resume ▸</button>' +
          '<span class="sg-who">' + g.players.map(p => '<span class="pdot" style="--pc:' + (HF_RULES.playerColors[p.color] || '#888') + '"></span>' + esc(p.name)).join(', ') + '</span>' +
          '<span class="muted small">' + (g.gameOver ? 'finished' : 'year ' + g.year + '/' + g.totalYears) + ' · ' + esc((g.updated || '').replace('T', ' ').slice(0, 16)) + '</span>' +
          '<button class="icon-btn sg-del" data-del="' + esc(g.id) + '" title="Delete">✕</button></div>').join('');
      }).catch(e => { el.innerHTML = '<p class="bad small">Game server: ' + esc(e.message) + '</p>'; });
    },

    setup(root) {
      if (!SU.st) {
        SU.st = { n: 3, cycles: 4, seed: Math.floor(Math.random() * 1e9), handover: true, rows: [] };
        SU.deal();
      }
      SU.draw(root);
    },

    deal() {
      const st = SU.st;
      const crews = shuffled(HF_CARDS.crews.map(c => c.id));
      const prev = st.rows;
      st.rows = [];
      for (let i = 0; i < 5; i++) {
        st.rows.push({ name: (prev[i] && prev[i].customName) ? prev[i].name : '', customName: prev[i] && prev[i].customName, crew: crews[i], face: Math.random() < 0.5 ? 0 : 1 });
      }
    },

    draw(root) {
      const st = SU.st;
      let rows = '';
      for (let i = 0; i < st.n; i++) {
        const r = st.rows[i];
        const crew = HF.cards.crew(r.crew);
        const col = HF_RULES.playerColors[crew.color];
        rows += '<div class="setup-row" style="--pc:' + col + '"><div class="sr-head"><span class="pdot" style="--pc:' + col + ';--ps:22px"></span>' +
          '<input class="sr-name" data-i="' + i + '" placeholder="' + esc(crew.sides[r.face].faction) + '" value="' + esc(r.name) + '">' +
          '<select class="sr-crew" data-i="' + i + '">' + HF_CARDS.crews.map(c => '<option value="' + c.id + '" ' + (c.id === r.crew ? 'selected' : '') +
            (st.rows.slice(0, st.n).some((o, j) => j !== i && o.crew === c.id) ? ' disabled' : '') + '>' + HF.cap(c.color) + ' crew</option>').join('') + '</select></div>' +
          '<div class="sr-sides">' + crew.sides.map((s, f) => {
            const pr = HF_RULES.privileges[s.privilege];
            const art = UI.artSrc({ deck: 'crew', crewId: r.crew, face: f });
            return '<button class="side-tile' + (r.face === f ? ' on' : '') + '" data-i="' + i + '" data-f="' + f + '">' +
              (art ? '<img class="st-art" src="' + art + '" alt="" onerror="this.remove()">' : '') +
              '<div class="st-fac">' + (UI.gfx.logoTry(s.faction) ? '<img class="st-logo" src="' + UI.gfx.logoTry(s.faction) + '" alt="" onerror="this.remove()">' : '') + esc(s.faction) + ' <span class="clout" title="Clout (A = most)">' + s.clout + '</span></div>' +
              '<div class="st-priv"><b>' + esc(pr.name) + '</b> - ' + esc(pr.text) + '</div>' +
              '<div class="st-crew">' + UI.thrustSvg(s.thrust, 34) + '<span>' + esc(s.engine) + '<br>ISRU ' + s.isru.r + ' ' + HF.cards.platforms(s.isru).join('+') + ' · mass ' + s.mass + ' · rad ' + s.rad +
                (s.thrust ? '' : ' · no thrust triangle') + '</span></div></button>';
          }).join('') + '</div></div>';
      }
      const first = st.rows.slice(0, st.n).map((r, i) => ({ i, c: HF.cards.crew(r.crew).sides[r.face].clout })).sort((a, b) => a.c.localeCompare(b.c))[0];
      root.innerHTML =
        '<div class="setup-screen"><div class="setup-card"><h1>New game</h1>' +
        '<div class="setup-opts"><div><label>Players</label><div class="chips">' + [1, 2, 3, 4, 5].map(n =>
          '<button class="chip' + (st.n === n ? ' on' : '') + '" data-n="' + n + '">' + n + '</button>').join('') + '</div></div>' +
        '<div><label>Length</label><div class="chips">' + [[4, 'Full: 4 cycles (48 years)'], [2, 'Medium: 2 cycles (24y)'], [1, 'Taster: 1 cycle (12y)']].map(([c, l]) =>
          '<button class="chip' + (st.cycles === c ? ' on' : '') + '" data-c="' + c + '">' + l + '</button>').join('') + '</div></div>' +
        '<div><label>Seed</label><input class="seed" type="number" value="' + st.seed + '"></div>' +
        '<div><label class="chk"><input type="checkbox" class="ho" ' + (st.handover ? 'checked' : '') + '> "Pass the device" screen between turns</label></div></div>' +
        '<div class="setup-help">Each player is dealt a Crew card (C2) and secretly picks one of its two factions. Crew lives in LEO, starts with 6 Aquas ' +
        '(+2 for Secretary-General). The best clout becomes 1st Player: <b>' + esc(HF.cards.crew(st.rows[first.i].crew).sides[st.rows[first.i].face].faction) + '</b>.' +
        (st.n >= 3 ? ' Heroism chits are in play.' : ' Heroism chits are not used with fewer than 3 players.') + '</div>' +
        '<div class="setup-rows">' + rows + '</div>' +
        '<div class="setup-foot"><button class="btn ghost" data-a="back">◂ Back</button><button class="btn" data-a="deal">Deal crews again</button>' +
        '<button class="btn primary big" data-a="start">Start game ▸</button></div></div></div>';

      root.querySelector('.setup-card').addEventListener('click', e => {
        const b = e.target.closest('button');
        if (!b) return;
        if (b.dataset.n) { st.n = +b.dataset.n; SU.draw(root); }
        else if (b.dataset.c) { st.cycles = +b.dataset.c; SU.draw(root); }
        else if (b.dataset.f != null) { st.rows[+b.dataset.i].face = +b.dataset.f; SU.draw(root); }
        else if (b.dataset.a === 'deal') { SU.deal(); SU.draw(root); }
        else if (b.dataset.a === 'back') { HF.app.showTitle(); }
        else if (b.dataset.a === 'start') SU.start();
      });
      root.querySelector('.setup-card').addEventListener('change', e => {
        const t = e.target;
        if (t.classList.contains('sr-crew')) { st.rows[+t.dataset.i].crew = t.value; SU.draw(root); }
        if (t.classList.contains('seed')) st.seed = +t.value || 1;
        if (t.classList.contains('ho')) st.handover = t.checked;
      });
      root.querySelector('.setup-card').addEventListener('input', e => {
        const t = e.target;
        if (t.classList.contains('sr-name')) { st.rows[+t.dataset.i].name = t.value; st.rows[+t.dataset.i].customName = !!t.value; }
      });
    },

    start() {
      const st = SU.st;
      const players = st.rows.slice(0, st.n).map(r => ({ name: r.name.trim(), crew: r.crew, face: r.face }));
      const ids = players.map(p => p.crew);
      if (new Set(ids).size !== ids.length) return UI.toast('Each player needs a different Crew card.', 'bad');
      HF.app.settings.handover = st.handover;
      HF.app.startGame({ players, cycles: st.cycles, seed: st.seed });
      SU.st = null;
    }
  };

  UI.setup = SU;
})(window.HF = window.HF || {});
