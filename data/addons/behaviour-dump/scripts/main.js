// Paletter behaviour dump, hand-written. Run on a Bedrock Dedicated Server by `behaviour-dump` (tools), on a world with the
// Beta APIs experiment (isSolid and getMapColor are beta). It prints what the Script API says about every block state and
// item type to the console in numbered chunks, as the Script API dump does:
//   BEHAVIOUR <index>/<total> <slice>
//   BEHAVIOUR-PROGRESS <phase> <done>/<total>   while it works (the tools wait as long as these come)
//   BEHAVIOUR-DONE <total> chunks          (BEHAVIOUR-FAILED <error> if the script failed)
// The script draws no conclusions beyond comparing values; BehaviourDump (tools) reads the JSON.
//
// Every block state is asked twice:
//   permutation  without a world: BlockPermutation's liquid queries, for every liquid type
//   placed       placed at one fixed position (overworld, flat world biome, stone below, air on the other five sides):
//                every property and getter of Block and of every component Block.getComponents() returns
// Every item type: ItemStack(type, 1), every property and getter of the stack and of its components.
//
// What is read is decided by the MEMBERS table below, per class: accessor properties are read unless the table skips
// them, functions only as the table says. Every member of every prototype met is listed in `api`; a member the table does
// not know is listed in `unread`, which fails behaviour-dump: new API surface is never silently ignored.
//
// World dependence: a sample of states (the default state of every block type and every 37th state) is placed again in
// two other settings and every placed getter is read again:
//   nether  the nether (another biome and dimension, no sky light), glowstone on all six sides
//   water   the overworld at another position, stone below, water on the other five sides
// A getter whose value differs from the first setting for any sampled state is world-dependent: it is not a property of
// the state, and `world.dependent` lists it with the first difference seen.
//
// Output (FORMAT 3):
// {
//   format: 3,
//   states:      [[block id, {state: value}], ...]                  every block state, Script API state names
//   permutation: {property: [[distinct values], [index per state]]}
//   placed:      {property: [[distinct values], [index per state]]}  null where the state lacks the component
//   changedOnPlacement: [[state index, block id, {state: value}]]   states BDS turned into another state when placed
//                                                                   (nothing placed is recorded for them): setting a
//                                                                   state recomputes what follows from the neighbours
//                                                                   (stair corners, connections, redstone signal, fire
//                                                                   age); placing from a structure does the same
//   items:       {item id: {property: value}}
//   api:         {class: [member]}                                  prototype members of every class met
//   unread:      ["Class.member"]
//   world:       {settings: {name: description}, sample: n, sampleBlocks: n, checked: [property],
//                 changedOnPlacement: {setting: [[state, placed as]]}   not compared: another block, not another value
//                 dependent: {property: {states: n, blocks: [block id], first: {setting, state, value, other}}}}
//   errors:      [message]
// }
// Property names: Class.member, plus the arguments for queries that take them, e.g. Block.isSolid,
// BlockPermutation.canContainLiquid(Water), Block.liquidCanFlowFromDirection(Water,Up), BlockMapColorComponent.color.

import { BlockPermutation, BlockStates, BlockTypes, Direction, ItemStack, ItemTypes, LiquidType, system, world } from "@minecraft/server";

const FORMAT = 3;
const CHUNK_SIZE = 2000;
const LIQUIDS = Object.values(LiquidType);
const DIRECTIONS = Object.values(Direction);
const SAMPLE_EVERY = 37;

