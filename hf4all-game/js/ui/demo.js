/*
 * The ready-made mid-game demo and the demo scenarios ("Demo scenarios" on the
 * title screen): the server builds the positions (DemoFactory, Scenarios). Also
 * reachable as index.html?demo=1, or ?demo=<scenario id> for a scenario (with its
 * intro), with optional &tab=<side tab>&open=<move|movePrep|
 * auction|boost|card|site|score|negotiate|murder|...> for jumping straight to
 * a screen (`murder` turns on Anarchy and puts Amelia's Rocket on Nereus so a
 * Felony is possible; `score` jumps to the end of the game).
 */
(function (HF) {
  'use strict';

  const wait = (ms) => new Promise(r => setTimeout(r, ms));

  HF.demo = {
    /** Handle ?demo=1&tab=..&open=.. */
    fromUrl() {
      const q = new URLSearchParams(location.search);
      if (!q.get('demo')) return false;
      HF.demo.run(q).catch(e => HF.ui.toast(e.message, 'bad'));
      return true;
    },

    async run(q) {
      const App = HF.app;
      const D = HF.ui.dialogs;
      App.settings.handover = false;
      if (q.get('gfx') === '0') { App.settings.cardArt = false; HF.ui.gfx.apply(); }
      const scenario = q.get('demo') !== '1' ? q.get('demo') : null;
      let res = await HF.api.demo(scenario);
      const open = q.get('open');
      // Positions the demo does not reach by play are made on a copy and loaded as a new game.
      if (open === 'score' || open === 'murder') {
        const G = res.state;
        if (open === 'score') { G.seniority = 1; G.cyclePos = 11; G.current = 2; }
        if (open === 'murder') { G.anarchy = true; G.players[0].stacks.rocket.node = '333'; }
        res = await HF.api.importState(G);
      }
      App.open(res, !!res.scenario);
      if (res.scenario && !open) App.scenarioIntro(res.scenario);
      if (q.get('tab') === 'mat') HF.ui.playmat.open(); // the Mat button opens the playmat
      else if (q.get('tab')) { App.ui.tab = q.get('tab'); App.render(); }
      await wait(300);
      if (open === 'move') {
        const opt = HF.movement.thrustOptions(App.G).find(o => o.ok);
        await App.act(g => HF.movement.start(g, opt.idx, false));
        HF.ui.board.centerOn(App.G.move.node, 0.55);
      } else if (open === 'move2') {
        const opt = HF.movement.thrustOptions(App.G).find(o => o.ok);
        await App.act(g => HF.movement.start(g, opt.idx, false));
        for (let i = 0; i < 2; i++) {
          const o = HF.movement.options(App.G).find(x => x.legal && !x.hazard && !x.cantStop && x.burns > 0);
          if (o) await App.act(g => HF.movement.step(g, o.to));
        }
        const nx = HF.movement.options(App.G).find(x => x.legal);
        HF.ui.board.centerOn(App.G.move.node, 0.5);
        if (nx) { HF.ui.board.hover = nx.to; HF.ui.board.drawHover(); App.hudPreview(nx.to); }
      } else if (open === 'handover') D.handover(App.G);
      else if (open === 'playmat') HF.ui.playmat.open(q.get('p') != null ? +q.get('p') : null);
      else if (open === 'keys') App.help();
      else if (open === 'movePrep') D.movePrep();
      else if (open === 'auction') await App.act(g => HF.ops.startAuction(g, 'reactor'));
      else if (open === 'boost') D.op_boost();
      else if (open === 'card') D.cardActions(0, 'rocket', 1);
      else if (open === 'site') { App.onNodeClick('260'); HF.ui.board.centerOn('260', 0.6); }
      else if (open === 'score') await App.act(g => HF.events.endTurn(g));
      else if (open === 'setup') App.showSetup();
      else if (open === 'library') App.library();
      else if (open === 'negotiate') HF.ui.negotiation.open(0, 1);
      else if (open === 'murder') D.murder();
      else if (open === 'spaces') { App.ui.showSpaces = true; HF.ui.board.drawSpaces(); HF.ui.board.renderLegend(App.G); HF.ui.board.centerOn('260', 0.5); }
    }
  };
})(window.HF = window.HF || {});
