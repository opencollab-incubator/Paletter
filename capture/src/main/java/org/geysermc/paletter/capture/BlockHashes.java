package org.geysermc.paletter.capture;

import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Block network ids as BDS sends them with {@code block-network-ids-are-hashes=true}: FNV-1a 32 of the little endian
 * NBT of {@code {name, states}} (no version tag; state keys sorted, root name empty).
 */
final class BlockHashes {
    private BlockHashes() {
    }

    static int hash(NbtMap state) {
        NbtMapBuilder states = NbtMap.builder();
        new TreeMap<>(state.getCompound("states", NbtMap.EMPTY)).forEach(states::put);
        NbtMap canonical = NbtMap.builder()
            .putString("name", state.getString("name"))
            .putCompound("states", states.build())
            .build();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (NBTOutputStream output = NbtUtils.createWriterLE(bytes)) {
            output.writeTag(canonical);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int hash = 0x811c9dc5;
        for (byte b : bytes.toByteArray()) {
            hash ^= b & 0xff;
            hash *= 0x01000193;
        }
        return hash;
    }

    /**
     * Every state of a custom block, from the {@code properties} list of its block property data, in the order the
     * client enumerates them (first property varies slowest).
     */
    static List<NbtMap> customStates(String name, NbtMap blockProperties) {
        List<NbtMap> properties = new ArrayList<>(blockProperties.getList("properties", NbtType.COMPOUND, List.of()));
        properties.addAll(traitProperties(blockProperties));
        List<NbtMap> states = new ArrayList<>();
        expand(name, properties, 0, new java.util.LinkedHashMap<>(), states);
        return states;
    }

    /**
     * The wire sends traits, not the states they add; the client adds those. Verified against BDS hashes for the
     * default state of each (first value listed here); the other values follow the vanilla blocks with the same state.
     */
    static List<NbtMap> traitProperties(NbtMap blockProperties) {
        List<NbtMap> out = new ArrayList<>();
        for (NbtMap trait : blockProperties.getList("traits", NbtType.COMPOUND, List.of())) {
            // enabled_states is a compound of flags, except for minecraft:connection, where it is an int bit set
            NbtMap enabled = trait.get("enabled_states") instanceof NbtMap map ? map : NbtMap.EMPTY;
            switch (trait.getString("name")) {
                case "minecraft:placement_direction" -> {
                    if (enabled.getBoolean("cardinal_direction")) {
                        out.add(property("minecraft:cardinal_direction", "south", "west", "north", "east"));
                    }
                    if (enabled.getBoolean("facing_direction")) {
                        out.add(property("minecraft:facing_direction", "down", "up", "south", "west", "north", "east"));
                    }
                    if (enabled.getBoolean("corner_and_cardinal_direction")) {
                        out.add(property("minecraft:corner_and_cardinal_direction", "south", "west", "north", "east",
                            "south_west", "north_west", "north_east", "south_east"));
                    }
                }
                case "minecraft:placement_position" -> {
                    if (enabled.getBoolean("block_face")) {
                        out.add(property("minecraft:block_face", "down", "up", "north", "south", "west", "east"));
                    }
                    if (enabled.getBoolean("vertical_half")) {
                        out.add(property("minecraft:vertical_half", "bottom", "top"));
                    }
                }
                case "minecraft:multi_block" -> {
                    if (enabled.getBoolean("multi_block")) {
                        int parts = ((Number) trait.get("part_count")).intValue();
                        List<Integer> values = new ArrayList<>();
                        for (int i = 0; i < parts; i++) {
                            values.add(i);
                        }
                        out.add(NbtMap.builder().putString("name", "minecraft:multi_block_part")
                            .putList("enum", NbtType.INT, values).build());
                    }
                }
                case "minecraft:connection" -> {
                    if ((((Number) trait.get("enabled_states")).intValue() & 1) != 0) { // cardinal_connections
                        for (String direction : List.of("north", "east", "south", "west")) {
                            out.add(NbtMap.builder().putString("name", "minecraft:connection_" + direction)
                                .putList("enum", NbtType.BYTE, (byte) 0, (byte) 1).build());
                        }
                    }
                }
                default -> {
                }
            }
        }
        return out;
    }

    private static NbtMap property(String name, String... values) {
        return NbtMap.builder().putString("name", name).putList("enum", NbtType.STRING, values).build();
    }

    private static void expand(String name, List<NbtMap> properties, int index, Map<String, Object> current, List<NbtMap> out) {
        if (index == properties.size()) {
            NbtMapBuilder states = NbtMap.builder();
            current.forEach(states::put);
            out.add(NbtMap.builder().putString("name", name).putCompound("states", states.build()).build());
            return;
        }
        NbtMap property = properties.get(index);
        String key = property.getString("name");
        Object values = property.get("enum");
        if (values instanceof NbtList<?> list) {
            for (Object value : list) {
                current.put(key, value);
                expand(name, properties, index + 1, current, out);
            }
        }
        current.remove(key);
    }
}
