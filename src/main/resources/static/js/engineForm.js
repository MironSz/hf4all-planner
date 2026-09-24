// Engine form — rebuildable engine blocks plus the +/X buttons + the
// fuel/dry-mass inputs. Re-numbering keeps the visible "Engine N"
// titles in sync with their position; the Add button caps at
// ui.engines.max and disables itself accordingly.
//
// Clicking an engine's title renames it. The custom name lives on the block
// as data-engine-name and the title always shows the effective name, which
// the route labels, debug dump and status messages read. Names are display-
// only: the planner request never carries them, so renaming doesn't re-plan.
import { state } from './state.js';
import { cfgI } from './config.js';
import { fireTraverse } from './traverse.js';
import { persistTabs } from './tabs.js';
import { draw } from './draw.js';
import { updateDebugRoute } from './routeInfo.js';
import { updateEndpointFuelStrip } from './endpointFuelStripOverlay.js';
import { parseFuelText, formatFuelSteps, massToStripStep, STRIP_MAX_STEP,
         parseFuelConsumption } from './fuelStrip.js';
import { inflateTooltips } from './tooltip.js';

// CSS hook applied when the Fuel input (or an engine's Fuel/burn input) has
// unparseable text. Cleared on next valid edit. Visible affordance + we
// suppress fireTraverse while invalid so the planner doesn't run a stale
// request.
const FUEL_INPUT_INVALID_CLASS = 'fuel-input-invalid';

function markInputValid(el, valid, message) {
    el.classList.toggle(FUEL_INPUT_INVALID_CLASS, !valid);
    el.title = valid ? '' : (message || '');
}

function setFuelInputValid(valid, message) {
    const el = document.getElementById('fuel');
    if (!el) return;
    markInputValid(el, valid, message);
}

// Flags an engine's Fuel/burn input from its current text; returns the parse.
function validateEngineFuelInput(el) {
    const parsed = parseFuelConsumption(el.value);
    markInputValid(el, parsed.ok, parsed.error);
    return parsed;
}

// Longest custom engine name — same cap as tab names (tabs.js renameTab).
const ENGINE_NAME_MAX = 30;

function defaultEngineName(index) {
    return 'Engine ' + (index + 1);
}

// Trimmed and capped; '' means "no custom name".
function normalizeEngineName(name) {
    return (name == null ? '' : String(name)).trim().slice(0, ENGINE_NAME_MAX);
}

function renumberEngines() {
    const blocks = document.querySelectorAll('.engine-block');
    blocks.forEach((block, i) => {
        block.querySelector('.e-title').textContent =
                block.dataset.engineName || defaultEngineName(i);
    });
    document.getElementById('add-engine').disabled = blocks.length >= cfgI('ui.engines.max', 4);
}

// Inline rename of an engine block's title. Enter or clicking away saves,
// Esc cancels; an empty name (or the default "Engine N" typed back) clears
// the custom name so the title follows the engine's position again.
function beginEngineRename(block) {
    if (block.querySelector('.e-name')) return;   // already editing
    const title = block.querySelector('.e-title');
    const input = document.createElement('input');
    input.type = 'text';
    input.className = 'e-name';
    input.maxLength = ENGINE_NAME_MAX;
    input.spellcheck = false;
    input.autocomplete = 'off';
    input.value = title.textContent;
    title.style.display = 'none';
    title.after(input);
    input.focus();
    input.select();

    let finished = false;
    const finish = (save) => {
        if (finished) return;
        finished = true;
        if (save) {
            const index = Array.from(document.querySelectorAll('.engine-block')).indexOf(block);
            const name = normalizeEngineName(input.value);
            if (name && name !== defaultEngineName(index)) block.dataset.engineName = name;
            else delete block.dataset.engineName;
        }
        input.remove();
        title.style.display = '';
        renumberEngines();
        if (save) {
            // Display-only: refresh the route labels and debug dump, no re-plan.
            draw();
            updateDebugRoute();
            persistTabs();
        }
    };
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            finish(true);
        } else if (e.key === 'Escape') {
            // Cancel only — keep the global ESC handler (blur / clear routes) out.
            e.stopPropagation();
            finish(false);
        }
    });
    input.addEventListener('blur', () => finish(true));
}

