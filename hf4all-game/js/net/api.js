/*
 * HTTP client of the game server (hf4all-game/server). The server holds the
 * game state and runs every rule; see server/.../ApiHandler.java for the API.
 */
(function (HF) {
  'use strict';

  const Api = {
    base: 'api',

    async req(method, path, body) {
      let r;
      try {
        r = await fetch(Api.base + path, {
          method,
          headers: body !== undefined ? { 'Content-Type': 'application/json' } : {},
          body: body !== undefined ? JSON.stringify(body) : undefined,
          cache: 'no-store'
        });
      } catch (e) {
        const err = new Error('The game server is not reachable (' + e.message + ').');
        err.status = 0;
        throw err;
      }
      let data = null;
      try { data = await r.json(); } catch (e) { /* empty or not JSON */ }
      if (!r.ok) {
        const err = new Error((data && data.error) || ('Server error ' + r.status));
        err.status = r.status;
        throw err;
      }
      return data;
    },

    health() { return Api.req('GET', '/health'); },
    games() { return Api.req('GET', '/games'); },
    create(cfg) { return Api.req('POST', '/games', cfg); },
    /** The demo position, or a demo scenario (the reply then carries `scenario`: {id, group, title, text}). */
    demo(scenario) { return Api.req('POST', '/games/demo', scenario ? { scenario } : {}); },
    scenarios() { return Api.req('GET', '/scenarios'); },
    importState(state) { return Api.req('POST', '/games/import', { state }); },
    get(id) { return Api.req('GET', '/games/' + id); },
    /** batch: [{ cmd, args }] applied as one step. */
    commands(id, version, batch) { return Api.req('POST', '/games/' + id + '/commands', { version, batch }); },
    undo(id, version) { return Api.req('POST', '/games/' + id + '/undo', { version }); },
    query(id, cmd, args) { return Api.req('POST', '/games/' + id + '/query', { cmd, args: args || [] }).then(r => r.result); },
    exportUrl(id) { return Api.base + '/games/' + id + '/export'; },
    remove(id) { return Api.req('DELETE', '/games/' + id); }
  };

  HF.api = Api;
})(window.HF = window.HF || {});