// How each member is read. read: call with no arguments; liquid: call per liquid type; liquidDirection: per liquid type and
// direction; direction: per direction; skip: not read, with the reason. Accessor properties not named here are read.
const COMMON = {
    constructor: "skip: the class itself",
    isValid: "skip: whether the handle is valid, not data",
    typeId: "skip: identity",
};
const MEMBERS = {
    BlockPermutation: {
        canBeDestroyedByLiquidSpread: "liquid", canContainLiquid: "liquid", isLiquidBlocking: "liquid", liquidSpreadCausesSpawn: "liquid",
        localizationKey: "skip: a name, not behaviour",
        type: "skip: identity",
        getAllStates: "skip: the state itself (states)",
        getState: "skip: the state itself (states)",
        withState: "skip: makes another state",
        matches: "skip: a comparison",
        getItemStack: "skip: the item of the state (dump.json.gz block_items)",
        getTags: "skip: tags (dump.json.gz block_tags)",
        hasTag: "skip: tags (dump.json.gz block_tags)",
    },
    Block: {
        getMapColor: "read", getLightLevel: "read", getSkyLightLevel: "read", getRedstonePower: "read",
        liquidCanFlowFromDirection: "liquidDirection",
        canBeDestroyedByLiquidSpread: "skip: asked on BlockPermutation, without a world",
        canContainLiquid: "skip: asked on BlockPermutation, without a world",
        isLiquidBlocking: "skip: asked on BlockPermutation, without a world",
        liquidSpreadCausesSpawn: "skip: asked on BlockPermutation, without a world",
        dimension: "skip: position", location: "skip: position", x: "skip: position", y: "skip: position", z: "skip: position",
        above: "skip: position", below: "skip: position", north: "skip: position", south: "skip: position", east: "skip: position",
        west: "skip: position", offset: "skip: position", center: "skip: position", bottomCenter: "skip: position",
        permutation: "skip: the state itself", type: "skip: identity", localizationKey: "skip: a name, not behaviour",
        getComponent: "skip: getComponents is read", getComponents: "skip: each component is read", hasComponent: "skip: getComponents is read",
        getItemStack: "skip: the item of the state (dump.json.gz block_items)",
        getTags: "skip: tags (dump.json.gz block_tags)", hasTag: "skip: tags (dump.json.gz block_tags)",
        getParts: "skip: other blocks of a multi-block structure (world)",
        canPlace: "skip: needs a block to place", matches: "skip: a comparison",
        hasScheduledNamedTick: "skip: needs an event name", scheduleNamedTick: "skip: changes the world",
        removeScheduledNamedTick: "skip: changes the world", setPermutation: "skip: changes the world", setType: "skip: changes the world",
        setWaterlogged: "skip: changes the world", trySetPermutation: "skip: changes the world",
    },
    BlockComponent: { block: "skip: the block itself" },
    BlockPrecipitationInteractionsComponent: { accumulatesSnow: "read", isSnowLoggable: "read", obstructsRain: "read" },
    BlockVibrationPropertiesComponent: { getCanDampenVibrations: "read", getCanOccludeVibrations: "read" },
    BlockInstrumentComponent: { getInstrumentName: "direction", playInstrumentSound: "skip: plays a sound" },
    BlockRedstoneProducerComponent: { getConnectedFaces: "read", getStronglyPoweredFace: "read" },
    BlockFluidContainerComponent: {
        getFluidType: "read", addDye: "skip: changes the world", setFluidType: "skip: changes the world", setPotion: "skip: changes the world",
        fillLevel: "read", fluidColor: "read",
    },
    BlockPistonComponent: { getAttachedBlocks: "skip: other blocks (world)", getAttachedBlocksLocations: "skip: other blocks (world)" },
    BlockInventoryComponent: { container: "skip: the block entity's contents" },
    BlockSignComponent: {
        getRawText: "skip: the block entity's text", getText: "skip: the block entity's text", getTextDyeColor: "skip: the block entity's text",
        setText: "skip: changes the world", setTextDyeColor: "skip: changes the world", setWaxed: "skip: changes the world",
    },
    BlockRecordPlayerComponent: {
        ejectRecord: "skip: changes the world", getRecord: "skip: the block entity's contents", isPlaying: "skip: the block entity's state",
        pauseRecord: "skip: changes the world", playRecord: "skip: changes the world", setRecord: "skip: changes the world",
    },
    BlockDynamicPropertiesComponent: {
        get: "skip: needs a name", set: "skip: changes the world", totalByteCount: "skip: user data",
        clearDynamicProperties: "skip: changes the world", getDynamicProperty: "skip: needs a name", getDynamicPropertyIds: "skip: user data",
        getDynamicPropertyTotalByteCount: "skip: user data", setDynamicProperties: "skip: changes the world", setDynamicProperty: "skip: changes the world",
    },
    BlockEntityStorageComponent: {
        getEntities: "skip: the block entity's contents", release: "skip: changes the world", size: "skip: the block entity's contents",
        store: "skip: changes the world", transfer: "skip: changes the world",
    },
    BlockRecipeCraftingComponent: { getCraftingContext: "skip: the block entity's state" },
    BlockRecipeProcessingComponent: {
        getInputItem: "skip: the block entity's contents", getInputSlotEnabled: "skip: needs a slot", getOutputItem: "skip: the block entity's contents",
        setInputItem: "skip: changes the world", setInputSlotEnabled: "skip: changes the world",
    },
    ItemStack: {
        amount: "skip: the stack's amount", keepOnDeath: "skip: the stack's state", lockMode: "skip: the stack's state",
        nameTag: "skip: the stack's state", type: "skip: identity", localizationKey: "skip: a name, not behaviour",
        clearDynamicProperties: "skip: changes the stack", clone: "skip: makes another stack", getCanDestroy: "skip: the stack's state",
        getCanPlaceOn: "skip: the stack's state", getComponent: "skip: getComponents is read", getComponents: "skip: each component is read",
        getDynamicProperty: "skip: needs a name", getDynamicPropertyIds: "skip: user data", getDynamicPropertyTotalByteCount: "skip: user data",
        getLore: "skip: the stack's state", getRawLore: "skip: the stack's state", getTags: "skip: tags (dump.json.gz item_tags)",
        hasComponent: "skip: getComponents is read", hasTag: "skip: tags (dump.json.gz item_tags)", isStackableWith: "skip: a comparison",
        matches: "skip: a comparison", setCanDestroy: "skip: changes the stack", setCanPlaceOn: "skip: changes the stack",
        setDynamicProperties: "skip: changes the stack", setDynamicProperty: "skip: changes the stack", setLore: "skip: changes the stack",
    },
    ItemCooldownComponent: {
        getCooldownTicksRemaining: "skip: needs a player", isCooldownCategory: "skip: a comparison", startCooldown: "skip: needs a player",
    },
    ItemDurabilityComponent: { getDamageChance: "read", getDamageChanceRange: "read", damage: "skip: the stack's damage" },
    ItemEnchantableComponent: {
        addEnchantment: "skip: changes the stack", addEnchantments: "skip: changes the stack", canAddEnchantment: "skip: needs an enchantment",
        getEnchantment: "skip: needs an enchantment", getEnchantments: "skip: the stack's enchantments", hasEnchantment: "skip: needs an enchantment",
        removeAllEnchantments: "skip: changes the stack", removeEnchantment: "skip: changes the stack",
    },
    ItemInventoryComponent: { container: "skip: the stack's contents" },
    ItemBookComponent: {
        author: "skip: the stack's contents", contents: "skip: the stack's contents", isSigned: "skip: the stack's contents",
        pageCount: "skip: the stack's contents", rawContents: "skip: the stack's contents", title: "skip: the stack's contents",
        getPageContent: "skip: the stack's contents", getRawPageContent: "skip: the stack's contents", insertPage: "skip: changes the stack",
        removePage: "skip: changes the stack", setContents: "skip: changes the stack", setPageContent: "skip: changes the stack",
        signBook: "skip: changes the stack",
    },
    ItemBlockDynamicPropertiesComponent: {
        get: "skip: needs a name", set: "skip: changes the stack", totalByteCount: "skip: user data",
        clearDynamicProperties: "skip: changes the stack", getDynamicProperty: "skip: needs a name", getDynamicPropertyIds: "skip: user data",
        getDynamicPropertyTotalByteCount: "skip: user data", setDynamicProperties: "skip: changes the stack", setDynamicProperty: "skip: changes the stack",
    },
    ItemDyeableComponent: { color: "skip: the stack's colour" },
};

