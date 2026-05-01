// Configuration loaded from /api/config; falls back to inline defaults.
//
// Keep this object reference stable so every module's `cfgI`/`cfgF` reads
// see updates after the fetch resolves. We mutate in place rather than
// reassigning the binding.
let CONFIG = {};

export const cfgI = (k, d) => { const v = CONFIG[k]; return v != null ? parseInt(v)   : d; };
export const cfgF = (k, d) => { const v = CONFIG[k]; return v != null ? parseFloat(v) : d; };

export function setConfig(cfg) {
    CONFIG = cfg || {};
}

export function applyConfigToForm() {
    const fuel = document.getElementById('fuel');
    fuel.value = cfgI('ui.fuel.default', 15);
    fuel.min   = cfgI('ui.fuel.min', 0);
    fuel.max   = cfgI('ui.fuel.max', 31);
    const dry = document.getElementById('dry-mass');
    dry.value = cfgI('ui.dry.mass.default', 4);
    dry.min   = cfgI('ui.dry.mass.min', 1);
    dry.max   = cfgI('ui.dry.mass.max', 23);
    const hyd = document.getElementById('hydration-min');
    hyd.min = cfgI('ui.hydration.filter.min', 0);
    hyd.max = cfgI('ui.hydration.filter.max', 4);
    document.getElementById('search-suggestions').style.maxHeight =
            cfgI('ui.search.suggestions.max.height', 220) + 'px';
}
