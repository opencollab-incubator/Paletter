// Behaviour probe, hand-written (the add-on blocks and entity next to it are hand-written too; nothing here is generated).
// Run on a Bedrock Dedicated Server by `behaviour-probe` (tools). It measures what the engine does, by experiment, and
// prints every observation as one console line:
//
//   PROBE <tag> <json>      one observation, see the sections below
//   PROBE-DONE              all done            (PROBE-FAILED <error> if the script failed)
//
// The tools parse the lines (org.geysermc.paletter.tools.behaviour.ProbeLog); this script draws no conclusions.
//
//   friction  {block, entity: [[tick, velocityX, x, y], ...]}   a body slid over 90 blocks of the floor; the factor
//                                                               velocity falls by per tick is the floor's friction * 0.91
//   cell      {block, half, state, side, level, rel, y}         a floating body that started 1 block outside the block's
//                                                               cell on each of its 4 sides, pushed towards it, and where
//                                                               it stopped on that axis, relative to the cell's minimum
//                                                               corner, at height y; the body is 0.5 wide, so it stops
//                                                               0.25 from the face of what blocks it. A body nothing
//                                                               blocks runs through the cell and ends beyond its centre.
//                                                               half: the body is level with the lower or the upper block
//                                                               (level: its height)
//   drop      {block, blockY, y}                                a body fell onto the block (at height blockY) and stopped at y
//   light     {kind, block, sky, light, self}                   light levels, see lightCells
//   tunnel    {block, behind, inside}                           block light behind and inside a block next to glowstone
//   explosion {block: {distance: [destroyed, total]}}           blocks beside an explosion (power 4)
//   flammable {block: {remaining, total}}                       blocks with fire on top after 400 ticks of fire ticking
//   climb     {block, states, climbing: [state], lost: [state]} every state of every block type, each in its own cell walled
//                                                               in by barriers, with a body that can climb
//                                                               (minecraft:can_climb, no wall climbing, no gravity) at its
//                                                               feet; climbing lists the states where Entity.isClimbing was
//                                                               true after 3 ticks; states is how many states were tried;
//                                                               lost: states the body was gone after (not measured)
//
// The per-state sections below are not essential: each one that fails prints `failed` and the probe goes on (the tools then
// call the section "unverified"). Each prints one line per block type:
//
//   light_state    {block, states, base, differ, changed}        every state in a sealed cell with a stone ceiling and an air cell
//                                                               below it, then with the ceiling open to the sky; a reading is
//                                                               [light in the state's cell, light in the air cell below,
//                                                               sky light in the air cell below]
//   friction_state {block, states, base, differ, changed}        every state as a floor of 8 blocks, a body slid over it; a
//                                                               reading is {v: the body's horizontal velocity on 10 ticks,
//                                                               dy: how far its height moved}
//   around         {block, state, attempts}                      the states of 4 blocks that BDS turns into another block when
//                                                               it places them, tried inside a sealed box with the blocks
//                                                               around them each recipe names; attempts: [{recipe, kept, light}]
//   failed         {section, error}                              a section that could not run
//
// A block's lines say what BDS holds for each state it observed: `base` is the reading most states have, `differ` lists
// [state, reading] for the others, and `changed` lists [requested state, the block that was there, its state or null] for each
// state BDS did not keep as requested (a stair's shape follows its neighbours, a flowing liquid becomes still): the reading
// belongs to the state that was there (and so is in `base` or `differ`), the requested state is not measured by it.
import { world, system, BlockPermutation, BlockStates, BlockTypes } from "@minecraft/server";

const out = (tag, data) => console.warn(`PROBE ${tag} ${JSON.stringify(data)}`);
const wait = (ticks) => new Promise((resolve) => system.runTimeout(resolve, ticks));
const Y = 200; // everything is built in open sky

const FRICTION_FLOORS = ["minecraft:stone", "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice", "probe:plain",
    "probe:friction_0", "probe:friction_10", "probe:friction_20", "probe:friction_40", "probe:friction_50",
    "probe:friction_80", "probe:friction_90"];