const errors = [];
const api = {};
const unread = new Set();

function warn(message) {
    errors.push(message);
    console.warn(`BEHAVIOUR-ERROR ${message}`);
}

// Tells behaviour-dump (tools) the dump is alive: it waits as long as these keep coming, however long the work is, and the
// log shows where a dump that never finished stopped. Printed every `every` steps, with what the step works on if given.
function progress(phase, done, total, every, what) {
    if (done % every === 0) {
        console.warn(`BEHAVIOUR-PROGRESS ${phase} ${done}/${total}${what === undefined ? "" : ` ${what}`}`);
    }
}

// Native values to JSON: objects with an identity by their id, plain structs (RGBA, NumberRange, Vector3) by their fields
function plain(value) {
    if (value === undefined) {
        return null;
    }
    if (value === null || typeof value !== "object") {
        return typeof value === "function" ? null : value;
    }
    if (Array.isArray(value)) {
        return value.map(plain);
    }
    if (typeof value.typeId === "string") {
        return value.typeId;
    }
    if (typeof value.id === "string") {
        return value.id;
    }
    const result = {};
    for (const key of Object.keys(value).sort()) {
        result[key] = plain(value[key]);
    }
    return result;
}

// Native arrays of the Script API are not always iterable; copy them by index
function toArray(list) {
    if (list === undefined || list === null || typeof list.length !== "number") {
        return [];
    }
    const array = [];
    for (let i = 0; i < list.length; i++) {
        array.push(list[i]);
    }
    return array;
}

