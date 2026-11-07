// Paletter dumper: prints Bedrock data that only the game itself knows to the server console.
// Script API code cannot write files, so the JSON is printed in numbered chunks that the generator reassembles:
//   PALETTER <index>/<total> <slice>
//   PALETTER-DONE <total> chunks
// Run BDS with its output redirected to a file, then: generator import ... --bds-dump <that file>
//
// Dump format (FORMAT 2):
// {
//   format: 2,
//   item_tags:      {tag: [item id]},
//   items:          {item id: {max_stack, localization_key, components: [id], max_durability?}},
//   block_defaults: {block id: {state: value}},              BlockPermutation.resolve(id), the true default
//   block_states:   {block id: {state: [valid value]}},      values each state accepts on that block
//   block_tags:     {tag: [block id]},                       tags of the default permutation
//   block_items:    {block id: item id},                     the item of the default permutation, if any
//   state_types:    {state: [valid value]}                   BlockStates.getAll(), all blocks together
//   errors:         [message]
// }

import { BlockPermutation, BlockStates, BlockTypes, ItemStack, ItemTypes, system, world } from "@minecraft/server";

const FORMAT = 2;
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

function* dump() {
    const out = { format: FORMAT, item_tags: {}, items: {}, block_defaults: {}, block_states: {}, block_tags: {}, block_items: {}, state_types: {} };
    for (const stateType of toArray(BlockStates.getAll())) {
        out.state_types[stateType.id] = toArray(stateType.validValues);
    }
    yield* dumpItems(out);
    yield* dumpBlocks(out);
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
world.afterEvents.worldLoad.subscribe(() => {
    console.warn("PALETTER-START");
    system.runJob((function* () {
        try {
            yield* dump();
        } catch (error) {
            // Tell the runner to stop instead of waiting for its timeout
            console.warn(`PALETTER-FAILED ${error} ${error?.stack}`);
        }
    })());
});