// initial: optional {name, baseThrust, fuelConsumption, solarPowered,
// bonusPivots, canAfterburn, afterburnFuelCost, afterburnThrustGain, magSail,
// decommissionsOnAerobrake}. name is the custom title ('' / absent = "Engine
// N"). fuelConsumption is the Fuel/burn text ("2", "1/2", "0.5", …); older
// tab saves stored a plain number.
// When called from a tab restore, state.suppressFire is true so we don't
// fire a traverse for every engine block being rebuilt.
export function addEngineBlock(initial) {
    const blocks = document.querySelectorAll('.engine-block');
    if (blocks.length >= cfgI('ui.engines.max', 4)) return;
    const thrustDef = (initial && initial.baseThrust      != null) ? initial.baseThrust
                    : (initial && initial.netThrust       != null) ? initial.netThrust
                    : cfgI('ui.engine.thrust.default', 3);
    const fuelDef   = String((initial && initial.fuelConsumption != null)
                    ? initial.fuelConsumption : cfgI('ui.engine.fuel.default', 2));
    const pivotsDef = (initial && initial.bonusPivots     != null) ? initial.bonusPivots     : cfgI('ui.engine.pivots.default', 0);
    const solarDef  = !!(initial && initial.solarPowered);
    const abOnDef   = !!(initial && initial.canAfterburn);
    const abCostDef = (initial && initial.afterburnFuelCost  != null) ? initial.afterburnFuelCost  : 1;
    const abGainDef = (initial && initial.afterburnThrustGain != null) ? initial.afterburnThrustGain : 1;
    const magDef    = !!(initial && initial.magSail);
    const aerodecDef= !!(initial && initial.decommissionsOnAerobrake);
    const div = document.createElement('div');
    div.className = 'engine-block';
    div.innerHTML = `
        <div style="display:flex;justify-content:space-between;align-items:center;gap:6px;margin-bottom:6px">
            <h3 style="margin:0"><span class="e-title" tabindex="0" title="Click to rename">Engine</span></h3>
            <button class="e-remove" style="padding:2px 8px;font-size:11px;margin:0">X</button>
        </div>
        <div class="engine-row">
            <div><label>Base Thrust: <span class="tip" data-tip-key="engine.base-thrust">?</span></label><input type="number" class="e-base-thrust" value="${thrustDef}" min="0" max="20"></div>
            <div><label>Fuel/burn: <span class="tip" data-tip-key="engine.fuel">?</span></label><input type="text" class="e-fuel" spellcheck="false" autocomplete="off"></div>
        </div>
        <div class="engine-row">
            <div><label>Pivots: <span class="tip" data-tip-key="engine.pivots">?</span></label><input type="number" class="e-pivots" value="${pivotsDef}" min="0" max="5"></div>
            <div style="display:flex;flex-direction:column;gap:2px">
                <label><input type="checkbox" class="e-solar"${solarDef ? ' checked' : ''}>Solar <span class="tip" data-tip-key="engine.solar">?</span></label>
                <label><input type="checkbox" class="e-afterburn"${abOnDef ? ' checked' : ''}>Afterburn <span class="tip" data-tip-key="engine.afterburn">?</span></label>
            </div>
        </div>
        <div class="engine-row engine-ab-row" style="display:${abOnDef ? 'flex' : 'none'}">
            <div><label>AB cost: <span class="tip" data-tip-key="engine.ab-cost">?</span></label><input type="number" class="e-ab-cost" value="${abCostDef}" min="1" max="20"></div>
            <div><label>AB gain: <span class="tip" data-tip-key="engine.ab-gain">?</span></label><input type="number" class="e-ab-gain" value="${abGainDef}" min="1" max="10"></div>
        </div>
        <div class="engine-row">
            <div><label><input type="checkbox" class="e-mag-sail"${magDef ? ' checked' : ''}>Mag Sail <span class="tip" data-tip-key="engine.mag-sail">?</span></label></div>
            <div><label><input type="checkbox" class="e-aerobrake-decom"${aerodecDef ? ' checked' : ''}>Aerobrake decom <span class="tip" data-tip-key="engine.aerobrake-decom">?</span></label></div>
        </div>
    `;
    // Set as properties, not interpolated into the markup above: the text
    // comes from tab storage / share links and may be arbitrary.
    const name = normalizeEngineName(initial && initial.name);
    if (name) div.dataset.engineName = name;
    const fuelEl = div.querySelector('.e-fuel');
    fuelEl.value = fuelDef;
    validateEngineFuelInput(fuelEl);
    const titleEl = div.querySelector('.e-title');
    titleEl.addEventListener('click', () => beginEngineRename(div));
    titleEl.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            beginEngineRename(div);
        }
    });
    const abCb  = div.querySelector('.e-afterburn');
    const abRow = div.querySelector('.engine-ab-row');
    abCb.addEventListener('change', () => {
        abRow.style.display = abCb.checked ? 'flex' : 'none';
    });
    div.querySelector('.e-remove').addEventListener('click', () => {
        div.remove();
        renumberEngines();
        fireTraverse();
        persistTabs();
    });
    div.querySelectorAll('input:not(.e-fuel)').forEach(inp => {
        inp.addEventListener('change', () => { fireTraverse(); persistTabs(); });
    });
    // Fuel/burn is free text ("2", "1/2", "1+1/2", "0.5"): flag it while
    // typing, and only plan / persist once it parses.
    fuelEl.addEventListener('input', () => validateEngineFuelInput(fuelEl));
    fuelEl.addEventListener('change', () => {
        if (!validateEngineFuelInput(fuelEl).ok) return;
        fireTraverse();
        persistTabs();
    });
    document.getElementById('engines-container').appendChild(div);
    renumberEngines();
    // Populate data-tip on the freshly-inserted [data-tip-key] spans.
    // Cheap; safe even before the JSON load completes (becomes a no-op
    // and re-runs at load time via loadTooltips).
    inflateTooltips(div);
    if (!state.suppressFire) fireTraverse();
}

