// Engine form — rebuildable engine blocks plus the +/X buttons + the
// fuel/dry-mass inputs. Re-numbering keeps the visible "Engine N"
// titles in sync with their position; the Add button caps at
// ui.engines.max and disables itself accordingly.
import { state } from './state.js';
import { cfgI } from './config.js';
import { fireTraverse } from './traverse.js';
import { persistTabs } from './tabs.js';
import { updateEndpointFuelStrip } from './endpointFuelStripOverlay.js';

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
    document.getElementById('fuel').addEventListener('change', () => { fireTraverse(); persistTabs(); });
    document.getElementById('dry-mass').addEventListener('change', () => { fireTraverse(); persistTabs(); });
    // Live-update the fuel-strip overlay as the user types in the
    // dry-mass / fuel inputs. The change-listener above will also fire
    // a traverse, but we don't want the strip's fallback rendering to
    // wait for the network round-trip.
    document.getElementById('fuel').addEventListener('input', updateEndpointFuelStrip);
    document.getElementById('dry-mass').addEventListener('input', updateEndpointFuelStrip);
}
