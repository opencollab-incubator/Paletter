package org.geysermc.paletter.tools.data;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.ItemExplanation;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.item.ItemVariant;

import java.io.PrintStream;
import java.util.List;

/**
 * The {@code inspect} command: prints {@link Palette#explain(NbtMap)} / {@link Palette#explain(ItemVariant)} of a block
 * state or item variant for one or all bundled versions.
 */
public final class Inspect {
    private Inspect() {
    }

    /**
     * @param version a version name, or "all"
     * @param block {@code identifier} or {@code identifier[key=value,...]}; unnamed states take the default
     * @param item {@code identifier} or {@code identifier:damage}
     */
    public static void run(Paletter paletter, String version, String block, String item, PrintStream out) {
        if ((block == null) == (item == null)) {
            throw new IllegalArgumentException("Give exactly one of --block and --item");
        }
        List<DataVersion> versions = version.equals("all") ? paletter.versions() : List.of(find(paletter, version));
        for (DataVersion v : versions) {
            Palette palette = paletter.vanilla(v, BlockNetworkIds.HASHED);
            out.println("== " + v.name() + " (protocol " + v.protocolVersion() + ")");
            out.println(block != null ? palette.explain(parseBlock(paletter.vanilla(paletter.latest(), BlockNetworkIds.HASHED), block))
                : explain(palette, parseItem(item)));
        }
    }

    private static ItemExplanation explain(Palette palette, ItemVariant item) {
        return palette.explain(item.identifier(), item.damage());
    }

    private static DataVersion find(Paletter paletter, String name) {
        return paletter.versions().stream().filter(v -> v.name().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No bundled version " + name + ", have " + paletter.versions()));
    }

    /** @param newest the newest palette, whose default states give the type of each state value */
    public static NbtMap parseBlock(Palette newest, String spec) {
        int bracket = spec.indexOf('[');
        String name = (bracket == -1 ? spec : spec.substring(0, bracket)).trim();
        if (!name.contains(":")) {
            name = "minecraft:" + name;
        }
        RegisteredBlockState defaults = newest.blocks().defaultState(name);
        NbtMap states = defaults == null ? NbtMap.EMPTY : defaults.states();
        if (bracket != -1) {
            if (!spec.endsWith("]")) {
                throw new IllegalArgumentException("Expected ']' at the end of " + spec);
            }
            NbtMapBuilder builder = states.toBuilder();
            for (String pair : spec.substring(bracket + 1, spec.length() - 1).split(",")) {
                if (pair.isBlank()) {
                    continue;
                }
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) {
                    throw new IllegalArgumentException("Expected key=value, got " + pair);
                }
                String key = kv[0].trim();
                String value = kv[1].trim();
                Object old = states.get(key);
                if (old instanceof Byte) {
                    builder.putByte(key, (byte) (value.equals("true") || value.equals("1") ? 1 : 0));
                } else if (old instanceof Integer) {
                    builder.putInt(key, Integer.parseInt(value));
                } else {
                    builder.putString(key, value);
                }
            }
            states = builder.build();
        }
        return BlockStates.of(name, states);
    }

    public static ItemVariant parseItem(String spec) {
        int colon = spec.lastIndexOf(':');
        int damage = 0;
        String name = spec;
        if (colon != -1 && colon < spec.length() - 1 && spec.substring(colon + 1).chars().allMatch(Character::isDigit)) {
            damage = Integer.parseInt(spec.substring(colon + 1));
            name = spec.substring(0, colon);
        }
        return ItemVariant.of(name.contains(":") ? name : "minecraft:" + name, damage);
    }
}