// One-shot wiring for the static add-engine button + fuel / dry-mass
// inputs. Called by main.js at startup.
export function initEngineForm() {
    document.getElementById('add-engine').addEventListener('click', () => {
        addEngineBlock();
        persistTabs();
    });
    const fuelEl = document.getElementById('fuel');
    const dryEl  = document.getElementById('dry-mass');

    // Fuel-text input — parse against current dryMass on every keystroke.
    // Invalid input flags the field and skips the traverse so we don't
    // round-trip nonsense; live overlay update still runs (with steps=0
    // if invalid, just renders Dry chit alone).
    fuelEl.addEventListener('input', () => {
        const dry = parseInt(dryEl.value) || 0;
        const parsed = parseFuelText(fuelEl.value, dry);
        setFuelInputValid(parsed.ok, parsed.error);
        updateEndpointFuelStrip();
    });
    fuelEl.addEventListener('change', () => {
        const dry = parseInt(dryEl.value) || 0;
        const parsed = parseFuelText(fuelEl.value, dry);
        if (!parsed.ok) return;   // don't fire on invalid
        fireTraverse();
        persistTabs();
    });

    // Dry-mass input — preserves the current Wet-strip POSITION rather
    // than the current "fuel" string. So if the user has typed "1+5/6"
    // (wet at step 14 with dry=1), bumping dry to 2 keeps wet at step
    // 14 and rewrites Fuel to "0+5/6" (5 steps from mass 2 → still
    // 2+5/6 on the strip). Clamp wet ≥ new dry.
    dryEl.addEventListener('input', () => {
        const oldDry = state.lastDryMass != null ? state.lastDryMass : (parseInt(dryEl.value) || 0);
        const newDry = parseInt(dryEl.value) || 0;
        const oldFuelText = fuelEl.value;
        const oldParsed = parseFuelText(oldFuelText, oldDry);
        if (oldParsed.ok) {
            const oldWetStep = massToStripStep(oldDry) + oldParsed.fuelSteps;
            const newDryStep = massToStripStep(newDry);
            const newFuelSteps = Math.max(0, oldWetStep - newDryStep);
            // Clamp if it would walk off the strip.
            const cappedFuelSteps = Math.min(newFuelSteps, STRIP_MAX_STEP - newDryStep);
            fuelEl.value = formatFuelSteps(cappedFuelSteps, newDry);
            setFuelInputValid(true);
        }
        state.lastDryMass = newDry;
        updateEndpointFuelStrip();
    });
    dryEl.addEventListener('change', () => {
        // change fires after input on commit; the fuel text is already
        // canonicalised to the new dryMass by the input handler. Just
        // fire the traverse if the fuel parse is still valid.
        const dry = parseInt(dryEl.value) || 0;
        const parsed = parseFuelText(fuelEl.value, dry);
        if (!parsed.ok) return;
        fireTraverse();
        persistTabs();
    });

    // Initialise lastDryMass so the first input event has a baseline.
    state.lastDryMass = parseInt(dryEl.value) || 0;
}