// side, where the body starts relative to the cell's minimum corner (1 block outside the cell), direction it is pushed in
const SIDES = [["+x", 2, 0.5, -1, 0], ["-x", -1, 0.5, 1, 0], ["+z", 0.5, 2, 0, -1], ["-z", 0.5, -1, 0, 1]];

async function main() {
    const dim = world.getDimension("overworld");
    const set = (x, y, z, id) => dim.setBlockType({ x, y, z }, id);
    for (const command of ["gamerule domobspawning false", "gamerule dodaylightcycle false", "time set noon",
        "gamerule dofiretick false", "tickingarea add 0 190 0 95 230 159 probe1", "tickingarea add 96 190 0 191 230 159 probe2"]) {
        dim.runCommand(command);
    }
    for (let i = 0; i < 200; i++) {
        let loaded = true;
        try {
            for (let x = 0; x < 192; x += 16) for (let z = 0; z < 160; z += 16) if (!dim.getBlock({ x, y: Y, z })) loaded = false;
        } catch { loaded = false; }
        if (loaded) break;
        await wait(10);
    }

    // ---------- light: built first so it has settled when it is read ----------
    // lid: a cell of stone with the block as its ceiling; emit: the block on the floor of a cell with a stone ceiling
    const lightCells = [];
    const lightCell = (x, z, ceiling, floor) => {
        for (let dx = -1; dx <= 1; dx++) for (let dz = -1; dz <= 1; dz++) {
            for (let y = Y; y <= Y + 3; y++) set(x + dx, y, z + dz, "minecraft:stone");
            set(x + dx, Y - 1, z + dz, "minecraft:stone");
        }
        for (let y = Y; y <= Y + 2; y++) set(x, y, z, "minecraft:air");
        set(x, Y, z, floor);
        set(x, Y + 3, z, ceiling);
    };
    let at = 110;
    for (const block of ["minecraft:stone", "minecraft:glass", "probe:plain", "probe:light_dampening_0", "probe:light_dampening_14",
        "probe:light_emission_15", "minecraft:air"]) {
        lightCell(at, 10, block, "minecraft:air");
        lightCells.push({ kind: "lid", block, x: at, y: Y + 1, z: 10, self: { x: at, y: Y + 3, z: 10 } });
        at += 4;
    }
    at = 110;
    for (const block of ["minecraft:stone", "probe:plain", "probe:light_emission_15", "minecraft:glowstone"]) {
        lightCell(at, 20, "minecraft:stone", block);
        lightCells.push({ kind: "emit", block, x: at, y: Y + 1, z: 20, self: { x: at, y: Y, z: 20 } });
        at += 4;
    }
    // tunnels: glowstone, the block, air, all in stone
    const tunnels = [];
    at = 110;
    for (const block of ["minecraft:air", "minecraft:glass", "minecraft:stone", "probe:plain", "probe:light_dampening_0",
        "probe:light_dampening_14", "probe:light_emission_15"]) {
        for (let x = at - 1; x <= at + 4; x++) for (let y = Y - 1; y <= Y + 1; y++) for (let z = 39; z <= 41; z++) set(x, y, z, "minecraft:stone");
        set(at, Y, 40, "minecraft:glowstone");
        set(at + 1, Y, 40, block);
        set(at + 2, Y, 40, "minecraft:air");
        tunnels.push({ block, behind: { x: at + 2, y: Y, z: 40 }, inside: { x: at + 1, y: Y, z: 40 } });
        at += 7;
    }

    // ---------- friction ----------
    const lanes = FRICTION_FLOORS.map((block, i) => ({ block, z: 4 + 4 * i }));
    for (const lane of lanes) for (let x = 0; x < 90; x++) set(x, Y - 1, lane.z, lane.block);
    await wait(40);
    const bodies = lanes.map((lane) => dim.spawnEntity("probe:body", { x: 2.5, y: Y, z: lane.z + 0.5 }));
    await wait(30);
    bodies.forEach((body) => body.applyImpulse({ x: 0.5, y: 0, z: 0 }));
    const tracks = bodies.map(() => []);
    for (let tick = 0; tick < 25; tick++) {
        bodies.forEach((body, i) => tracks[i].push([tick, body.getVelocity().x, body.location.x, body.location.y]));
        await wait(1);
    }
    lanes.forEach((lane, i) => out("friction", { block: lane.block, entity: tracks[i] }));
    bodies.forEach((body) => body.remove());

    // ---------- collision cells: the blocks, and every door in every state ----------
    // One block (or door) per cell, 36 cells in a grid, on a stone floor (a door needs one); floating bodies (no gravity: they
    // are level with the block they are asked to be) are pushed at it from its 4 sides. Pushed like this a free body slides
    // about 3 blocks, from where it starts to past the cell's centre
    const cellAt = (i) => ({ cx: 10 + 12 * (i % 6), cz: 70 + 12 * Math.floor(i / 6) });
    const wave = async (cells) => {
        const bodies = [];
        cells.forEach((cell, i) => {
            const { cx, cz } = cellAt(i);
            set(cx, Y - 1, cz, "minecraft:stone");
            cell.place(cx, cz);
            for (const half of cell.halves) for (const [side, dx, dz, ix, iz] of SIDES) {
                const level = half === "upper" ? Y + 1 : Y;
                bodies.push({ cell, cx, cz, half, level, side, impulse: { x: ix * 1.4, y: 0, z: iz * 1.4 },
                    body: dim.spawnEntity("probe:floater", { x: cx + dx, y: level, z: cz + dz }) });
            }
        });
        await wait(30);
        bodies.forEach((b) => b.body.applyImpulse(b.impulse));
        await wait(70);
        for (const b of bodies) {
            const loc = b.body.location;
            out("cell", {
                block: b.cell.block, half: b.half, state: b.cell.state(b.half), side: b.side, level: b.level,
                rel: b.side.endsWith("x") ? loc.x - b.cx : loc.z - b.cz, y: loc.y,
            });
            b.body.remove();
        }
        cells.forEach((cell, i) => { const { cx, cz } = cellAt(i); set(cx, Y, cz, "minecraft:air"); set(cx, Y + 1, cz, "minecraft:air"); });
    };
    const solid = (block) => ({
        block, halves: ["lower"], state: () => null,
        place: (cx, cz) => { set(cx, Y, cz, block); set(cx, Y + 1, cz, block); },
    });
    await wave([solid("minecraft:stone"), solid("probe:plain")]);

    const doors = BlockTypes.getAll().map((type) => type.id).filter((id) => id.endsWith("_door")).sort();
    for (const door of doors) {
        const base = BlockPermutation.resolve(door).getAllStates();
        const cells = [];
        for (const direction of ["north", "east", "south", "west"]) for (const open of [false, true]) for (const hinge of [false, true]) {
            const lower = { ...base, "minecraft:cardinal_direction": direction, open_bit: open, door_hinge_bit: hinge, upper_block_bit: false };
            const upper = { ...lower, upper_block_bit: true };
            let location;
            cells.push({
                block: door, halves: ["lower", "upper"],
                state: (half) => dim.getBlock({ x: location.cx, y: half === "upper" ? Y + 1 : Y, z: location.cz }).permutation.getAllStates(),
                place: (cx, cz) => {
                    location = { cx, cz };
                    dim.getBlock({ x: cx, y: Y, z: cz }).setPermutation(BlockPermutation.resolve(door, lower));
                    dim.getBlock({ x: cx, y: Y + 1, z: cz }).setPermutation(BlockPermutation.resolve(door, upper));
                },
            });
        }
        await wave(cells);
    }

    // ---------- a body falling onto the block ----------
    const drops = ["minecraft:stone", "probe:plain", "probe:no_collision"].map((block, i) => ({ block, x: 110 + 4 * i }));
    for (const drop of drops) set(drop.x, Y + 5, 30, drop.block);
    await wait(20);
    const fallers = drops.map((drop) => dim.spawnEntity("probe:body", { x: drop.x + 0.5, y: Y + 8, z: 30.5 }));
    await wait(40);
    drops.forEach((drop, i) => out("drop", { block: drop.block, blockY: Y + 5, y: fallers[i].isValid ? fallers[i].location.y : null }));
    fallers.forEach((body) => body.isValid && body.remove());

    // ---------- light, read ----------
    for (const c of lightCells) {
        out("light", {
            kind: c.kind, block: c.block, sky: dim.getSkyLightLevel({ x: c.x, y: c.y, z: c.z }),
            light: dim.getLightLevel({ x: c.x, y: c.y, z: c.z }), self: dim.getLightLevel(c.self),
        });
    }
    for (const t of tunnels) out("tunnel", { block: t.block, behind: dim.getLightLevel(t.behind), inside: dim.getLightLevel(t.inside) });

    // ---------- explosion resistance ----------
    // each explosion has 6 blocks around it at the same distance, one per direction, rotated every repetition
    const tested = ["minecraft:dirt", "minecraft:sand", "probe:plain", "probe:explosion_resistance_0", "probe:destructible_by_explosion",
        "probe:explosion_resistance_3"];
    const directions = [[1, 0, 0], [-1, 0, 0], [0, 0, 1], [0, 0, -1], [0, 1, 0], [0, -1, 0]];
    const stats = {};
    const centres = [];
    for (let gx = 0; gx < 9; gx++) for (let gz = 0; gz < 8; gz++) centres.push({ x: 10 + gx * 20, z: 10 + gz * 20 });
    let next = 0;
    for (let repetition = 0; repetition < 48; repetition++) for (const distance of [6, 7]) {
        if (next >= centres.length) { next = 0; await wait(5); }
        const c = centres[next++];
        const cy = Y + 15;
        const placed = tested.map((block, k) => {
            const d = directions[(k + repetition) % 6];
            const pos = { x: c.x + d[0] * distance, y: cy + d[1] * distance, z: c.z + d[2] * distance };
            dim.setBlockType(pos, block);
            return { block, pos };
        });
        dim.createExplosion({ x: c.x + 0.5, y: cy + 0.5, z: c.z + 0.5 }, 4, { breaksBlocks: true });
        for (const b of placed) {
            const count = ((stats[b.block] ??= {})[distance] ??= [0, 0]);
            count[1]++;
            if (dim.getBlock(b.pos).typeId !== b.block) count[0]++;
            dim.setBlockType(b.pos, "minecraft:air");
        }
    }
    out("explosion", stats);

    // ---------- flammability: fire on top of 12 blocks of each kind ----------
    const fires = [];
    ["minecraft:oak_planks", "probe:plain", "probe:flammable", "minecraft:stone"].forEach((block, i) => {
        for (let k = 0; k < 12; k++) {
            const x = 150 + 3 * k, z = 4 + 6 * i;
            set(x, Y, z, block);
            set(x, Y + 1, z, "minecraft:fire");
            fires.push({ block, x, z });
        }
    });
    dim.runCommand("gamerule dofiretick true");
    dim.runCommand("gamerule randomtickspeed 100");
    await wait(400);
    const burnt = {};
    for (const f of fires) {
        const count = (burnt[f.block] ??= { remaining: 0, total: 0 });
        count.total++;
        if (dim.getBlock({ x: f.x, y: Y, z: f.z }).typeId === f.block) count.remaining++;
    }
    out("flammable", burnt);

    const states = allStates();
    await climbing(dim, states);
    await section("light", () => lightStates(dim, states));
    await section("friction", () => frictionStates(dim, states));
    await section("around", () => aroundRecipes(dim, states));
    console.warn("PROBE-DONE");
}

