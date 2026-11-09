package org.geysermc.paletter.examples;

import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.behaviour.Behaviour;
import org.geysermc.paletter.behaviour.BlockBehaviour;
import org.geysermc.paletter.behaviour.BlockShape;
import org.geysermc.paletter.behaviour.BlockTable;
import org.geysermc.paletter.behaviour.ItemBehaviour;
import org.geysermc.paletter.behaviour.Provenance;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.block.component.Friction;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.geysermc.paletter.vanilla.BedrockItems;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a movement anticheat (Boar) needs from the server's blocks and items, read from Paletter instead of hand tables:
 * friction under the player's feet, collision boxes around a position, climbing, how long an item is used. The world
 * is a map of runtime ids, as a Bedrock server or proxy keeps it. Run:
 * {@code ./gradlew :examples:run -PmainClass=org.geysermc.paletter.examples.AnticheatExample}.
 */
public final class AnticheatExample {
    /** A tiny world: position -> runtime id (state hash), what chunk packets carry. */
    private final Map<List<Integer>, Integer> world = new HashMap<>();
    private final int air;
    /** Built once per palette (client version) and key; lookups are array loads. */
    private final BlockTable<Float> friction;
    private final BlockTable<BlockShape> collision;
    private final BlockTable<Boolean> climbable;

    AnticheatExample(Palette palette) {
        Behaviour behaviour = palette.behaviour();
        this.friction = behaviour.blocks(BlockBehaviour.FRICTION);
        this.collision = behaviour.blocks(BlockBehaviour.COLLISION_SHAPE);
        this.climbable = behaviour.blocks(BlockBehaviour.CLIMBABLE);
        this.air = palette.runtimeId(BedrockBlocks.AIR.defaultState());
    }

    int blockAt(int x, int y, int z) {
        return world.getOrDefault(List.of(x, y, z), air);
    }

    /** The friction of the block under the feet (blocks nothing says anything about count as the usual 0.6). */
    float frictionUnder(double x, double y, double z) {
        Float value = friction.byRuntimeId(blockAt((int) Math.floor(x), (int) Math.floor(y - 0.5), (int) Math.floor(z)));
        return value == null ? 0.6f : value;
    }

    /** The collision boxes of the blocks in a 3x3x3 cube around a position, in world coordinates. */
    List<float[]> collisionsAround(int px, int py, int pz) {
        List<float[]> boxes = new ArrayList<>();
        for (int x = px - 1; x <= px + 1; x++) {
            for (int y = py - 1; y <= py + 1; y++) {
                for (int z = pz - 1; z <= pz + 1; z++) {
                    BlockShape shape = collision.byRuntimeId(blockAt(x, y, z));
                    if (shape == null) {
                        continue;
                    }
                    for (BlockShape.Box box : shape.boxes()) {
                        boxes.add(new float[] {x + box.minX(), y + box.minY(), z + box.minZ(), x + box.maxX(), y + box.maxY(), z + box.maxZ()});
                    }
                }
            }
        }
        return boxes;
    }

    /** Null while no source has the key (it needs the native capture): the caller keeps its own rule until then. */
    Boolean climbable(int x, int y, int z) {
        return climbable.byRuntimeId(blockAt(x, y, z));
    }

    public static void main(String[] args) {
        // A server's custom block: its friction component is read with the same key
        CustomBlock slippery = CustomBlock.builder("example:slippery").component(Friction.of(0.02f)).build();
        Palette palette = Paletter.builder().layer(PaletteLayer.builder("server").block(slippery).build()).build()
            .palette(Bedrock_v2225.CODEC);
        AnticheatExample check = new AnticheatExample(palette);

        check.world.put(List.of(0, 63, 0), palette.runtimeId(BedrockBlocks.BLUE_ICE.defaultState()));
        check.world.put(List.of(1, 63, 0), palette.blocks().block("example:slippery").defaultState().getRuntimeId());
        check.world.put(List.of(0, 64, 1), palette.runtimeId(BedrockBlocks.OAK_STAIRS.defaultState()));
        check.world.put(List.of(1, 64, 1), palette.runtimeId(BedrockBlocks.LADDER.defaultState()));

        System.out.println("friction on blue ice: " + check.frictionUnder(0.5, 64, 0.5));
        System.out.println("friction on the custom block: " + check.frictionUnder(1.5, 64, 0.5));
        for (float[] box : check.collisionsAround(0, 64, 0)) {
            System.out.println("collision box " + java.util.Arrays.toString(box));
        }
        System.out.println("ladder climbable: " + check.climbable(1, 64, 1));

        // Where a value comes from, and whether this version inherits it from an older capture
        Behaviour behaviour = palette.behaviour();
        RegisteredBlockState ice = palette.block(BedrockBlocks.BLUE_ICE.defaultState());
        Provenance provenance = behaviour.provenance(ice, BlockBehaviour.FRICTION);
        System.out.println("blue ice friction from " + provenance.source() + " " + provenance.reference()
            + (provenance.inherited() ? " (inherited from " + provenance.captured() + ")" : ""));
        System.out.println("  third-party dump: " + provenance.thirdParty() + ", disputed: " + provenance.disputed());

        // A custom block: was the value set by the server, or is it a BDS default? (Not known yet: no value then)
        RegisteredBlockState custom = palette.blocks().block("example:slippery").defaultState();
        System.out.println("custom friction set by the user: " + behaviour.isUserSet(custom, BlockBehaviour.FRICTION)
            + ", conversion unverified: " + behaviour.provenance(custom, BlockBehaviour.FRICTION).unverified());
        System.out.println("custom destroy time: " + behaviour.get(custom, BlockBehaviour.DESTROY_TIME) + " ("
            + behaviour.provenance(custom, BlockBehaviour.DESTROY_TIME).source() + ")");

        // Items: how long eating takes, the stack size, the damage of a weapon
        RegisteredItem apple = palette.item(BedrockItems.APPLE);
        System.out.println("apple use duration: " + behaviour.get(apple, ItemBehaviour.USE_DURATION) + " ticks, from "
            + behaviour.provenance(apple, ItemBehaviour.USE_DURATION).source());
        System.out.println("ender pearl stack: " + behaviour.get(BedrockItems.ENDER_PEARL, ItemBehaviour.MAX_STACK_SIZE));
        System.out.println("diamond sword attack damage: " + behaviour.get(BedrockItems.DIAMOND_SWORD, ItemBehaviour.ATTACK_DAMAGE));
    }
}