// The members of an object's classes, from its class up to (not including) Object, each with the class that declares it
function members(object) {
    const result = [];
    for (let proto = Object.getPrototypeOf(object); proto && proto !== Object.prototype; proto = Object.getPrototypeOf(proto)) {
        const type = proto.constructor?.name ?? "?";
        if (!api[type]) {
            api[type] = Object.getOwnPropertyNames(proto).sort();
        }
        for (const name of Object.getOwnPropertyNames(proto)) {
            result.push({ type, name, accessor: typeof Object.getOwnPropertyDescriptor(proto, name).get === "function" });
        }
    }
    return result;
}

function how(type, name) {
    // own entries only: every object literal has a constructor
    const table = Object.hasOwn(MEMBERS, type) ? MEMBERS[type] : {};
    return Object.hasOwn(table, name) ? table[name] : Object.hasOwn(COMMON, name) ? COMMON[name] : undefined;
}

// Reads every member of an object as the table says, into out[prefix + member (+ arguments)]. `className` names the
// properties (the most derived class), the declaring class decides how a member is read.
function readAll(object, out) {
    const own = Object.getPrototypeOf(object).constructor?.name ?? object.typeId ?? "?";
    for (const { type, name, accessor } of members(object)) {
        const rule = how(type, name) ?? how(own, name) ?? (accessor ? "read" : undefined);
        if (rule === undefined) {
            unread.add(`${type}.${name}`);
            continue;
        }
        if (rule.startsWith("skip")) {
            continue;
        }
        const property = `${own}.${name}`;
        const ask = (suffix, ...args) => {
            try {
                out[property + suffix] = plain(accessor ? object[name] : object[name](...args));
            } catch (error) {
                out[property + suffix] = `error: ${error}`;
            }
        };
        if (rule === "read") {
            ask("");
        } else if (rule === "liquid") {
            for (const liquid of LIQUIDS) ask(`(${liquid})`, liquid);
        } else if (rule === "direction") {
            for (const direction of DIRECTIONS) ask(`(${direction})`, direction);
        } else if (rule === "liquidDirection") {
            for (const liquid of LIQUIDS) for (const direction of DIRECTIONS) ask(`(${liquid},${direction})`, liquid, direction);
        } else {
            warn(`unknown rule ${rule} of ${type}.${name}`);
        }
    }
}

function readPlaced(block) {
    const out = {};
    readAll(block, out);
    for (const component of toArray(block.getComponents())) {
        readAll(component, out);
    }
    return out;
}

