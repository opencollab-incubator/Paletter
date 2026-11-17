// Paletter dumper: prints Bedrock data that only the game itself knows to the server console.
// Script API code cannot write files, so the JSON is printed in numbered chunks that the generator reassembles:
//   PALETTER <index>/<total> <slice>
//   PALETTER-DONE <total> chunks
// Run BDS with its output redirected to a file, then: generator import ... --bds-dump <that file>
//
// Dump format (FORMAT 4):
// {
//   format: 4,
//   item_tags:      {tag: [item id]},
//   items:          {item id: {max_stack, localization_key, components: [id], max_durability?}},
//   block_defaults: {block id: {state: value}},              BlockPermutation.resolve(id), the true default
//   block_states:   {block id: {state: [valid value]}},      values each state accepts on that block
//   block_tags:     {tag: [block id]},                       tags of the default permutation
//   block_items:    {block id: item id},                     the item of the default permutation, if any
//   state_types:    {state: [valid value]}                   BlockStates.getAll(), all blocks together. The values are in the
//                   order BDS lists them, unsorted (format 3 and before sorted them): the order of the network palette
//   item_variants:  {item id: {aux: {key, potion?}}}  the aux values BDS accepts of VARIANT_ITEMS
//                   key: localization key of the stack; potion: the effect id of the Script API potion component (potions), or
//                   the effect whose set_potion loot table gives that aux (arrows, if exactly one does).
//                   Missing if this part failed (see errors): the rest of the dump is complete without it
//   errors:         [message]
// }

import { BlockPermutation, BlockStates, BlockTypes, ItemStack, ItemTypes, Potions, system, world } from "@minecraft/server";

const FORMAT = 4;
const CHUNK_SIZE = 2000;

const errors = [];

function warn(message) {
    errors.push(message);
    console.warn(`PALETTER-ERROR ${message}`);
}

function* dumpItems(out) {
    for (const type of ItemTypes.getAll()) {
        let stack;
        try {
            stack = new ItemStack(type, 1);
        } catch (error) {
            // Some types (air, some technical items) cannot be instantiated as stacks
            warn(`no stack for item ${type.id}: ${error}`);
            yield;
            continue;
        }
        for (const tag of stack.getTags()) {
            (out.item_tags[tag] ??= []).push(type.id);
        }
        const item = {
            max_stack: stack.maxAmount,
            localization_key: stack.localizationKey,
            components: stack.getComponents().map((component) => component.typeId).sort(),
        };
        const durability = stack.getComponent("minecraft:durability");
        if (durability) {
            item.max_durability = durability.maxDurability;
        }
        out.items[type.id] = item;
        yield;
    }
}

// Native arrays of the Script API are not always iterable; copy them by index
function toArray(list) {
    if (list === undefined || list === null || typeof list.length !== "number") {
        return null;
    }
    const array = [];
    for (let i = 0; i < list.length; i++) {
        array.push(list[i]);
    }
    return array;
}

const unknownStates = new Set();

function validValues(name, fallback) {
    const values = toArray(BlockStates.get(name)?.validValues);
    if (values) {
        return values;
    }
    if (!unknownStates.has(name)) {
        unknownStates.add(name);
        warn(`no valid values for block state ${name}: ${values}`);
    }
    return [fallback];
}

function* dumpBlocks(out) {
    for (const type of BlockTypes.getAll()) {
        let permutation;
        try {
            permutation = BlockPermutation.resolve(type.id);
        } catch (error) {
            warn(`could not resolve block ${type.id}: ${error}`);
            yield;
            continue;
        }
        const defaults = permutation.getAllStates();
        out.block_defaults[type.id] = defaults;

        // Valid values per block: vary one state at a time from the default
        const states = {};
        for (const name of Object.keys(defaults)) {
            const values = [];
            for (const value of validValues(name, defaults[name])) {
                try {
                    // withState may also answer with another block (legacy aliases such as flower_type) or clamp
                    const changed = permutation.withState(name, value);
                    if (changed.type.id === type.id && changed.getState(name) === value) {
                        values.push(value);
                    }
                } catch {
                    // not valid for this block
                }
            }
            states[name] = values;
        }
        out.block_states[type.id] = states;

        for (const tag of permutation.getTags()) {
            (out.block_tags[tag] ??= []).push(type.id);
        }
        try {
            const stack = permutation.getItemStack(1);
            if (stack) {
                out.block_items[type.id] = stack.typeId;
            }
        } catch {
            // blocks without an item (e.g. technical blocks)
        }
        yield;
    }
}

// The items whose aux value names a variant of them (potion effect, colour). Everything else with an aux is not named.
const VARIANT_ITEMS = ["minecraft:potion", "minecraft:splash_potion", "minecraft:lingering_potion", "minecraft:arrow", "minecraft:bed", "minecraft:banner"];
const MAX_AUX = 64;
const CHEST = { x: 0, y: -60, z: 0 };

