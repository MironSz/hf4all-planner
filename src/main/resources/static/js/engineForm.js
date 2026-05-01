// Engine form — rebuildable engine blocks plus the +/X buttons + the
// fuel/dry-mass inputs. Re-numbering keeps the visible "Engine N"
// titles in sync with their position; the Add button caps at
// ui.engines.max and disables itself accordingly.
import { state } from './state.js';
import { cfgI } from './config.js';
import { fireTraverse } from './traverse.js';
import { persistTabs } from './tabs.js';
import { updateEndpointFuelStrip } from './endpointFuelStripOverlay.js';
import { parseFuelText, formatFuelSteps, massToStripStep, STRIP_MAX_STEP }
        from './fuelStrip.js';

// CSS hook applied when the Fuel input has unparseable text. Cleared on
// next valid edit. Visible affordance + we suppress fireTraverse while
// invalid so the planner doesn't run a stale request.
const FUEL_INPUT_INVALID_CLASS = 'fuel-input-invalid';

function setFuelInputValid(valid, message) {
    const el = document.getElementById('fuel');
    if (!el) return;
    el.classList.toggle(FUEL_INPUT_INVALID_CLASS, !valid);
    el.title = valid ? '' : (message || '');
}

function renumberEngines() {
    const blocks = document.querySelectorAll('.engine-block');
    blocks.forEach((block, i) => {
        block.querySelector('h3').textContent = 'Engine ' + (i + 1);
    });
    document.getElementById('add-engine').disabled = blocks.length >= cfgI('ui.engines.max', 4);
}

// initial: optional {baseThrust, fuelConsumption, solarPowered, bonusPivots,
// canAfterburn, afterburnFuelCost, afterburnThrustGain}.
// When called from a tab restore, state.suppressFire is true so we don't
// fire a traverse for every engine block being rebuilt.
export function addEngineBlock(initial) {
    const blocks = document.querySelectorAll('.engine-block');
    if (blocks.length >= cfgI('ui.engines.max', 4)) return;
    const thrustDef = (initial && initial.baseThrust      != null) ? initial.baseThrust
                    : (initial && initial.netThrust       != null) ? initial.netThrust
                    : cfgI('ui.engine.thrust.default', 3);
    const fuelDef   = (initial && initial.fuelConsumption != null) ? initial.fuelConsumption : cfgI('ui.engine.fuel.default',   2);
    const pivotsDef = (initial && initial.bonusPivots     != null) ? initial.bonusPivots     : cfgI('ui.engine.pivots.default', 0);
    const solarDef  = !!(initial && initial.solarPowered);
    const abOnDef   = !!(initial && initial.canAfterburn);
    const abCostDef = (initial && initial.afterburnFuelCost  != null) ? initial.afterburnFuelCost  : 1;
    const abGainDef = (initial && initial.afterburnThrustGain != null) ? initial.afterburnThrustGain : 1;
    const div = document.createElement('div');
    div.className = 'engine-block';
    div.innerHTML = `
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px">
            <h3 style="margin:0">Engine</h3>
            <button class="e-remove" style="padding:2px 8px;font-size:11px;margin:0">X</button>
        </div>
        <div class="engine-row">
            <div><label>Base Thrust: <span class="tip" data-tip="Engine thrust per burn before adjustments.">?</span></label><input type="number" class="e-base-thrust" value="${thrustDef}" min="0" max="20"></div>
            <div><label>Fuel/burn: <span class="tip" data-tip="Fuel steps this engine consumes per burn. Most engines are 1–2; high-thrust engines cost more.">?</span></label><input type="number" class="e-fuel" value="${fuelDef}" min="0" max="20"></div>
        </div>
        <div class="engine-row">
            <div><label>Pivots: <span class="tip" data-tip="Lets you turn at non-Lagrange nodes without ending the turn or performing double burn.">?</span></label><input type="number" class="e-pivots" value="${pivotsDef}" min="0" max="5"></div>
            <div style="display:flex;flex-direction:column;gap:2px">
                <label><input type="checkbox" class="e-solar"${solarDef ? ' checked' : ''}>Solar <span class="tip" data-tip="Solar-powered engine: thrust scales with distance from the Sun.">?</span></label>
                <label><input type="checkbox" class="e-afterburn"${abOnDef ? ' checked' : ''}>Afterburn <span class="tip" data-tip="Once per movement: spend fuel to gain net thrust.">?</span></label>
            </div>
        </div>
        <div class="engine-row engine-ab-row" style="display:${abOnDef ? 'flex' : 'none'}">
            <div><label>AB cost: <span class="tip" data-tip="Fuel steps spent per afterburn use.">?</span></label><input type="number" class="e-ab-cost" value="${abCostDef}" min="1" max="20"></div>
            <div><label>AB gain: <span class="tip" data-tip="Net-thrust gained per afterburn use.">?</span></label><input type="number" class="e-ab-gain" value="${abGainDef}" min="1" max="10"></div>
        </div>
    `;
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
    div.querySelectorAll('input').forEach(inp => {
        inp.addEventListener('change', () => { fireTraverse(); persistTabs(); });
    });
    document.getElementById('engines-container').appendChild(div);
    renumberEngines();
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