// ---- Block states ----

function sameState(block, permutation) {
    const a = block.permutation.getAllStates();
    const b = permutation.getAllStates();
    return block.typeId === permutation.type.id && Object.keys(b).every((name) => a[name] === b[name]);
}

function validValues(name) {
    return toArray(BlockStates.get(name)?.validValues);
}

// Every state of a block: every combination of the values its states accept, as BDS resolves them (legacy alias states,
// e.g. direction next to minecraft:cardinal_direction, make some combinations the same state)
function statesOf(id) {
    const base = BlockPermutation.resolve(id);
    const defaults = base.getAllStates();
    const options = Object.keys(defaults).sort().map((name) => [name, validValues(name).filter((value) => {
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
                const key = JSON.stringify(Object.keys(all).sort().map((name) => [name, all[name]]));
                if (!result.has(key)) {
                    result.set(key, permutation);
                }
            } catch {
                // not a state of the block
            }
            return;
        }
        const [name, values] = options[i];
        for (const value of values.length ? values : [defaults[name]]) {
            combine(i + 1, { ...states, [name]: value });
        }
    };
    combine(0, {});
    return [...result.keys()].sort().map((key) => result.get(key));
}

// Dictionary-encoded columns: {property: [[distinct values], [index per state]]}
class Columns {
    constructor() {
        this.columns = {};
    }

    set(property, index, value) {
        const column = (this.columns[property] ??= { values: [], lookup: new Map(), index: [] });
        const key = JSON.stringify(value);
        let at = column.lookup.get(key);
        if (at === undefined) {
            at = column.values.length;
            column.values.push(value);
            column.lookup.set(key, at);
        }
        column.index[index] = at;
    }

    json(count) {
        const result = {};
        for (const property of Object.keys(this.columns).sort()) {
            const column = this.columns[property];
            // states without the property: null
            let none = column.lookup.get("null");
            const index = [];
            for (let i = 0; i < count; i++) {
                if (column.index[i] === undefined) {
                    if (none === undefined) {
                        none = column.values.length;
                        column.values.push(null);
                        column.lookup.set("null", none);
                    }
                    index.push(none);
                } else {
                    index.push(column.index[i]);
                }
            }
            result[property] = [column.values, index];
        }
        return result;
    }
}

// A setting: where a state is placed and what is around it. Each placement takes the next of a grid of cells (3 apart, so
// the surroundings of two cells never touch): placing many states with block entities at one position within a tick crashes
// BDS (chiseled bookshelves, 1.26.63.1).
function setting(dimension, corner, size, around) {
    const cells = [];
    for (let i = 0; i < size; i++) for (let j = 0; j < size; j++) cells.push({ x: corner.x + 3 * i, y: corner.y, z: corner.z + 3 * j });
    let next = 0;
    return {
        corner,
        extent: 3 * size,
        place(permutation) {
            const at = cells[next];
            next = (next + 1) % cells.length;
            for (const [dx, dy, dz, block] of around) {
                dimension.setBlockType({ x: at.x + dx, y: at.y + dy, z: at.z + dz }, block);
            }
            // air first: the state never meets the block entity of an earlier state
            dimension.setBlockType(at, "minecraft:air");
            const block = dimension.getBlock(at);
            block.setPermutation(permutation);
            return block;
        },
    };
}

const SIDES = [[1, 0, 0], [-1, 0, 0], [0, 0, 1], [0, 0, -1], [0, 1, 0]];

async function loaded(dimension, area, name) {
    const { x, y, z } = area.corner;
    const far = { x: x + area.extent, y, z: z + area.extent };
    dimension.runCommand(`tickingarea add ${x - 2} ${y - 2} ${z - 2} ${far.x + 2} ${y + 2} ${far.z + 2} ${name}`);
    for (let i = 0; i < 200; i++) {
        progress(`loading ${name}`, i, 200, 20);
        try {
            if (dimension.getBlock(area.corner) && dimension.getBlock(far)) {
                return;
            }
        } catch {
            // not loaded yet
        }
        await new Promise((resolve) => system.runTimeout(resolve, 10));
    }
    throw new Error(`${name} did not load`);
}

function* dumpBlocks(out, cells) {
    const states = [];
    // Sorted, so two runs of the same build print the same dump
    const types = toArray(BlockTypes.getAll()).sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    for (const [n, type] of types.entries()) {
        progress("block types", n, types.length, 100);
        try {
            for (const permutation of statesOf(type.id)) {
                states.push(permutation);
            }
        } catch (error) {
            warn(`no states for block ${type.id}: ${error}`);
        }
        yield;
    }
    out.changedOnPlacement = [];
    const permutationColumns = new Columns();
    const placedColumns = new Columns();
    out.states = states.map((permutation) => [permutation.type.id, permutation.getAllStates()]);
    for (let i = 0; i < states.length; i++) {
        progress("block states", i, states.length, 500);
        const without = {};
        readAll(states[i], without);
        for (const [property, value] of Object.entries(without)) permutationColumns.set(property, i, value);
        try {
            const block = cells.base.place(states[i]);
            if (sameState(block, states[i])) {
                const placed = readPlaced(block);
                for (const [property, value] of Object.entries(placed)) placedColumns.set(property, i, value);
            } else {
                // BDS turned it into another block on placement: nothing placed is known about the state
                out.changedOnPlacement.push([i, block.typeId, block.permutation.getAllStates()]);
            }
        } catch (error) {
            warn(`could not place ${JSON.stringify(out.states[i])}: ${error}`);
        }
        yield;
    }
    out.permutation = permutationColumns.json(states.length);
    out.placed = placedColumns.json(states.length);

    // World dependence: the sample in the other settings
    const firstOfType = new Set();
    const sample = [];
    states.forEach((permutation, i) => {
        if (!firstOfType.has(permutation.type.id) || i % SAMPLE_EVERY === 0) {
            firstOfType.add(permutation.type.id);
            sample.push(i);
        }
    });
    const checked = new Set();
    const dependent = {};
    const changed = {};
    for (const [n, i] of sample.entries()) {
        // Every sample, with its state: BDS 1.26.70.24 (preview) went silent within world samples 1800 to 1849 (from
        // minecraft:spruce_pressure_plate to minecraft:stripped_jungle_log), a hang the base placement of the same
        // states did not have, so the line names the state a hang is in
        progress("world sample", n, sample.length, 1, JSON.stringify(out.states[i]));
        let reference;
        try {
            const block = cells.base.place(states[i]);
            if (!sameState(block, states[i])) continue;
            reference = readPlaced(block);
        } catch {
            continue;
        }
        for (const [setting, other] of Object.entries(cells.others)) {
            let values;
            try {
                const block = other.place(states[i]);
                if (!sameState(block, states[i])) {
                    // e.g. concrete powder next to water: another block, not another value of this one
                    (changed[setting] ??= []).push([out.states[i], [block.typeId, block.permutation.getAllStates()]]);
                    continue;
                }
                values = readPlaced(block);
            } catch (error) {
                warn(`could not place ${JSON.stringify(out.states[i])} in ${setting}: ${error}`);
                continue;
            }
            for (const property of new Set([...Object.keys(reference), ...Object.keys(values)])) {
                checked.add(property);
                const a = reference[property] ?? null;
                const b = values[property] ?? null;
                if (JSON.stringify(a) !== JSON.stringify(b)) {
                    const entry = (dependent[property] ??= { states: 0, blocks: [], first: { setting, state: out.states[i], value: a, other: b } });
                    entry.states++;
                    if (!entry.blocks.includes(out.states[i][0])) entry.blocks.push(out.states[i][0]);
                }
            }
        }
        yield;
    }
    out.world = {
        settings: {
            base: "overworld, flat world biome, stone below, air on the other five sides (every state)",
            nether: "nether, glowstone on all six sides",
            water: "overworld at another position, stone below, water on the other five sides",
        },
        sample: sample.length,
        changedOnPlacement: changed,
        sampleBlocks: firstOfType.size,
        checked: [...checked].sort(),
        dependent,
    };
}

function* dumpItems(out) {
    const types = toArray(ItemTypes.getAll()).sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    for (const [n, type] of types.entries()) {
        progress("items", n, types.length, 100);
        let stack;
        try {
            stack = new ItemStack(type, 1);
        } catch (error) {
            // Some types (air, some technical items) cannot be instantiated as stacks
            warn(`no stack for item ${type.id}: ${error}`);
            yield;
            continue;
        }
        const values = {};
        readAll(stack, values);
        for (const component of toArray(stack.getComponents())) {
            readAll(component, values);
        }
        // getComponents listed ItemFoodComponent for the apple only in the dumps of 1.26.36.1 to 1.26.63.1 (1 of 1623 items).
        // Asked by name too: whether getComponent finds the food of the other items is not known, the next capture shows it.
        // The importer reads ItemFoodComponent.* the same way whichever call found it.
        try {
            const food = stack.getComponent("minecraft:food");
            if (food && values["ItemFoodComponent.nutrition"] === undefined) {
                readAll(food, values);
            }
        } catch (error) {
            warn(`no food component of item ${type.id}: ${error}`);
        }
        out.items[type.id] = values;
        yield;
    }
}

function* dump(cells) {
    const out = { format: FORMAT, items: {} };
    yield* dumpItems(out);
    yield* dumpBlocks(out, cells);
    out.api = api;
    out.unread = [...unread].sort();
    out.errors = errors;

    const json = JSON.stringify(out);
    const total = Math.ceil(json.length / CHUNK_SIZE);
    for (let i = 0; i < total; i++) {
        console.warn(`BEHAVIOUR ${i}/${total} ${json.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE)}`);
        if (i % 50 === 49) {
            progress("printing", i + 1, total, 50);
            yield;
        }
    }
    console.warn(`BEHAVIOUR-DONE ${total} chunks`);
}

async function setup() {
    const overworld = world.getDimension("overworld");
    const nether = world.getDimension("nether");
    for (const command of ["gamerule domobspawning false", "gamerule dodaylightcycle false", "time set noon", "gamerule dofiretick false",
        "gamerule randomtickspeed 0"]) {
        overworld.runCommand(command);
    }
    const cells = {
        base: setting(overworld, { x: 2, y: 200, z: 2 }, 16, [[0, -1, 0, "minecraft:stone"], ...SIDES.map((d) => [...d, "minecraft:air"])]),
        others: {
            nether: setting(nether, { x: 2, y: 64, z: 2 }, 8, [[0, -1, 0, "minecraft:glowstone"], ...SIDES.map((d) => [...d, "minecraft:glowstone"])]),
            water: setting(overworld, { x: 64, y: 200, z: 2 }, 8, [[0, -1, 0, "minecraft:stone"], ...SIDES.map((d) => [...d, "minecraft:water"])]),
        },
    };
    await loaded(overworld, cells.base, "behaviour_base");
    await loaded(overworld, cells.others.water, "behaviour_water");
    await loaded(nether, cells.others.nether, "behaviour_nether");
    return cells;
}

// Item stacks cannot be created during early execution; wait for the world. The dump runs as a job so it never trips the
// script watchdog.
world.afterEvents.worldLoad.subscribe(() => {
    console.warn("BEHAVIOUR-START");
    // A script the watchdog stops would otherwise end silently (the event is not on every build: feature detection)
    system.beforeEvents?.watchdogTerminate?.subscribe((event) => console.warn(`BEHAVIOUR-FAILED watchdog: ${event.terminateReason}`));
    setup().then((cells) => {
        system.runJob((function* () {
            try {
                yield* dump(cells);
            } catch (error) {
                console.warn(`BEHAVIOUR-FAILED ${error} ${error?.stack}`);
            }
        })());
    }).catch((error) => console.warn(`BEHAVIOUR-FAILED ${error} ${error?.stack}`));
});
