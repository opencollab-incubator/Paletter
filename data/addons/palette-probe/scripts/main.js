// Palette probe, hand-written (nothing here is generated). Run on a Bedrock Dedicated Server by `update` (CaptureStage), with
// sequential block ids (block-network-ids-are-hashes=false) and a capture client that spawns and stays connected. When the
// player spawns, it places every state of every block type into a cell of its own next to the player, so the client receives
// the runtime id of each state in the block update of that cell; the tools pair the two. It prints:
//
//   PALETTE <index>/<total> <slice>   the JSON below in numbered chunks
//   PALETTE-DONE <total> chunks       all done (PALETTE-FAILED <error> if the script failed)
//
//   {cells: [[x, y, z, block id, {state: value}]], moved: [cell index]}
//     cells: every state, in the order placed; moved: the cells that did not hold their state when read back after the
//     placement (a block that fell, flowed or broke): their block updates say nothing of that state
//
// The tools draw the conclusions (dev.opencollab.paletter.tools.client.PaletteIds); this script draws none.
import { world, system, BlockPermutation, BlockStates, BlockTypes } from "@minecraft/server";

const CHUNK_SIZE = 2000;
// The lowest barrier layer, in open sky
const Y = 200;
// Cells per row and column: every second block of the 3 x 3 chunks around the player's chunk, which the client has
const SIDE = 24;

const wait = (ticks) => new Promise((resolve) => system.runTimeout(resolve, ticks));

// Native arrays of the Script API are not always iterable; copy them by index
function toArray(list) {
    const array = [];
    for (let i = 0; list && i < list.length; i++) array.push(list[i]);
    return array;
}

// Every state of a block: every combination of the values its states accept, as BDS resolves them (legacy alias states make
// some combinations the same state). The same enumeration as data/addons/probe and data/addons/behaviour-dump.
function statesOf(id) {
    const base = BlockPermutation.resolve(id);
    const defaults = base.getAllStates();
    const options = Object.keys(defaults).sort().map((name) => [name, toArray(BlockStates.get(name)?.validValues).filter((value) => {
        try {
            const changed = base.withState(name, value);
            return changed.type.id === id && changed.getState(name) === value;
        } catch {
            return false;
        }
    })]);
    const result = new Map();
    const combine = (i, states) => {
        if (i === options.length) {
            try {
                const permutation = BlockPermutation.resolve(id, states);
                const all = permutation.getAllStates();
                result.set(JSON.stringify(Object.keys(all).sort().map((name) => [name, all[name]])), permutation);
            } catch {
                // not a state of the block
            }
            return;
        }
        const [name, values] = options[i];
        for (const value of values.length ? values : [defaults[name]]) combine(i + 1, { ...states, [name]: value });
    };
    combine(0, {});
    return [...result.keys()].sort().map((key) => result.get(key));
}

function key(permutation) {
    const all = permutation.getAllStates();
    return permutation.type.id + JSON.stringify(Object.keys(all).sort().map((name) => [name, all[name]]));
}

async function main(player) {
    const dim = world.getDimension("overworld");
    const x0 = Math.floor(player.location.x / 16) * 16 - 16;
    const z0 = Math.floor(player.location.z / 16) * 16 - 16;
    const states = [];
    for (const type of toArray(BlockTypes.getAll()).sort((a, b) => (a.id < b.id ? -1 : 1))) {
        try {
            for (const permutation of statesOf(type.id)) states.push(permutation);
        } catch (e) {
            console.warn(`PALETTE-ERROR no states for ${type.id}: ${e}`);
        }
    }
    const layers = Math.ceil(states.length / (SIDE * SIDE));
    const top = Y + 2 * layers;
    if (top > 318) {
        throw new Error(`${states.length} states need ${layers} layers of cells, more than fit below the build limit`);
    }
    for (let i = 0; i < 200 && !(dim.getBlock({ x: x0, y: Y, z: z0 }) && dim.getBlock({ x: x0 + 2 * SIDE, y: top, z: z0 + 2 * SIDE })); i++) {
        await wait(5);
    }
    // Cells walled in by barriers, so nothing of one cell (water, falling blocks) reaches another
    for (let y = Y; y <= top; y++) {
        dim.runCommand(`fill ${x0} ${y} ${z0} ${x0 + 2 * SIDE} ${y} ${z0 + 2 * SIDE} barrier`);
    }
    await wait(5);
    const cells = states.map((permutation, i) => {
        const inLayer = i % (SIDE * SIDE);
        return { x: x0 + 1 + 2 * (inLayer % SIDE), y: Y + 1 + 2 * Math.floor(i / (SIDE * SIDE)), z: z0 + 1 + 2 * Math.floor(inLayer / SIDE) };
    });
    await new Promise((resolve, reject) => system.runJob((function* () {
        try {
            for (let i = 0; i < states.length; i++) {
                try {
                    dim.getBlock(cells[i]).setPermutation(states[i]);
                } catch (e) {
                    console.warn(`PALETTE-ERROR could not place ${key(states[i])}: ${e}`);
                }
                if (i % 100 === 99) yield;
            }
            resolve();
        } catch (e) {
            reject(e);
        }
    })()));
    await wait(10);
    const moved = [];
    states.forEach((permutation, i) => {
        const block = dim.getBlock(cells[i]);
        if (!block || key(block.permutation) !== key(permutation)) moved.push(i);
    });
    const json = JSON.stringify({
        cells: states.map((permutation, i) => [cells[i].x, cells[i].y, cells[i].z, permutation.type.id, permutation.getAllStates()]),
        moved,
    });
    const total = Math.ceil(json.length / CHUNK_SIZE);
    for (let i = 0; i < total; i++) {
        console.warn(`PALETTE ${i}/${total} ${json.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE)}`);
        if (i % 50 === 49) await wait(1);
    }
    console.warn(`PALETTE-DONE ${total} chunks`);
}

let started = false;
world.afterEvents.playerSpawn.subscribe((event) => {
    if (!event.initialSpawn || started) return;
    started = true;
    console.warn("PALETTE-START");
    main(event.player).catch((e) => console.warn(`PALETTE-FAILED ${e} ${e?.stack}`));
});
