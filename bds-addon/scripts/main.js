// Paletter dumper: prints Bedrock data that only the game itself knows to the server console.
// Script API code cannot write files, so the JSON is printed in numbered chunks that the generator reassembles:
//   PALETTER <index>/<total> <slice>
// Run BDS with its output redirected to a file, then: generator import ... --bds-dump <that file>

import { BlockPermutation, BlockTypes, ItemStack, ItemTypes, system, world } from "@minecraft/server";

const FORMAT = 1;
const CHUNK_SIZE = 2000;

function itemTags() {
    const tags = {};
    for (const type of ItemTypes.getAll()) {
        let stack;
        try {
            stack = new ItemStack(type, 1);
        } catch {
            // Some types (e.g. air) cannot be instantiated as stacks
            continue;
        }
        for (const tag of stack.getTags()) {
            (tags[tag] ??= []).push(type.id);
        }
    }
    return tags;
}

function blockDefaults() {
    const defaults = {};
    for (const type of BlockTypes.getAll()) {
        try {
            defaults[type.id] = BlockPermutation.resolve(type.id).getAllStates();
        } catch (error) {
            console.warn(`PALETTER-ERROR could not resolve ${type.id}: ${error}`);
        }
    }
    return defaults;
}

function dump() {
    const json = JSON.stringify({
        format: FORMAT,
        item_tags: itemTags(),
        block_defaults: blockDefaults(),
    });
    const total = Math.ceil(json.length / CHUNK_SIZE);
    for (let i = 0; i < total; i++) {
        console.warn(`PALETTER ${i}/${total} ${json.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE)}`);
    }
    console.warn(`PALETTER-DONE ${total} chunks`);
}

// Item stacks cannot be created during early execution; wait for the world
if (world.afterEvents.worldLoad) {
    world.afterEvents.worldLoad.subscribe(() => dump());
} else {
    system.runTimeout(() => dump(), 20);
}