// ---------- climbing ----------

// Native arrays of the Script API are not always iterable; copy them by index
function toArray(list) {
    const array = [];
    for (let i = 0; list && i < list.length; i++) array.push(list[i]);
    return array;
}

// Every state of a block: every combination of the values its states accept, as BDS resolves them (legacy alias states make
// some combinations the same state). The same enumeration as data/addons/behaviour-dump.
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

// Every state of every block type, sorted by block
function allStates() {
    const states = [];
    for (const type of toArray(BlockTypes.getAll()).sort((a, b) => (a.id < b.id ? -1 : 1))) {
        try {
            for (const permutation of statesOf(type.id)) states.push(permutation);
        } catch (e) {
            console.warn(`PROBE-ERROR no states for ${type.id}: ${e}`);
        }
    }
    return states;
}

async function climbing(dim, states) {
    // cells every 2 blocks in a slab of barriers, so nothing of one cell (water, falling blocks) reaches another
    const X = 0, Z = 200, CY = 120, ROWS = 16, COLUMNS = 32;
    dim.runCommand(`tickingarea add ${X - 2} ${CY - 2} ${Z - 2} ${X + 2 * COLUMNS + 2} ${CY + 2} ${Z + 2 * ROWS + 2} probe3`);
    for (let i = 0; i < 200 && !dim.getBlock({ x: X + 2 * COLUMNS, y: CY, z: Z + 2 * ROWS }); i++) await wait(10);
    for (let x = X - 1; x <= X + 2 * COLUMNS; x += 16) {
        dim.runCommand(`fill ${x} ${CY - 1} ${Z - 1} ${Math.min(x + 15, X + 2 * COLUMNS)} ${CY + 1} ${Z + 2 * ROWS} barrier`);
    }
    const cells = [];
    for (let r = 0; r < ROWS; r++) for (let c = 0; c < COLUMNS; c++) cells.push({ x: X + 2 * c, z: Z + 2 * r });
    const feet = (cell) => ({ x: cell.x + 0.5, y: CY + 0.1, z: cell.z + 0.5 });
    const climbers = cells.map((cell) => dim.spawnEntity("probe:climber", feet(cell)));

    const results = new Map(); // block -> {states, climbing}
    for (let start = 0; start < states.length; start += cells.length) {
        const wave = states.slice(start, start + cells.length);
        wave.forEach((permutation, i) => {
            try {
                dim.getBlock({ x: cells[i].x, y: CY, z: cells[i].z }).setPermutation(permutation);
            } catch (e) {
                console.warn(`PROBE-ERROR could not place ${permutation.type.id}: ${e}`);
            }
            // a block can remove the body (a portal takes it away): a new one then
            if (!climbers[i].isValid) climbers[i] = dim.spawnEntity("probe:climber", feet(cells[i]));
            climbers[i].teleport(feet(cells[i]));
        });
        await wait(3);
        wave.forEach((permutation, i) => {
            const result = results.get(permutation.type.id) ?? { states: 0, climbing: [], lost: [] };
            results.set(permutation.type.id, result);
            result.states++;
            if (!climbers[i].isValid) result.lost.push(permutation.getAllStates());
            else if (climbers[i].isClimbing) result.climbing.push(permutation.getAllStates());
        });
        wave.forEach((permutation, i) => dim.getBlock({ x: cells[i].x, y: CY, z: cells[i].z }).setType("minecraft:barrier"));
        await wait(1);
    }
    climbers.forEach((climber) => climber.isValid && climber.remove());
    for (const [block, result] of results) out("climb", { block, ...result });
}

