package org.geysermc.paletter.examples;

import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.behaviour.BlockBehaviour;
import org.geysermc.paletter.behaviour.BlockShape;
import org.geysermc.paletter.behaviour.BlockValues;
import org.geysermc.paletter.behaviour.ItemBehaviour;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.ResolvedBlock;
import org.geysermc.paletter.block.component.Friction;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.geysermc.paletter.vanilla.BedrockItems;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    /** The palette of the player's client version: its behaviour handles are made once per state, lookups are array reads. */
    private final Palette palette;

    AnticheatExample(Palette palette) {
        this.palette = palette;
        this.air = palette.runtimeId(BedrockBlocks.AIR.defaultState());
    }

    int blockAt(int x, int y, int z) {
        return world.getOrDefault(List.of(x, y, z), air);
    }

    /** The values of the block at a position; an id this palette does not send (a corrupt chunk) counts as air. */
    BlockValues valuesAt(int x, int y, int z) {
        ResolvedBlock block = palette.resolveBlock(blockAt(x, y, z));
        return (block != null ? block : Objects.requireNonNull(palette.resolveBlock(air))).behaviour();
    }

    /** The friction of the block under the feet: a float, not boxed. */
    float frictionUnder(double x, double y, double z) {
        return valuesAt((int) Math.floor(x), (int) Math.floor(y - 0.5), (int) Math.floor(z)).get(BlockBehaviour.FRICTION);
    }

    /** The collision boxes of the blocks in a 3x3x3 cube around a position, in world coordinates. */
    List<float[]> collisionsAround(int px, int py, int pz) {
        List<float[]> boxes = new ArrayList<>();
        for (int x = px - 1; x <= px + 1; x++) {
            for (int y = py - 1; y <= py + 1; y++) {
                for (int z = pz - 1; z <= pz + 1; z++) {
                    BlockShape shape = valuesAt(x, y, z).get(BlockBehaviour.COLLISION_SHAPE);
                    for (BlockShape.Box box : shape.boxes()) {
                        boxes.add(new float[] {x + box.minX(), y + box.minY(), z + box.minZ(), x + box.maxX(), y + box.maxY(), z + box.maxZ()});
                    }
                }
            }
        }
        return boxes;
    }

    public static void main(String[] args) {
        // A server's custom block: its friction component is read with the same key
        CustomBlock slippery = CustomBlock.builder("example:slippery").component(Friction.of(0.02f)).build();
        Paletter paletter = Paletter.builder().layer(PaletteLayer.builder("server").block(slippery).build()).build();
        // The palette of the client's protocol version; here the newest bundled one
        Palette palette = paletter.palette(paletter.latest().protocolVersion());
        AnticheatExample check = new AnticheatExample(palette);

        check.world.put(List.of(0, 63, 0), palette.runtimeId(BedrockBlocks.BLUE_ICE.defaultState()));
        check.world.put(List.of(1, 63, 0), palette.runtimeId(slippery.type().defaultState()));
        check.world.put(List.of(0, 64, 1), palette.runtimeId(BedrockBlocks.OAK_STAIRS.defaultState()));

        System.out.println("friction on blue ice: " + check.frictionUnder(0.5, 64, 0.5));
        System.out.println("friction on the custom block: " + check.frictionUnder(1.5, 64, 0.5));
        for (float[] box : check.collisionsAround(0, 64, 0)) {
            System.out.println("collision box " + java.util.Arrays.toString(box));
        }

        // Where a value comes from: the source, its version, whether it is inherited, third party or disputed
        System.out.println("blue ice friction: " + palette.resolve(BedrockBlocks.BLUE_ICE.defaultState()).behaviour().explain(BlockBehaviour.FRICTION));

        // A custom block: set by the server, a BDS default (measured on BDS), or not known
        BlockValues custom = palette.resolve(slippery.type().defaultState()).behaviour();
        System.out.println("custom friction: " + custom.explain(BlockBehaviour.FRICTION));
        System.out.println("custom explosion resistance: " + custom.explain(BlockBehaviour.EXPLOSION_RESISTANCE));
        System.out.println("custom destroy time: " + custom.getOrElse(BlockBehaviour.DESTROY_TIME, -2f) + ", "
            + custom.explain(BlockBehaviour.DESTROY_TIME));

        // Items: how long eating takes, the stack size, the damage of a weapon
        System.out.println("apple use duration: " + palette.resolve(BedrockItems.APPLE.base()).behaviour().get(ItemBehaviour.USE_DURATION) + " ticks");
        System.out.println("ender pearl stack: " + palette.resolve(BedrockItems.ENDER_PEARL.base()).behaviour().get(ItemBehaviour.MAX_STACK_SIZE));
        System.out.println("diamond sword attack damage: "
            + palette.resolve(BedrockItems.DIAMOND_SWORD.base()).behaviour().explain(ItemBehaviour.ATTACK_DAMAGE));
    }
}
