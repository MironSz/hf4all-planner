/*
 * Live research auction (I2): the device goes around the table. Each eligible
 * player in turn raises, matches, holds or passes; the auction ends when every
 * player still in has acted since the last raise or match. Ties follow I2e
 * (initiator, Marketeer, otherwise the initiator chooses). The old "enter the
 * final bids" form stays available for groups that bid out loud.
 */
(function (HF) {
  'use strict';

  const UI = HF.ui;
  const S = HF.state;
  const O = HF.ops;
  const esc = HF.escape;

  const Auc = {
    /** Decks whose top card the winner also takes (I2g): one per support the card needs. */
    bonusDecks(cardId) {
      const req = HF.cards.def(cardId).w.req || {};
      const out = [];
      if (req.gen && req.gen.length) out.push('generator');
      if (req.reactor && req.reactor.length) out.push('reactor');
      if (req.therms) out.push('radiator');
      return out;
    },

    /**
     * Everything the auction is for: the patent and, below it, the bonus supports
     * the winner also takes (I2g) - the top cards of those decks, which are open (I2f).
     */
    lotHtml(g, pd, buttons) {
      const bonus = Auc.bonusDecks(pd.card);
      let h = '<div class="auc-lot">' + UI.cardFull(HF.cards.sideStats(pd.card, 'w')) + (buttons || '');
      if (bonus.length) {
        h += '<div class="auc-bonus-h">+ Bonus supports (I2g): the winner also takes</div><div class="auc-bonus-cards">' + bonus.map(d => {
          const top = (g.decks[d] || [])[0];
          const name = HF.cards.DECK_INFO[d].name;
          if (!top) return '<div class="auc-bonus-none muted small">' + esc(name) + ' deck empty: no bonus</div>';
          const s = HF.cards.sideStats(top, 'w');
          return '<div class="auc-bonus-item" data-tip="' + esc(UI.artTip(s) + '<b>' + esc(s.name) + '</b> - top of the ' + name + ' deck') + '">' +
            UI.cardFull(s) + '<div class="muted small">top ' + esc(name.toLowerCase().replace(/s$/, '')) + '</div></div>';
        }).join('') + '</div>';
      }
      return h + '</div>';
    },

    open() {
      const App = HF.app;
      const g = App.G;
      const pd = g.pending;
      const order = S.order(g);
      const start = order.indexOf(pd.initiator);
      const ring = order.slice(start).concat(order.slice(0, start));
      const seq = ring.filter(pi => O.canBid(g, pi));
      const def = HF.cards.def(pd.card);
      let st = { bids: {}, passed: {}, acted: {}, turn: 0, high: -1, log: [], tie: null };
      const undo = [];
      const P = pi => g.players[pi];
      const active = () => seq.filter(pi => !st.passed[pi]);
      const done = () => st.high >= 0 && active().every(pi => st.acted[pi]);
      const leaders = () => Object.keys(st.bids).map(Number).filter(pi => st.bids[pi] === st.high);
      const marketeer = pi => S.privilegeActive(g, P(pi), 'marketeer');

      /** Winner under I2e, or null when the initiator must choose between the tied players. */
      const winner = () => {
        const tied = leaders();
        if (tied.length === 1) return tied[0];
        const mk = tied.filter(marketeer);
        if (tied.includes(pd.initiator) && !mk.some(k => k !== pd.initiator)) return pd.initiator;
        if (mk.length === 1) return mk[0];
        return st.tie != null && tied.includes(st.tie) ? st.tie : null;
      };

      const advance = () => {
        for (let k = 1; k <= seq.length; k++) {
          const i = (st.turn + k) % seq.length;
          if (!st.passed[seq[i]]) { st.turn = i; return; }
        }
      };

      const doAct = (pi, kind, n) => {
        undo.push(JSON.stringify(st));
        const name = P(pi).name;
        if (kind === 'raise') {
          st.bids[pi] = n; st.high = n; st.acted = { [pi]: true };
          st.log.push({ pi, t: (Object.keys(st.bids).length === 1 ? 'opens at ' : 'raises to ') + n });
        } else if (kind === 'match') {
          st.bids[pi] = st.high; st.acted = { [pi]: true };
          st.log.push({ pi, t: 'matches ' + st.high });
        } else if (kind === 'hold') {
          st.acted[pi] = true;
          st.log.push({ pi, t: 'holds at ' + st.bids[pi] });
        } else {
          st.passed[pi] = true; st.acted[pi] = true;
          st.log.push({ pi, t: st.bids[pi] === st.high ? 'stands at ' + st.bids[pi] + ' (done bidding)' : 'passes' });
        }
        if (!done()) advance();
        draw();
      };

      let root;
      const draw = () => {
        const body = root.querySelector('.modal-body');
        const cur = seq[st.turn];
        const fin = done();
        const w = fin ? winner() : null;
        let h = '<div class="auction live-auc"><div class="la-card">' + Auc.lotHtml(g, pd, '<button class="btn xs" data-a="black">See Black-Side</button>') +
          '</div><div class="la-main">';
        h += '<div class="la-high">' + (st.high < 0 ? '<span class="muted">No bids yet - ' + esc(P(pd.initiator).name) + ' (initiator) opens, 0 or more.</span>' :
          'High bid <b class="la-amt">' + st.high + '</b> <i class="aqua"></i> · ' + leaders().map(pi => UI.pname(P(pi))).join(' · ')) + '</div>';
        h += '<div class="la-rows">' + ring.map(pi => {
          const p = P(pi);
          const can = seq.includes(pi);
          const isCur = can && !fin && pi === cur;
          const bid = st.bids[pi];
          let status = '';
          if (!can) status = '<span class="muted small" data-tip="Academia hand limit (I2a): 4+ Hand cards">cannot bid</span>';
          else if (st.passed[pi]) status = '<span class="muted small">' + (bid === st.high ? 'stands' : 'passed') + '</span>';
          else if (fin) status = '';
          else if (isCur) status = '<span class="la-turn">to act</span>';
          else if (st.acted[pi]) status = '<span class="muted small">✓</span>';
          let row = '<div class="la-row' + (isCur ? ' cur' : '') + (can ? '' : ' off') + '" style="--pc:' + S.color(p) + '">' +
            '<div class="la-who">' + UI.pname(p) + (pi === pd.initiator ? ' <span class="tag">initiator</span>' : '') + (marketeer(pi) ? ' <span class="tag" data-tip="Marketeer: wins every tie">marketeer</span>' : '') + '</div>' +
            '<div class="la-aq"><i class="aqua"></i> ' + p.aquas + '</div>' +
            '<div class="la-bid">' + (bid != null ? '<b' + (bid === st.high ? ' class="lead"' : '') + '>' + bid + '</b>' : '<span class="muted">-</span>') + '</div>' +
            '<div class="la-st">' + status + '</div></div>';
          if (isCur) {
            const opening = st.high < 0;
            const min = opening ? 0 : st.high + 1;
            const canRaise = min <= p.aquas;
            row += '<div class="la-ctl" style="--pc:' + S.color(p) + '">' +
              (canRaise ? '<div class="la-step"><button class="btn xs" data-a="dec">−</button><input type="number" class="la-n" min="' + min + '" max="' + p.aquas + '" value="' + min + '">' +
                '<button class="btn xs" data-a="inc">+</button></div><button class="btn sm primary" data-a="raise" data-p="' + pi + '">' + (opening ? 'Open' : 'Raise') + '</button>' : '<span class="muted small">cannot ' + (opening ? 'open' : 'raise') + ' (' + p.aquas + ' Aquas)</span>') +
              (!opening && bid !== st.high && st.high <= p.aquas ? '<button class="btn sm" data-a="match" data-p="' + pi + '">Match ' + st.high + '</button>' : '') +
              (!opening && bid === st.high ? '<button class="btn sm" data-a="hold" data-p="' + pi + '">Hold</button>' : '') +
              (opening ? '' : '<button class="btn sm ghost" data-a="pass" data-p="' + pi + '">Pass</button>') + '</div>';
          }
          return row;
        }).join('') + '</div>';
        if (fin) {
          const tied = leaders();
          if (w == null) {
            h += '<div class="la-end"><b>Tie at ' + st.high + '.</b> ' + esc(P(pd.initiator).name) + ' (initiator) chooses the winner (I2e):<div class="btn-row">' +
              tied.map(pi => '<button class="btn sm" data-a="tie" data-p="' + pi + '">' + UI.dot(P(pi)) + ' ' + esc(P(pi).name) + '</button>').join('') + '</div></div>';
          } else {
            h += '<div class="la-end"><b>' + esc(P(w).name) + '</b> wins for <b>' + st.high + '</b> <i class="aqua"></i>' +
              (tied.length > 1 ? ' <span class="muted small">(tie: ' + (w === pd.initiator ? 'initiator' : marketeer(w) ? 'Marketeer' : 'initiator’s choice') + ')</span>' : '') +
              ' - paid to ' + (w === pd.initiator ? 'the Pool' : esc(P(pd.initiator).name)) + '.</div>';
          }
        }
        h += '<div class="la-log">' + st.log.slice(-8).map(e => '<div>' + UI.dot(P(e.pi)) + ' ' + esc(P(e.pi).name) + ' ' + e.t + '</div>').join('') + '</div>';
        h += '</div></div>';
        body.innerHTML = h;
        const foot = root.querySelector('.modal-foot');
        foot.querySelector('[data-a="undo"]').disabled = !undo.length;
        const award = foot.querySelector('[data-a="award"]');
        award.disabled = !(fin && w != null);
        award.innerHTML = fin && w != null ? 'Award to ' + esc(P(w).name) + ' ▸' : 'Award card';
        const n = body.querySelector('.la-n');
        if (n) n.focus();
      };

      const awardNow = (close) => {
        const w = winner();
        const bids = {};
        for (const k in st.bids) bids[k] = st.bids[k];
        close();
        App.act(g2 => O.resolveAuction(g2, bids, w));
      };

      UI.modal({
        title: '🔨 Research auction · ' + esc(def.w.name), wide: true, dismissable: false, cls: 'live',
        body: '',
        buttons: [
          { label: 'Enter final bids instead…', kind: 'ghost', onClick: c => { c(); UI.dialogs.auctionPending(); } },
          { label: '↶ Undo bid', onClick: () => { if (undo.length) { st = JSON.parse(undo.pop()); draw(); } } },
          { label: 'Award card', kind: 'primary', onClick: c => { if (done() && winner() != null) awardNow(c); } }
        ],
        onMount(r) {
          root = r;
          const foot = r.querySelector('.modal-foot');
          foot.children[1].dataset.a = 'undo';
          foot.children[2].dataset.a = 'award';
          r.addEventListener('click', e => {
            const b = e.target.closest('.modal-body [data-a]');
            if (!b) return;
            const a = b.dataset.a;
            const pi = b.dataset.p != null ? +b.dataset.p : null;
            const inp = r.querySelector('.la-n');
            if (a === 'black') return UI.showCard(pd.card);
            if (a === 'inc' || a === 'dec') {
              const v = Math.max(+inp.min, Math.min(+inp.max, +inp.value + (a === 'inc' ? 1 : -1)));
              inp.value = v;
              return;
            }
            if (a === 'raise') {
              const v = Math.floor(+inp.value);
              if (!(v >= +inp.min && v <= +inp.max)) return UI.toast('Bid between ' + inp.min + ' and ' + inp.max + ' Aquas.', 'bad');
              return doAct(pi, 'raise', v);
            }
            if (a === 'match' || a === 'hold' || a === 'pass') return doAct(pi, a);
            if (a === 'tie') { st.tie = pi; draw(); }
          });
          r.addEventListener('keydown', e => {
            if (e.key === 'Enter' && e.target.classList.contains('la-n')) { e.preventDefault(); r.querySelector('[data-a="raise"]').click(); }
          });
          draw();
        }
      });
    }
  };

  UI.auction = Auc;
})(window.HF = window.HF || {});