// ---------- per-state sections (not essential) ----------

// A section that fails prints `failed` and the probe goes on
async function section(name, run) {
    try {
        await run();
    } catch (e) {
        out("failed", { section: name, error: `${e} ${e?.stack}` });
    }
}

const stateKey = (state) => JSON.stringify(Object.keys(state).sort().map((name) => [name, state[name]]));

// What is in a cell: the block's id and its state
const placed = (block) => ({ block: block.typeId, state: block.permutation.getAllStates() });
const samePlaced = (a, b) => a.block === b.block && stateKey(a.state) === stateKey(b.state);

async function loadedArea(dim, from, to, name) {
    dim.runCommand(`tickingarea add ${from.x} ${from.y} ${from.z} ${to.x} ${to.y} ${to.z} ${name}`);
    for (let i = 0; i < 200 && !dim.getBlock(to); i++) await wait(10);
}

// Prints one line per block type (see the header). entries: [{requested: BlockPermutation, actual: {block, state} | null,
// reading: reading | null}]: the state that was there after the request, null where nothing could be placed, and its reading,
// null where it could not be read (the cell changed while it was read)
function printGrouped(tag, entries) {
    const byBlock = new Map();
    for (const entry of entries) {
        const id = entry.requested.type.id;
        if (!byBlock.has(id)) byBlock.set(id, []);
        byBlock.get(id).push(entry);
    }
    for (const [block, list] of byBlock) {
        const seen = new Map(); // key of the state that was there -> {state, reading}
        const changed = [];
        for (const { requested, actual, reading } of list) {
            const asked = requested.getAllStates();
            const kept = actual && actual.block === block;
            if (kept && reading !== null && !seen.has(stateKey(actual.state))) seen.set(stateKey(actual.state), { state: actual.state, reading });
            if (reading === null || !kept || stateKey(actual.state) !== stateKey(asked)) {
                changed.push([asked, actual ? actual.block : null, kept ? actual.state : null]);
            }
        }
        const counts = new Map();
        for (const { reading } of seen.values()) counts.set(JSON.stringify(reading), (counts.get(JSON.stringify(reading)) ?? 0) + 1);
        let base = null;
        let most = 0;
        for (const [key, count] of counts) if (count > most) { base = key; most = count; }
        const differ = [...seen.values()].filter(({ reading }) => JSON.stringify(reading) !== base).map(({ state, reading }) => [state, reading]);
        out(tag, { block, states: list.length, base: base === null ? null : JSON.parse(base), differ, changed });
    }
}

