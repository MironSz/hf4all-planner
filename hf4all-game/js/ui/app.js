/*
 * Application controller: screens, the act() -> server -> render loop, undo,
 * top bar, movement HUD, site inspector and side-panel event dispatch.
 *
 * The game lives on the game server (hf4all-game/server): App.G is the state
 * and App.V the view the server sent last. act(fn) records the commands fn
 * issues (js/net/remote.js) and sends them to the server as one step.
 */
(function (HF) {
  'use strict';

  const UI = HF.ui;
  const S = HF.state;
  const MAP = HF.map;
  const esc = HF.escape;
  const SETTINGS_KEY = 'hf4a-hotseat-settings-v1';

  const App = {
    G: null,
    V: null,
    gameId: null,
    version: 0,
    canUndo: false,
    busy: false,
    screen: 'title',
    seenRoll: 0,
    lastTurnKey: null,
    ui: { tab: 'turn', handBlack: false, logFilter: 'all', selectedNode: null, showSpaces: false },
    settings: { handover: true, cardArt: true },

    init() {
      try { Object.assign(App.settings, JSON.parse(localStorage.getItem(SETTINGS_KEY) || '{}')); } catch (e) { /* ignore */ }
      document.addEventListener('keydown', App.onKey);
      App.bindFlip();
      UI.gfx.probe(() => { if (App.screen === 'game') App.render(); });
      if (location.protocol === 'file:') return App.showOffline();
      if (HF.demo && HF.demo.fromUrl()) return;
      const m = /(?:^|[#&])g=([a-z0-9-]+)/.exec(location.hash);
      if (m) return App.resume(m[1]);
      App.showTitle();
    },

    root() { return document.getElementById('app'); },

    /**
     * Hold Alt to see the other side of every card on screen (Black-Side for
     * White-Side and back, a Crew card's other faction): every card is rendered
     * with its reverse hidden, body.flip swaps them. AltGr (Polish letters) does
     * not count; losing focus (Alt+Tab) lets go.
     */
    bindFlip() {
      const set = (on) => document.body.classList.toggle('flip', on);
      document.addEventListener('keydown', e => {
        if (e.key !== 'Alt') return;
        set(true);
        e.preventDefault();
      });
      document.addEventListener('keyup', e => {
        if (e.key !== 'Alt') return;
        set(false);
        e.preventDefault();  // keeps the browser from focusing its menu on Alt release
      });
      window.addEventListener('blur', () => set(false));
      document.addEventListener('visibilitychange', () => { if (document.hidden) set(false); });
    },

    /* ---------------- screens ---------------- */
    showTitle() { App.screen = 'title'; App.setHash(null); UI.closeAllModals(); UI.setup.title(App.root()); },
    showSetup() { App.screen = 'setup'; App.setHash(null); UI.closeAllModals(); UI.setup.setup(App.root()); },

    showOffline() {
      App.root().innerHTML = '<div class="title-screen"><div class="title-card"><div class="logo">HF<span>4A</span></div>' +
        '<p>This table needs the game server, which holds the game and runs the rules.</p>' +
        '<p>Start it in <code>hf4all-game/server</code> with <code>mvn exec:java@run</code> and open <b>http://localhost:8097/</b>.</p></div></div>';
    },

    setHash(id) {
      const h = id ? '#g=' + id : '';
      if (location.hash !== h) history.replaceState(null, '', location.pathname + location.search + h);
    },

    /** Take a server response {id, version, canUndo, state, view}. */
    apply(res) {
      App.G = res.state;
      App.V = res.view;
      App.version = res.version;
      App.canUndo = !!res.canUndo;
      App.gameId = res.id;
      App.setHash(res.id);
    },

    /** Open a server response as the game on screen. */
    open(res, quiet) {
      App.apply(res);
      App.enterGame(quiet);
    },

    /** Open a demo scenario (or the plain demo) and say what it is for. */
    async startDemo(scenario) {
      try {
        const res = await HF.api.demo(scenario);
        // The scenario's intro takes the place of the "pass the device" screen.
        App.open(res, !!res.scenario);
        if (res.scenario) App.scenarioIntro(res.scenario);
      } catch (e) { UI.toast(e.message, 'bad'); }
    },

    scenarioIntro(s) {
      UI.modal({
        title: '🎬 ' + esc(s.title), body: '<p class="scn-group-tag">' + esc(s.group) + '</p><p>' + esc(s.text) + '</p>',
        buttons: [{ label: 'Other scenarios…', onClick: c => { c(); App.demoPicker(); } }, { label: 'Start', kind: 'primary' }]
      });
    },

    /** Demo button: every scenario (one decision of the game one click away), by group. */
    async demoPicker() {
      let list;
      try { list = await HF.api.scenarios(); } catch (e) { return UI.toast(e.message, 'bad'); }
      const groups = [];
      for (const s of list) {
        let gr = groups.find(x => x.name === s.group);
        if (!gr) groups.push(gr = { name: s.group, items: [] });
        gr.items.push(s);
      }
      UI.modal({
        title: '🎬 Demo scenarios', wide: true,
        body: '<p class="hint">Each scenario is the demo game set up so that one decision or flow is one click away; where dice matter, the die is loaded (the text says so). ' +
          'Every scenario is a new game on the server.</p>' +
          groups.map(gr => '<div class="scn-group"><div class="scn-gh">' + esc(gr.name) + '</div><div class="scn-items">' +
            gr.items.map(s => '<button class="scn" data-scn="' + esc(s.id) + '"><b>' + esc(s.title) + '</b><span>' + esc(s.text) + '</span></button>').join('') + '</div></div>').join(''),
        buttons: [{ label: 'Close' }],
        onMount(root, close) {
          root.addEventListener('click', e => {
            const b = e.target.closest('[data-scn]');
            if (!b) return;
            close();
            App.startDemo(b.dataset.scn);
          });
        }
      });
    },

    /** cfg: { seed, cycles, players: [{ name, crew, face }] } */
    async startGame(cfg) {
      App.saveSettings();
      try { App.open(await HF.api.create(cfg)); } catch (e) { UI.toast(e.message, 'bad'); }
    },

    async resume(id) {
      try { App.open(await HF.api.get(id)); } catch (e) { UI.toast('Cannot open the game: ' + e.message, 'bad'); App.showTitle(); }
    },

    /** quiet: no "pass the device" screen (a demo scenario shows its intro instead). */
    enterGame(quiet) {
      App.screen = 'game';
      App.seenRoll = App.G.rollSeq || 0;
      App.lastTurnKey = App.turnKey();
      App.ui.tab = 'turn';
      App.layout();
      App.render();
      if (App.settings.handover && !App.G.gameOver && !quiet) UI.dialogs.handover(App.G);
      if (App.G.gameOver) UI.dialogs.gameOver();
    },

    saveSettings() { try { localStorage.setItem(SETTINGS_KEY, JSON.stringify(App.settings)); } catch (e) { /* ignore */ } },

    /** Download the game as a JSON file (the server keeps it anyway). */
    saveFile() {
      if (!App.gameId) return;
      const a = document.createElement('a');
      a.href = HF.api.exportUrl(App.gameId);
      a.download = 'hf4a-year' + App.G.year + '-' + App.gameId + '.json';
      document.body.appendChild(a); a.click(); a.remove();
      UI.toast('Game file downloaded.', 'good');
    },

    /** A game file becomes a new game on the server. */
    loadFile() {
      const inp = document.createElement('input');
      inp.type = 'file'; inp.accept = '.json,application/json';
      inp.onchange = () => {
        const f = inp.files[0];
        if (!f) return;
        f.text().then(t => HF.api.importState(JSON.parse(t)))
          .then(res => { App.open(res); UI.toast('Game loaded.', 'good'); })
          .catch(e => UI.toast('Cannot load: ' + e.message, 'bad'));
      };
      inp.click();
    },

    /* ---------------- act / undo ---------------- */
    turnKey() { return App.G ? App.G.year + ':' + App.G.current + ':' + (App.G.turnNo || 0) : null; },

    /**
     * Run fn(G): the commands it issues are sent to the server as one step,
     * then the new state is shown with dice / event / hand-over popups.
     * Returns a promise of true (done) or false (refused).
     */
    act(fn, after) {
      if (App.busy) { UI.toast('Waiting for the server…', 'info'); return Promise.resolve(false); }
      const batch = [];
      HF.remote.recording = batch;
      try {
        fn(App.G);
      } catch (e) {
        if (e instanceof HF.RuleError) UI.toast(e.message, 'bad');
        else { console.error(e); UI.toast('Error: ' + e.message, 'bad'); }
        return Promise.resolve(false);
      } finally {
        HF.remote.recording = null;
      }
      if (!batch.length) { App.render(); return Promise.resolve(true); }
      return App.send(batch, after);
    },

    async send(batch, after) {
      App.setBusy(true);
      let res;
      try {
        res = await HF.api.commands(App.gameId, App.version, batch);
      } catch (e) {
        App.setBusy(false);
        if (e.status === 409) {
          UI.toast('The game changed elsewhere - reloaded.', 'info');
          try { App.apply(await HF.api.get(App.gameId)); } catch (e2) { /* keep the old state */ }
        } else UI.toast(e.message, e.status === 422 ? 'bad' : 'bad');
        App.render();
        return false;
      }
      App.setBusy(false);
      App.apply(res);
      const turnChanged = App.turnKey() !== App.lastTurnKey;
      App.lastTurnKey = App.turnKey();
      if (turnChanged) { App.ui.selectedNode = null; App.ui.tab = 'turn'; UI.playmat.pi = null; }
      App.render();
      App.afterEffects(turnChanged, after, res.events || {});
      return true;
    },

    setBusy(on) {
      App.busy = on;
      document.body.classList.toggle('busy', on);
    },

    /** Ask the server a read-only question (dialog previews). */
    query(cmd, args) { return HF.api.query(App.gameId, cmd, args); },

    afterEffects(turnChanged, after, events) {
      const G = App.G;
      const rolls = G.rolls.filter(r => (r.seq || 0) > App.seenRoll);
      App.seenRoll = G.rollSeq || 0;
      const steps = [];
      if (rolls.length) steps.push(next => UI.showRolls(rolls, next));
      if (events && events.lastEvent) {
        const ev = events.lastEvent;
        steps.push(next => UI.dialogs.eventPopup(ev, next));
      }
      // Cards lost against their owner's will blow up on the map.
      if (events && events.booms && events.booms.length) steps.push(next => UI.fx.booms(events.booms, next));
      if (G.gameOver) steps.push(() => UI.dialogs.gameOver());
      else {
        if (turnChanged && App.settings.handover) steps.push(next => UI.dialogs.handover(G, next));
        if (G.pending) steps.push(() => App.openPending());
      }
      if (after) steps.push(next => { after(); next(); });
      const run = () => { const s = steps.shift(); if (s) s(run); else App.render(); };
      run();
    },

    openPending() {
      const G = App.G;
      if (!G.pending || UI.hasModal()) return;
      if (G.pending.type === 'auction') UI.auction.open();
      if (G.pending.type === 'budgetCuts') UI.dialogs.budgetCuts();
      if (G.pending.type === 'glitchEvent') UI.dialogs.glitchChoice();
      if (G.pending.type === 'padExplosion') UI.dialogs.padChoice();
    },

    async undo() {
      if (App.busy) return;
      if (!App.canUndo) return UI.toast(App.G && App.G.diceLocked ? 'Nothing to undo - dice are final.' : 'Nothing to undo.', 'info');
      App.setBusy(true);
      try {
        App.apply(await HF.api.undo(App.gameId, App.version));
        App.lastTurnKey = App.turnKey();
        UI.toast('Undone.', 'info');
      } catch (e) {
        UI.toast(e.message, 'bad');
        if (e.status === 409) { try { App.apply(await HF.api.get(App.gameId)); } catch (e2) { /* keep */ } }
      } finally {
        App.setBusy(false);
      }
      App.render();
    },

    /* ---------------- layout & render ---------------- */
    layout() {
      App.root().innerHTML =
        '<div class="game">' +
        '<header id="topbar"></header>' +
        '<main class="main"><div id="board" class="board"></div><div id="hud"></div><div id="inspector"></div><div id="dock" class="dock"></div></main>' +
        '<aside id="side" class="side"></aside>' +
        '</div>';
      UI.board.mount(document.getElementById('board'));
      UI.minimap.mount(App.root().querySelector('.main'));
      UI.dock.collapsed = !!App.settings.dockCollapsed;
      document.getElementById('dock').addEventListener('click', UI.dock.onClick);
      UI.dnd.bind(App.root().querySelector('.game'));
      App.root().querySelector('.game').insertAdjacentHTML('beforeend', '<section id="playmat" class="pm hidden"></section>');
      UI.playmat.isOpen = false;
      UI.playmat.bind(document.getElementById('playmat'));
      document.getElementById('side').addEventListener('click', App.onSideClick);
      document.getElementById('side').addEventListener('change', App.onSideChange);
      document.getElementById('topbar').addEventListener('click', App.onTopClick);
      document.getElementById('hud').addEventListener('click', App.onHudClick);
      document.getElementById('inspector').addEventListener('click', App.onInspectorClick);
    },

    render() {
      if (App.screen !== 'game' || !App.G) return;
      const G = App.G;
      App.renderTop(G);
      UI.panels.render(G);
      UI.board.render(G);
      App.renderHud(G);
      App.renderInspector(G);
      UI.dock.render(G);
      UI.minimap.render(G);
      UI.playmat.render(G);
      const dock = document.getElementById('dock');
      const hud = document.getElementById('hud');
      const main = App.root().querySelector('.main');
      const dockH = dock.classList.contains('hidden') ? 0 : dock.offsetHeight;
      main.style.setProperty('--dock-off', dockH + 'px');
      main.style.setProperty('--dock-h', (G.move ? dockH + hud.offsetHeight + 16 : dockH) + 'px');
      if (G.pending && !UI.hasModal()) App.openPending();
    },

    cycleSvg(G) {
      const spaces = HF_RULES.cycle.spaces;
      const col = { blue: '#3b82f6', yellow: '#eab308', red: '#ef4444' };
      let s = '<svg class="cycle" viewBox="-50 -50 100 100" width="46" height="46">';
      spaces.forEach((sp, i) => {
        const a0 = (i / 12) * 2 * Math.PI - Math.PI / 2, a1 = ((i + 1) / 12) * 2 * Math.PI - Math.PI / 2;
        const r1 = 46, r0 = 28;
        const p = (r, a) => (r * Math.cos(a)).toFixed(2) + ',' + (r * Math.sin(a)).toFixed(2);
        s += '<path d="M' + p(r0, a0) + ' L' + p(r1, a0) + ' A' + r1 + ',' + r1 + ' 0 0 1 ' + p(r1, a1) + ' L' + p(r0, a1) + ' A' + r0 + ',' + r0 + ' 0 0 0 ' + p(r0, a0) + 'Z" fill="' + col[sp.season] + '" opacity="' + (i === G.cyclePos ? 1 : 0.38) + '" stroke="#0b0f1a" stroke-width="2"/>';
        if (sp.event) { const am = (a0 + a1) / 2; s += '<circle cx="' + (37 * Math.cos(am)).toFixed(1) + '" cy="' + (37 * Math.sin(am)).toFixed(1) + '" r="3.5" fill="#fff"/>'; }
      });
      const fp = G.players[G.firstPlayer];
      const am = ((G.cyclePos + 0.5) / 12) * 2 * Math.PI - Math.PI / 2;
      s += '<rect x="' + (37 * Math.cos(am) - 6).toFixed(1) + '" y="' + (37 * Math.sin(am) - 6).toFixed(1) + '" width="12" height="12" fill="' + S.color(fp) + '" stroke="#000" stroke-width="2"/>';
      s += '<circle r="20" fill="#ffd34d"/><text y="7" text-anchor="middle" font-size="20" font-weight="700" fill="#5a3b00">' + G.seniority + '</text></svg>';
      return s;
    },

    renderTop(G) {
      const season = S.season(G);
      const total = G.config.cycles * 12;
      const order = S.order(G);
      document.getElementById('topbar').innerHTML =
        '<div class="tb-logo" data-tip="High Frontier 4 All - hot-seat prototype">HF<span>4A</span></div>' +
        '<div class="tb-year" data-tip="' + esc('Sunspot Cycle: white dots are event thresholds; the cube shows the 1st Player. Centre: Seniority Disks left (' + G.seniority + ').' +
          (UI.gfx.on() ? '<img class="tip-cycle" src="assets/gfx/sunspot-cycle.jpg" alt="">' : '')) + '">' + App.cycleSvg(G) +
        '<div><div class="tb-y">Year <b>' + G.year + '</b><span class="muted"> / ' + total + '</span></div><div class="season s-' + season + '">' + HF.cap(season) + ' season' + (G.anarchy ? ' · ANARCHY' : '') + '</div></div></div>' +
        '<div class="tb-order">' + order.map(pi => {
          const p = G.players[pi];
          return '<button class="tb-p' + (pi === G.current ? ' on' : '') + '" style="--pc:' + S.color(p) + '" data-act="mat-player" data-p="' + pi + '" data-tip="' + esc(p.name + ' (' + p.faction + ') - ' + p.aquas + ' Aquas, ' + HF.events.score(G, pi).total + ' VP') + '">' +
            UI.dot(p) + '<span>' + esc(p.name) + '</span><i>' + HF.events.score(G, pi).total + '</i></button>';
        }).join('') + '</div>' +
        '<div class="tb-actions"><button class="btn sm" data-act="negotiate" data-tip="<b>Deal</b> (N): trade Aquas, Hand cards, stack cargo, stacks and Claims between players - at any time, even on an opponent’s Turn">🤝<span class="tb-lbl"> Deal</span></button>' +
        '<button class="btn sm" data-act="undo" ' + (App.canUndo ? '' : 'disabled') + ' data-tip="<b>Undo</b> (Ctrl+Z). Dice rolls are final and clear the undo history.">↶<span class="tb-lbl"> Undo</span></button>' +
        '<button class="btn sm" data-act="save" data-tip="<b>Save</b>: download the game as a JSON file (the server keeps every game anyway)">💾<span class="tb-lbl"> Save</span></button>' +
        '<button class="btn sm" data-act="menu" data-tip="<b>Menu</b>: settings, new game, load, rules">☰<span class="tb-lbl"> Menu</span></button></div>';
    },

    renderHud(G) {
      const hud = document.getElementById('hud');
      if (!G.move) { hud.innerHTML = ''; hud.className = ''; return; }
      const m = G.move;
      const p = G.players[G.current];
      const end = HF.movement.canEnd(G);
      const alive = HF.movement.thrusterAlive(G);
      const pips = (n, used, cls) => {
        let s = '';
        for (let i = 0; i < Math.min(n, 16); i++) s += '<i class="pip ' + cls + (i < used ? ' used' : '') + '"></i>';
        return s + (n > 16 ? '<span class="muted small">+' + (n - 16) + '</span>' : '');
      };
      const pct = v => (m.fuelAvail ? Math.min(100, Math.ceil(v - 1e-9) / m.fuelAvail * 100) : 0).toFixed(1) + '%';
      hud.className = 'hud';
      hud.style.setProperty('--pc', S.color(p));
      hud.innerHTML =
        '<div class="hud-top"><div class="hud-title">' + UI.dot(p) + ' Moving with <b>' + esc(m.name) + '</b>' + (m.afterburn ? ' <span class="tag">afterburn</span>' : '') + (alive ? '' : ' <span class="tag bad">coasting</span>') +
        ' · at <b>' + esc(MAP.name(m.node)) + '</b> <span class="muted small">step ' + (m.path.length - 1) + '</span></div>' +
        '<div class="hud-btns">' +
        '<button class="btn sm" data-h="undo" ' + (App.canUndo ? '' : 'disabled') + ' data-tip="Step back (Backspace)">↶ Step back</button>' +
        (m.path.length === 1 && !m.afterburn ? '<button class="btn sm" data-h="cancel" data-tip="Leave movement without moving (Esc)">Cancel</button>' : '') +
        '<button class="btn sm primary" data-h="end" ' + (end.ok ? 'data-tip="End the move here (Enter)"' : 'disabled data-tip="' + esc(end.reason) + '"') + '>End movement ✓</button></div></div>' +
        '<div class="hud-meters">' +
        '<div class="hm" data-tip="Burns used / burn limit (H5c) - the burn limit is the net thrust"><span>Burns</span><div class="pips">' + pips(m.burnLimit, m.burnsUsed, 'burn') + '</div><b>' + m.burnsUsed + '/' + m.burnLimit + '</b></div>' +
        (m.bonus || m.bonusPivots ? '<div class="hm" data-tip="Bonus Burns from flybys (free, but not for lander burns) and Bonus Pivots"><span>Bonus</span><div class="pips">' + pips(m.bonus, 0, 'bonus') + pips(m.bonusPivots, 0, 'pivot') + '</div><b>' + m.bonus + (m.bonusPivots ? '+' + m.bonusPivots + '✦' : '') + '</b></div>' : '') +
        '<div class="hm" data-tip="Fuel steps expended this move (rounded up when the move ends) / fuel available. The pale part previews the hovered Space."><span>Fuel</span><div class="fbar"><i class="pv" style="width:' + pct(m.spent) + '"></i><i class="sp" style="width:' + pct(m.spent) + '"></i></div><b>' + HF.fmtNum(m.spent) + '/' + m.fuelAvail + '</b></div>' +
        '<div class="hm sm" data-tip="Net thrust (H3), fuel per burn (H5) and weight class"><span>Thrust</span><b>' + m.net + '</b><span class="muted small">' + HF.fmtNum(m.fuelCons) + '/burn · ' + m.weightClass + '</span></div>' +
        '</div>' +
        '<div class="hud-preview">' + App.previewHtml(G, UI.board.hover) + '</div>';
    },

    /** "After this step" line for the Space under the mouse. */
    previewHtml(G, id) {
      const m = G.move;
      const o = id && App.moveOptionFor(id);
      if (!o) return '<span class="muted">Click a highlighted Space to move there · <kbd>Enter</kbd> ends the move · <kbd>Backspace</kbd> steps back</span>';
      if (!o.legal) return '<span class="bad">✕ ' + esc(MAP.name(id)) + ': ' + esc(o.reason) + '</span>';
      const n = o.next;
      return '<b>→ ' + esc(MAP.name(id)) + '</b>: ' + (o.burns ? HF.plural(o.burns, 'burn') : 'no burn') + (o.bonusUsed ? ' + ' + o.bonusUsed + ' bonus' : '') +
        (o.pivotUsed ? ' (bonus pivot)' : '') + ' · then burns <b>' + n.burnsUsed + '/' + m.burnLimit + '</b>, fuel <b>' + HF.fmtNum(n.spent) + '/' + m.fuelAvail + '</b>' +
        (o.cantStop ? ' · <span class="warn">cannot stop there</span>' : '') + (o.flags.length ? '<div class="small muted">' + esc(o.flags.join(' · ')) + '</div>' : '');
    },

    /** Board hover during movement: refresh the preview line and fuel bar. */
    hudPreview(id) {
      const G = App.G;
      if (!G || !G.move) return;
      const el = document.querySelector('#hud .hud-preview');
      if (el) el.innerHTML = App.previewHtml(G, id);
      const pv = document.querySelector('#hud .fbar .pv');
      const o = id && App.moveOptionFor(id);
      if (pv) {
        const v = o && o.legal ? o.next.spent : G.move.spent;
        pv.style.width = (G.move.fuelAvail ? Math.min(100, Math.ceil(v - 1e-9) / G.move.fuelAvail * 100) : 0).toFixed(1) + '%';
      }
    },

    renderInspector(G) {
      const el = document.getElementById('inspector');
      const id = App.ui.selectedNode;
      if (!id || G.move) { el.innerHTML = ''; el.className = ''; return; }
      const n = MAP.node(id);
      el.className = 'inspector';
      const site = G.sites[id];
      let h = '<button class="icon-btn" data-i="close">✕</button><h3>' + esc(MAP.name(id)) + '</h3>';
      if (n.t === 'site') {
        h += '<div class="ins-site"><div class="ins-hex"><b>' + n.size + '</b><span>' + n.spec + '</span></div><div>' +
          '<div>Size <b>' + n.size + '</b> · ' + (HF_RULES.spectralTypes[n.spec] || n.spec) + ' · Hydration <b>' + n.water + '</b></div>' +
          '<div class="muted small">' + [n.atmo && 'atmospheric', n.astro && 'astrobiology', n.sub && 'submarine', n.push && 'push', n.syn && ('synodic ' + n.syn)].filter(Boolean).join(' · ') + '</div>' +
          '<div class="small">Landing/liftoff: net thrust &gt; ' + n.size + ' · prospect roll ≤ ' + n.size + (n.size >= 6 ? ' (automatic)' : '') + '</div>' +
          '<div class="small">Delivery cost: ' + HF.ops.deliveryCost(id) + ' FT · stock price ' + S.stockPrice(G, n.spec) + '</div></div></div>';
        if (site && site.busted) h += '<div class="ins-own bad">Busted</div>';
        else if (site && site.claim != null) h += '<div class="ins-own">' + UI.pname(G.players[site.claim]) + ': ' + (site.colony ? 'Colony' : site.factory ? 'Factory' : 'Claim') + '</div>';
        else h += '<div class="ins-own muted">Unclaimed</div>';
        // G6a: your own tokens can be discarded to your Reserve (a colonized Factory is permanent, G6b).
        if (site && site.claim === G.current && !G.move && !G.pending && !G.gameOver && !site.colony) {
          h += '<div class="ins-acts">' + (site.factory ? '<button class="btn xs" data-i="discardFactory" data-tip="Free action (G6a): the cube returns to your Reserve; the Claim stays and can be industrialized again.">Discard Factory cube</button>'
            : '<button class="btn xs" data-i="discardClaim" data-tip="Free action (G6a): the disk returns to your Reserve; the Site can be prospected again.">Discard Claim disk</button>') + '</div>';
        }
      } else {
        h += '<div class="small muted">' + HF.zone(n.z).name + ' zone</div>';
      }
      const st = S.stacksAt(G, id);
      if (st.length) {
        h += '<div class="ins-stacks">' + st.map(x => {
          const p = G.players[x.p];
          return '<div class="ins-stack">' + UI.pname(p) + ' ' + S.STACK_NAME[x.key] + '<div class="mcards">' + x.stack.cards.map((c, i) =>
            UI.cardMini(c, { attrs: 'data-i="card" data-p="' + x.p + '" data-key="' + x.key + '" data-ci="' + i + '"' })).join('') +
            (x.stack.fts ? '<div class="small">' + x.stack.fts + ' FT</div>' : '') + '</div></div>';
        }).join('') + '</div>';
      }
      el.innerHTML = h;
    },

    /* ---------------- input ---------------- */
    moveOptionFor(id) {
      const G = App.G;
      if (!G || !G.move) return null;
      return HF.movement.options(G).find(o => o.to === id) || null;
    },

    onNodeClick(id) {
      const G = App.G;
      if (!G || App.screen !== 'game') return;
      if (G.move) {
        if (id && UI.dialogs.stepTo(id)) return;
        return;
      }
      App.ui.selectedNode = id;
      App.renderInspector(G);
      UI.board.drawHover();
    },

    onInspectorClick(e) {
      const t = e.target.closest('[data-i]');
      if (!t) return;
      if (t.dataset.i === 'close') { App.ui.selectedNode = null; App.renderInspector(App.G); UI.board.drawHover(); }
      if (t.dataset.i === 'card') UI.dialogs.cardActions(+t.dataset.p, t.dataset.key, +t.dataset.ci);
      const id = App.ui.selectedNode;
      if (t.dataset.i === 'discardFactory') UI.confirm('Discard Factory cube (G6a)', '<p>The Factory cube on ' + esc(MAP.name(id)) + ' returns to your Reserve. The Claim stays; the stock price of its type rises.</p>',
        'Discard', () => App.act(g => HF.actions.discardFactory(g, id)), 'danger');
      if (t.dataset.i === 'discardClaim') UI.confirm('Discard Claim disk (G6a)', '<p>The Claim disk on ' + esc(MAP.name(id)) + ' returns to your Reserve; any player may prospect the Site again.</p>',
        'Discard', () => App.act(g => HF.actions.discardClaim(g, id)), 'danger');
    },

    onHudClick(e) {
      const b = e.target.closest('[data-h]');
      if (!b) return;
      const h = b.dataset.h;
      if (h === 'undo') App.undo();
      if (h === 'cancel') App.act(g => HF.movement.cancel(g));
      if (h === 'end') App.act(g => HF.movement.end(g));
    },

    onTopClick(e) {
      const b = e.target.closest('[data-act]');
      if (!b) return;
      const a = b.dataset.act;
      if (a === 'undo') App.undo();
      if (a === 'save') App.saveFile();
      if (a === 'menu') App.menu();
      if (a === 'negotiate') UI.negotiation.open();
      if (a === 'playmat') UI.playmat.toggle();
      if (a === 'mat-player') UI.playmat.open(+b.dataset.p);
    },

    /* ---------------- keyboard ---------------- */
    SHORTCUTS: [
      ['M', 'Move the Rocket (thrust triangle picker)'], ['E', 'End turn'], ['Enter', 'End movement (while moving)'],
      ['Backspace', 'Step back (while moving)'], ['Esc', 'Cancel a move before the first step'], ['Ctrl+Z / Z', 'Undo'],
      ['Alt (hold)', 'Show the other side of every card'],
      ['1 - 7', 'Side-panel tabs: Turn, Mat (= the playmat), Hand, Patents, Players, Log, Help'], ['D', 'Show / hide the ship dock (also while moving)'],
      ['P', 'Playmat (Fuel Strip, stacks, Hand; read-only while moving) - Esc closes it'], ['← ↑ → ↓', 'Pan the map (with Shift: farther)'], ['F', 'Fit the whole map'], ['C', 'Center on the current player'], ['+ / -', 'Zoom'], ['N', 'Negotiation (Deal)'], ['?', 'Help tab']
    ],

    onKey(e) {
      if (e.key === 'Escape') UI.tip(null);
      if (App.screen !== 'game' || !App.G) return;
      const t = e.target;
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable)) return;
      const k = e.key;
      if ((e.ctrlKey || e.metaKey) && k.toLowerCase() === 'z') { if (!UI.hasModal()) { e.preventDefault(); App.undo(); } return; }
      if (e.ctrlKey || e.metaKey || e.altKey || UI.hasModal()) return;
      if (k === 'Escape' && UI.playmat.isOpen) { UI.playmat.close(); App.render(); return; }
      const G = App.G;
      const tabs = ['turn', 'mat', 'hand', 'patents', 'players', 'log', 'help'];
      if (G.move) {
        if (k === 'Enter') {
          e.preventDefault();
          const c = HF.movement.canEnd(G);
          if (c.ok) App.act(g => HF.movement.end(g)); else UI.toast(c.reason, 'bad');
          return;
        }
        if (k === 'Backspace') { e.preventDefault(); App.undo(); return; }
        if (k === 'Escape' && G.move.path.length === 1 && !G.move.afterburn) { App.act(g => HF.movement.cancel(g)); return; }
      }
      const pan = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[k];
      if (pan) {
        if (UI.playmat.isOpen) return;
        e.preventDefault();
        const step = e.shiftKey ? 420 : 140;
        UI.board.panBy(pan[0] * step, pan[1] * step);
        return;
      }
      const lk = k.toLowerCase();
      if (lk === 'z') App.undo();
      else if (lk === 'p') UI.playmat.toggle();
      else if (lk === 'm' && !G.move && !G.pending) { UI.playmat.close(); UI.dialogs.movePrep(); }
      else if (lk === 'e' && !G.move && !G.pending) UI.assist.guardEndTurn(G, () => App.act(g => HF.events.endTurn(g)));
      else if (lk === 'd') UI.dock.toggle();
      else if (lk === 'f') UI.board.fit();
      else if (lk === 'c') UI.board.centerOnPlayer();
      else if (lk === 'n') UI.negotiation.open();
      else if (k === '+' || k === '=') UI.board.zoomBy(1.4);
      else if (k === '-') UI.board.zoomBy(1 / 1.4);
      else if (k === '?') { App.ui.tab = 'help'; App.render(); }
      else if (k >= '1' && k <= '7') {
        const tab = tabs[+k - 1];
        if (tab === 'mat') UI.playmat.toggle();
        else { App.ui.tab = tab; App.render(); }
      }
    },

    help() {
      UI.modal({
        title: '⌨ Keyboard shortcuts',
        body: '<table class="keys">' + App.SHORTCUTS.map(([k, d]) => '<tr><td>' + k.split(' / ').map(x => '<kbd>' + esc(x) + '</kbd>').join(' / ') + '</td><td>' + esc(d) + '</td></tr>').join('') + '</table>' +
          '<p class="hint">Drag card thumbnails and FT chips between stacks in the ship dock or the playmat; drop FTs on the Fuel Strip to load them as fuel. Click or drag on the minimap to move the view.</p>',
        buttons: [{ label: 'Close', kind: 'primary' }]
      });
    },

    menu() {
      UI.modal({
        title: 'Menu',
        body: '<div class="menu-list">' +
          '<button class="btn" data-m="save">💾 Save game to file</button>' +
          '<button class="btn" data-m="load">📂 Load game file…</button>' +
          '<label class="chk"><input type="checkbox" data-m="handover" ' + (App.settings.handover ? 'checked' : '') + '> "Pass the device" screen between turns</label>' +
          '<label class="chk"><input type="checkbox" data-m="hints" ' + (App.settings.hints !== false ? 'checked' : '') + '> "Next steps" hints in the Turn tab</label>' +
          '<label class="chk"><input type="checkbox" data-m="confirmEnd" ' + (App.settings.confirmEnd !== false ? 'checked' : '') + '> Ask before ending a turn with the move or Operation unused</label>' +
          '<label class="chk"><input type="checkbox" data-m="cardArt" ' + (App.settings.cardArt !== false ? 'checked' : '') + '> Original game graphics: cards, playmat, chits, pieces (from assets/; drawn otherwise)</label>' +
          '<button class="btn" data-m="rules">📖 Rules summary</button>' +
          '<button class="btn" data-m="keys">⌨ Keyboard shortcuts</button>' +
          '<button class="btn" data-m="library">📚 Card library</button>' +
          '<button class="btn" data-m="demo">🎬 Demo scenarios…</button>' +
          '<button class="btn" data-m="about">ℹ About this prototype</button>' +
          '<button class="btn danger" data-m="title">⏏ Quit to title (the server keeps the game)</button></div>',
        onMount(root, close) {
          root.addEventListener('click', e => {
            const b = e.target.closest('button[data-m]');
            if (!b) return;
            const m = b.dataset.m;
            close();
            if (m === 'save') App.saveFile();
            if (m === 'load') App.loadFile();
            if (m === 'rules') UI.modal({ title: 'Rules summary', wide: true, body: UI.panels.rules() });
            if (m === 'library') App.library();
            if (m === 'demo') App.demoPicker();
            if (m === 'keys') App.help();
            if (m === 'about') UI.modal({ title: 'About', body: '<p>High Frontier 4 All hot-seat table, prototype ' + HF.version + '. Core game rules (no modules) run on the Java game server, ' +
              'built on the HF4A mission planner’s map and Fuel Strip models; all players share one screen.</p><p>Game ' + esc(App.gameId) + ', version ' + App.version + '.</p><p>The map graph is generated from the HF4A mission planner’s board data. The 77 patent cards and 6 Crew cards were transcribed ' +
              'from the card faces in the official Vassal module (by Stefano Tine\', released with permission from ION/SMG); the original card art is extracted locally with ' +
              '<code>tools/extract_cards.py</code> and is © ION Game Design / Sierra Madre Games.</p><p>Seed: ' + App.G.seed + '</p>' });
            if (m === 'title') App.showTitle();
          });
          root.addEventListener('change', e => {
            if (e.target.dataset.m === 'handover') { App.settings.handover = e.target.checked; App.saveSettings(); }
            if (e.target.dataset.m === 'hints' || e.target.dataset.m === 'confirmEnd') { App.settings[e.target.dataset.m] = e.target.checked; App.saveSettings(); App.render(); }
            if (e.target.dataset.m === 'cardArt') { App.settings.cardArt = e.target.checked; App.saveSettings(); UI.gfx.apply(); App.render(); }
          });
        }
      });
    },

    /** Every patent and Crew card, filterable by deck and side. */
    library() {
      const st = { deck: 'thruster', side: 'w' };
      const draw = (root) => {
        const decks = HF.cards.DECKS.concat(['crew']);
        let cards;
        if (st.deck === 'crew') {
          cards = HF_CARDS.crews.flatMap(c => c.sides.map((s, f) => UI.cardFull(HF.cards.stats({ crew: c.id, face: f }))));
        } else {
          cards = HF_CARDS.decks[st.deck].map(c => UI.cardFull(HF.cards.sideStats(c.id, st.side),
            { footer: '<div class="card-actions"><span class="muted small">' + c.id + '</span></div>' }));
        }
        root.querySelector('.modal-body').innerHTML = '<div class="chips">' + decks.map(d => '<button class="chip' + (st.deck === d ? ' on' : '') + '" data-d="' + d + '">' +
          HF.cards.DECK_INFO[d].name + '</button>').join('') + '<span style="flex:1"></span>' +
          (st.deck === 'crew' ? '' : ['w', 'b'].map(s => '<button class="chip' + (st.side === s ? ' on' : '') + '" data-s="' + s + '">' + (s === 'w' ? 'White-Side' : 'Black-Side') + '</button>').join('')) +
          '</div><div class="card-grid">' + cards.join('') + '</div>' +
          '<p class="hint">Card art © ION Game Design / Sierra Madre Games (from the official Vassal module). Without assets/cards the drawn cards are shown.</p>';
      };
      UI.modal({ title: '📚 Card library', wide: true, body: '', onMount(root) {
        draw(root);
        root.addEventListener('click', e => {
          const b = e.target.closest('button');
          if (!b || (!b.dataset.d && !b.dataset.s)) return;
          if (b.dataset.d) st.deck = b.dataset.d;
          if (b.dataset.s) st.side = b.dataset.s;
          draw(root);
        });
      } });
    },

    onSideChange(e) {
      const t = e.target;
      if (t.dataset.act === 'hand-black') { App.ui.handBlack = t.checked; App.render(); }
    },

    onSideClick(e) {
      const b = e.target.closest('[data-act]');
      if (!b) return;
      if (b.classList.contains('off')) {
        const tip = b.getAttribute('data-tip');
        if (tip) UI.toast(tip.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim(), 'info');
        return;
      }
      const a = b.dataset.act;
      const D = UI.dialogs;
      const A = HF.actions;
      const ui = App.ui;
      switch (a) {
        case 'tab':
          if (b.dataset.tab === 'mat') UI.playmat.open(); // the Mat button is the playmat
          else { ui.tab = b.dataset.tab; App.render(); }
          break;
        case 'mat-player': UI.playmat.open(+b.dataset.p); break;
        case 'playmat': UI.playmat.open(b.dataset.p != null ? +b.dataset.p : null); break;
        case 'goto': UI.board.centerOn(b.dataset.node); ui.selectedNode = b.dataset.node; App.renderInspector(App.G); UI.board.drawHover(); break;
        case 'move-prep': D.movePrep(); break;
        case 'op': D.op(b.dataset.op); break;
        case 'auction': App.act(g => HF.ops.startAuction(g, b.dataset.deck)); break;
        case 'end-turn': UI.assist.guardEndTurn(App.G, () => App.act(g => HF.events.endTurn(g))); break;
        case 'hints-off': App.settings.hints = false; App.saveSettings(); App.render(); UI.toast('Hints hidden - Menu turns them back on.', 'info'); break;
        case 'card': D.cardActions(+b.dataset.p, b.dataset.key, +b.dataset.i); break;
        case 'card-detail': UI.showCard(b.dataset.id); break;
        case 'discard': App.act(g => A.discardHand(g, +b.dataset.i)); break;
        case 'load-fuel': D.loadFuel(); break;
        case 'engines': D.engineBuilder(false); break;
        case 'jettison-fuel': D.jettisonFuel(); break;
        case 'dirt-refuel': D.dirtRefuel(); break;
        case 'mooncable': App.act(g => A.mooncable(g)); break;
        case 'jellybots': D.op_industrialize(true); break;
        case 'load-glory': App.act(g => A.loadGlory(g)); break;
        case 'build-colony': UI.confirm('Build colony (G3)', 'Your Crew settles at your Factory and returns to LEO as a card (it becomes the colonists). Continue?', 'Build colony', () => App.act(g => A.buildColony(g))); break;
        case 'claim-jump': UI.confirm('Claim jump (Felony)', 'Replace the opponent’s Claim with yours?', 'Jump the claim', () => App.act(g => A.claimJump(g)), 'danger'); break;
        case 'murder': D.murder(); break;
        case 'negotiate': {
          const pi = b.dataset.p != null ? +b.dataset.p : null;
          if (pi == null || pi === App.G.current) UI.negotiation.open(App.G.current);
          else UI.negotiation.open(App.G.current, pi);
          break;
        }
        case 'glitch-repair': App.act(g => A.glitchRepair(g, b.dataset.key)); break;
        case 'heroism': D.heroism(); break;
        case 'log-filter': ui.logFilter = b.dataset.f; App.render(); break;
        case 'rules-summary': UI.modal({ title: 'Rules summary', wide: true, body: UI.panels.rules() }); break;
        case 'final-scores': D.gameOver(); break;
        case 'new-game': App.showSetup(); break;
      }
    }
  };

  HF.app = App;
  document.addEventListener('DOMContentLoaded', () => App.init());
})(window.HF = window.HF || {});
