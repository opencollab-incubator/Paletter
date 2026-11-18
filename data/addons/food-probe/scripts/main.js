// Food probe, hand-written. Run on a Bedrock Dedicated Server by `food-probe` (tools), on a world with the Beta APIs experiment
// (the gametest module needs it). A simulated player (a SimulatedPlayer of a game test, on a stone platform) tries to eat every item
// type twice and the add-on prints what it saw:
//
//   PROBE food <json>      one item, see below
//   PROBE-DONE             all done            (PROBE-FAILED <error> if the script failed)
//
// The tools parse the lines (org.geysermc.paletter.tools.behaviour.ProbeLog); this script draws no conclusions.
//
//   food {item, hungry, full}
//
// Both trials give the player the item in slot 0 and let it use it (useItemInSlot), then wait for the item to be used up (up
// to 100 ticks): `hungry` with a food level of 10 and a saturation of 0, `full` with a food level of 20. A trial is
//   {started: whether ItemStartUse fired, useDuration: what it reported (null if it did not fire), completed: whether
//    ItemCompleteUse fired, hunger: [food level before, after], saturation: [saturation before, after]}
// The two custom items probe:control_food and probe:control_snack have known components; the tools check their values.
import { world, system, ItemStack, ItemTypes, GameMode } from "@minecraft/server";
import * as GameTest from "@minecraft/server-gametest";

const out = (tag, data) => console.warn(`PROBE ${tag} ${JSON.stringify(data)}`);
const wait = (ticks) => new Promise((resolve) => system.runTimeout(resolve, ticks));

const HUNGRY = 10;
const FULL = 20;
const SETTLE = 3;
const LIMIT = 100;

// The events of the use in progress
let current = null;
world.afterEvents.itemStartUse.subscribe((event) => {
    if (current && event.source.id === current.player.id) {
        current.started = true;
        current.useDuration = event.useDuration;
    }
});
world.afterEvents.itemCompleteUse.subscribe((event) => {
    if (current && event.source.id === current.player.id) {
        current.completed = true;
    }
});

async function trial(player, type, foodLevel) {
    const hunger = player.getComponent("minecraft:player.hunger");
    const saturation = player.getComponent("minecraft:player.saturation");
    const container = player.getComponent("inventory").container;
    container.clearAll();
    container.setItem(0, new ItemStack(type, 1));
    hunger.setCurrentValue(foodLevel);
    saturation.setCurrentValue(0);
    const before = [hunger.currentValue, saturation.currentValue];
    const use = { player, started: false, useDuration: null, completed: false };
    current = use;
    player.useItemInSlot(0);
    await wait(SETTLE);
    if (use.started) {
        for (let tick = 0; tick < LIMIT && !use.completed; tick++) await wait(1);
        try {
            player.stopUsingItem();
        } catch {
            // not using anything any more
        }
    }
    current = null;
    const after = [hunger.currentValue, saturation.currentValue];
    container.clearAll();
    return { started: use.started, useDuration: use.useDuration, completed: use.completed, hunger: [before[0], after[0]], saturation: [before[1], after[1]] };
}

async function probe(test, player) {
    const types = ItemTypes.getAll().map((type) => type.id).sort();
    for (const id of types) {
        try {
            const type = ItemTypes.get(id);
            new ItemStack(type, 1); // some types (air) cannot be stacks
            const hungry = await trial(player, type, HUNGRY);
            const full = await trial(player, type, FULL);
            out("food", { item: id, hungry, full });
        } catch {
            // not an item a player can hold: nothing to say about it
        }
        player.teleport(test.worldLocation({ x: 1.5, y: 1, z: 1.5 }));
    }
}

GameTest.registerAsync("paletter", "food", async (test) => {
    const player = test.spawnSimulatedPlayer({ x: 1, y: 1, z: 1 }, "paletter_food", GameMode.Survival);
    try {
        await probe(test, player);
        console.warn("PROBE-DONE");
        test.succeed();
    } catch (e) {
        console.warn(`PROBE-FAILED ${e} ${e?.stack}`);
        test.fail(String(e));
    }
}).maxTicks(100000).structureName("paletter:platform");

world.afterEvents.worldLoad.subscribe(() => {
    world.getDimension("overworld").runCommand("gametest run paletter:food");
});