// ---------- light, every state ----------

// A column per state, 2 blocks apart in a slab of stone: the air cell, the state above it, a stone ceiling. Sealed (the
// ceiling is stone) the light in the state's cell and in the cell below it say how much it emits; with the ceiling open to
// the sky, the sky light in the cell below says how much it dampens (sky light is a channel of its own, so what the state
// emits does not mix into it).
async function lightStates(dim, states) {
    const X = 0, Z = 400, COLUMNS = 16, ROWS = 32;
    await loadedArea(dim, { x: X - 2, y: Y - 2, z: Z - 2 }, { x: X + 2 * COLUMNS + 2, y: Y + 4, z: Z + 2 * ROWS + 2 }, "probe4");
    dim.runCommand(`fill ${X - 1} ${Y - 1} ${Z - 1} ${X + 2 * COLUMNS} ${Y + 2} ${Z + 2 * ROWS} stone`);
    const columns = [];
    for (let r = 0; r < ROWS; r++) for (let c = 0; c < COLUMNS; c++) columns.push({ x: X + 2 * c, z: Z + 2 * r });
    const cellOf = (c) => ({ x: c.x, y: Y, z: c.z });
    const slotOf = (c) => ({ x: c.x, y: Y + 1, z: c.z });
    const ceilingOf = (c) => ({ x: c.x, y: Y + 2, z: c.z });
    for (const c of columns) {
        dim.setBlockType(cellOf(c), "minecraft:air");
        dim.setBlockType(slotOf(c), "minecraft:air");
    }
    const entries = [];
    for (let start = 0; start < states.length; start += columns.length) {
        const wave = states.slice(start, start + columns.length).map((requested, i) => ({ requested, column: columns[i], actual: null, reading: null }));
        for (const w of wave) {
            try {
                dim.getBlock(slotOf(w.column)).setPermutation(w.requested);
                w.actual = placed(dim.getBlock(slotOf(w.column)));
            } catch {
                // not placeable: no reading
            }
        }
        await wait(4);
        for (const w of wave) {
            if (w.actual) {
                w.self = dim.getLightLevel(slotOf(w.column));
                w.adjacent = dim.getLightLevel(cellOf(w.column));
            }
        }
        wave.forEach((w) => dim.setBlockType(ceilingOf(w.column), "minecraft:air"));
        await wait(4);
        for (const w of wave) {
            if (!w.actual) continue;
            const sky = dim.getSkyLightLevel(cellOf(w.column));
            // a liquid that flowed into the cell below, or a block that changed itself: the readings are not of the state
            const still = dim.getBlock(cellOf(w.column)).typeId === "minecraft:air" && samePlaced(placed(dim.getBlock(slotOf(w.column))), w.actual);
            w.reading = still ? [w.self, w.adjacent, sky] : null;
        }
        wave.forEach((w) => {
            dim.setBlockType(ceilingOf(w.column), "minecraft:stone");
            dim.setBlockType(slotOf(w.column), "minecraft:air");
            dim.setBlockType(cellOf(w.column), "minecraft:air");
        });
        await wait(1);
        wave.forEach((w) => entries.push({ requested: w.requested, actual: w.actual, reading: w.reading }));
    }
    printGrouped("light_state", entries);
}