// The chest of the item variants needs a loaded chunk: a ticking area, which loads over some ticks
async function loadChestArea() {
    const dimension = world.getDimension("overworld");
    try {
        dimension.runCommand(`tickingarea add ${CHEST.x} ${CHEST.y} ${CHEST.z} ${CHEST.x + 1} ${CHEST.y} ${CHEST.z + 1} paletter`);
    } catch {
        // a rerun on the same world has the area already
    }
    for (let i = 0; i < 300; i++) {
        try {
            if (dimension.getBlock(CHEST)) {
                return;
            }
        } catch {
            // not loaded yet
        }
        await new Promise((resolve) => system.runTimeout(resolve, 10));
    }
    throw new Error("the ticking area for the item variants never loaded");
}

// replaceitem is the only way to set an aux value from scripts; BDS rejects the aux values the item does not have.
// The stack goes through a chest in the ticking area, which is where it can be read back.
function* dumpItemVariants(out) {
    const dimension = world.getDimension("overworld");
    dimension.setBlockType(CHEST, "minecraft:chest");
    const container = dimension.getBlock(CHEST).getComponent("minecraft:inventory").container;
    const at = `${CHEST.x} ${CHEST.y} ${CHEST.z}`;

    function stackOf(slot, id, aux) {
        container.setItem(slot, undefined);
        dimension.runCommand(`replaceitem block ${at} slot.container ${slot} ${id} 1 ${aux}`);
        const stack = container.getItem(slot);
        return stack?.typeId === id ? stack : undefined;
    }

    const itemVariants = {};
    for (const id of VARIANT_ITEMS) {
        const variants = (itemVariants[id] = {});
        for (let aux = 0; aux < MAX_AUX; aux++) {
            const stack = stackOf(0, id, aux);
            if (!stack) {
                continue;
            }
            variants[aux] = { key: stack.localizationKey };
            const potion = stack.getComponent("minecraft:potion");
            if (potion) {
                variants[aux].potion = potion.potionEffectType.id;
            }
        }
        if (variants[MAX_AUX - 1]) {
            throw new Error(`${id} accepts aux ${MAX_AUX - 1}: raise MAX_AUX`);
        }
        yield;
    }

    // Tipped arrows have no component: the tool wrote a loot table per potion effect (set_potion), and the aux of an
    // arrow is the one whose stack the looted arrow stacks with
    const effectsOfAux = {};
    for (const effect of toArray(Potions.getAllEffectTypes())) {
        container.setItem(0, undefined);
        dimension.runCommand(`loot insert ${at} loot "paletter/arrow_${effect.id.split(":")[1]}"`);
        const looted = container.getItem(0);
        if (!looted) {
            warn(`no arrow for the potion effect ${effect.id}`);
            continue;
        }
        for (const aux of Object.keys(itemVariants["minecraft:arrow"])) {
            const arrow = stackOf(1, "minecraft:arrow", aux);
            if (looted.isStackableWith(arrow)) {
                (effectsOfAux[aux] ??= []).push(effect.id);
            }
        }
        yield;
    }
    // Several effects give the plain arrow (aux 0), which has no potion; every tipped arrow has exactly one effect.
    // Without the tool's loot tables none has, and the variants are then incomplete as a whole
    for (const aux of Object.keys(itemVariants["minecraft:arrow"])) {
        const effects = effectsOfAux[aux] ?? [];
        if (aux !== "0" && effects.length !== 1) {
            throw new Error(`the tipped arrow aux ${aux} matches ${effects.length} potion effects, not one`);
        }
        if (effects.length === 1) {
            itemVariants["minecraft:arrow"][aux].potion = effects[0];
        }
    }
    out.item_variants = itemVariants;
}

let chestReady = false;

function* dump() {
    const out = { format: FORMAT, item_tags: {}, items: {}, block_defaults: {}, block_states: {}, block_tags: {}, block_items: {}, state_types: {} };
    for (const stateType of toArray(BlockStates.getAll())) {
        out.state_types[stateType.id] = toArray(stateType.validValues);
    }
    yield* dumpItems(out);
    yield* dumpBlocks(out);
    // The variants need a chest in a ticking area and loot tables of the tool; the rest of the dump stands without them
    if (chestReady) {
        try {
            yield* dumpItemVariants(out);
        } catch (error) {
            warn(`item variants: ${error}`);
        }
    }
    out.errors = errors;

    const json = JSON.stringify(out);
    const total = Math.ceil(json.length / CHUNK_SIZE);
    for (let i = 0; i < total; i++) {
        console.warn(`PALETTER ${i}/${total} ${json.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE)}`);
        if (i % 50 === 49) {
            yield;
        }
    }
    console.warn(`PALETTER-DONE ${total} chunks`);
}

// Item stacks cannot be created during early execution; wait for the world. The dump runs as a job so it never
// trips the script watchdog.
world.afterEvents.worldLoad.subscribe(async () => {
    console.warn("PALETTER-START");
    try {
        await loadChestArea();
        chestReady = true;
    } catch (error) {
        warn(`item variants: ${error}`);
    }
    system.runJob((function* () {
        try {
            yield* dump();
        } catch (error) {
            // Tell the runner to stop instead of waiting for its timeout
            console.warn(`PALETTER-FAILED ${error} ${error?.stack}`);
        }
    })());
});