// ---------- friction, every state ----------

// A lane of 8 blocks of the state as a floor, a body dropped on it, pushed, and its speed on 10 ticks
async function frictionStates(dim, states) {
    const X = 0, Z = 500, COLUMNS = 8, ROWS = 16, LENGTH = 8, PITCH = 12, TICKS = 10;
    await loadedArea(dim, { x: X - 2, y: Y - 6, z: Z - 2 }, { x: X + COLUMNS * PITCH, y: Y + 6, z: Z + 2 * ROWS + 2 }, "probe5");
    const lanes = [];
    for (let r = 0; r < ROWS; r++) for (let c = 0; c < COLUMNS; c++) lanes.push({ x: X + PITCH * c, z: Z + 2 * r });
    const round = (value, digits) => Number(value.toPrecision(digits));
    const entries = [];
    for (let start = 0; start < states.length; start += lanes.length) {
        const wave = states.slice(start, start + lanes.length).map((requested, i) => ({ requested, lane: lanes[i], actual: null, reading: null }));
        for (const w of wave) {
            try {
                for (let k = 0; k < LENGTH; k++) dim.getBlock({ x: w.lane.x + k, y: Y - 1, z: w.lane.z }).setPermutation(w.requested);
                // the block the body is dropped on
                w.actual = placed(dim.getBlock({ x: w.lane.x + 1, y: Y - 1, z: w.lane.z }));
            } catch {
                // not placeable: no reading
            }
        }
        await wait(2);
        const bodies = wave.map((w) => (w.actual ? dim.spawnEntity("probe:body", { x: w.lane.x + 1.5, y: Y + 2, z: w.lane.z + 0.5 }) : null));
        await wait(25);
        const heights = bodies.map((body) => (body && body.isValid ? body.location.y : null));
        bodies.forEach((body) => body && body.isValid && body.applyImpulse({ x: 0.5, y: 0, z: 0 }));
        const velocities = bodies.map(() => []);
        const moved = bodies.map(() => 0);
        for (let tick = 0; tick < TICKS; tick++) {
            bodies.forEach((body, i) => {
                if (body && body.isValid && velocities[i]) {
                    velocities[i].push(round(body.getVelocity().x, 6));
                    moved[i] = Math.max(moved[i], Math.abs(body.location.y - heights[i]));
                } else {
                    velocities[i] = null;
                }
            });
            await wait(1);
        }
        wave.forEach((w, i) => {
            const still = w.actual && samePlaced(placed(dim.getBlock({ x: w.lane.x + 1, y: Y - 1, z: w.lane.z })), w.actual);
            w.reading = still && velocities[i] && heights[i] !== null ? { v: velocities[i], dy: round(moved[i] + 1e-9, 3) } : null;
        });
        bodies.forEach((body) => body && body.isValid && body.remove());
        for (const w of wave) for (let k = 0; k < LENGTH; k++) dim.setBlockType({ x: w.lane.x + k, y: Y - 1, z: w.lane.z }, "minecraft:air");
        await wait(1);
        wave.forEach((w) => entries.push({ requested: w.requested, actual: w.actual, reading: w.reading }));
    }
    printGrouped("friction_state", entries);
}

// ---------- the states BDS does not keep, tried with the blocks around them ----------

// How each of the 4 blocks might be kept: the block under the state, the blocks filling the cells around it (a sealed 3x3x3
// box), the block above it and the one beside it. These are recipes to try, nothing is taken from them: a state counts only
// if BDS kept it.
const AROUND = {
    "minecraft:soul_fire": [{ name: "on soul soil", below: "minecraft:soul_soil" }, { name: "on soul sand", below: "minecraft:soul_sand" }],
    "minecraft:bubble_column": [
        { name: "in water on soul sand", below: "minecraft:soul_sand", fill: "minecraft:water" },
        { name: "in water on magma block", below: "minecraft:magma_block", fill: "minecraft:water" },
    ],
    "minecraft:flowing_water": [{ name: "beside a source", side: "minecraft:water" }, { name: "under a source", above: "minecraft:water" }],
    "minecraft:flowing_lava": [{ name: "beside a source", side: "minecraft:lava" }, { name: "under a source", above: "minecraft:lava" }],
};

async function aroundRecipes(dim, states) {
    const X = 0, Z = 600, PITCH = 5, PER_ROW = 10, SPOTS = 100;
    await loadedArea(dim, { x: X - 3, y: Y - 2, z: Z - 3 }, { x: X + PER_ROW * PITCH, y: Y + 5, z: Z + (SPOTS / PER_ROW) * PITCH }, "probe6");
    const jobs = [];
    for (const requested of states) {
        for (const recipe of AROUND[requested.type.id] ?? []) jobs.push({ requested, recipe });
    }
    const results = new Map(); // key of the requested state -> {block, state, attempts}
    for (let start = 0; start < jobs.length; start += SPOTS) {
        const wave = jobs.slice(start, start + SPOTS);
        wave.forEach((job, i) => {
            const x = X + PITCH * (i % PER_ROW), z = Z + PITCH * Math.floor(i / PER_ROW);
            const { recipe } = job;
            job.centre = { x, y: Y + 1, z };
            dim.runCommand(`fill ${x - 2} ${Y - 1} ${z - 2} ${x + 2} ${Y + 3} ${z + 2} stone`);
            dim.runCommand(`fill ${x - 1} ${Y} ${z - 1} ${x + 1} ${Y + 2} ${z + 1} ${recipe.fill ?? "air"}`);
            if (recipe.below) dim.setBlockType({ x, y: Y, z }, recipe.below);
            if (recipe.above) dim.setBlockType({ x, y: Y + 2, z }, recipe.above);
            if (recipe.side) dim.setBlockType({ x: x + 1, y: Y + 1, z }, recipe.side);
            try {
                dim.getBlock(job.centre).setPermutation(job.requested);
            } catch {
                // not placeable
            }
        });
        await wait(4);
        for (const job of wave) {
            const asked = job.requested.getAllStates();
            const block = dim.getBlock(job.centre);
            const attempt = {
                recipe: job.recipe.name,
                materials: ["minecraft:stone", job.recipe.below, job.recipe.fill, job.recipe.above, job.recipe.side].filter((m) => m),
                kept: block.typeId === job.requested.type.id && stateKey(block.permutation.getAllStates()) === stateKey(asked),
                light: dim.getLightLevel(job.centre),
            };
            const key = job.requested.type.id + stateKey(asked);
            if (!results.has(key)) results.set(key, { block: job.requested.type.id, state: asked, attempts: [] });
            results.get(key).attempts.push(attempt);
        }
    }
    for (const result of results.values()) out("around", result);
}

world.afterEvents.worldLoad.subscribe(() => {
    main().catch((e) => console.warn(`PROBE-FAILED ${e} ${e?.stack}`));
});
