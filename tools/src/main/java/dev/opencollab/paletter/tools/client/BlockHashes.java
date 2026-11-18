package dev.opencollab.paletter.tools.client;

import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.internal.component.TraitStateTable;
import dev.opencollab.paletter.tools.data.Nbt;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Block network ids as BDS sends them with {@code block-network-ids-are-hashes=true}: FNV-1a 32 of the little endian
 * NBT of {@code {name, states}} (no version tag; state keys sorted, root name empty).
 */
public final class BlockHashes {
    private BlockHashes() {
    }

    static int hash(NbtMap state) {
        NbtMapBuilder states = NbtMap.builder();
        new TreeMap<>(state.getCompound("states", NbtMap.EMPTY)).forEach(states::put);
        NbtMap canonical = NbtMap.builder()
            .putString("name", state.getString("name"))
            .putCompound("states", states.build())
            .build();
        byte[] bytes;
        try {
            bytes = Nbt.writeLe(canonical);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int hash = 0x811c9dc5;
        for (byte b : bytes) {
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
        Set<String> unknown = new TreeSet<>();
        List<NbtMap> states = customStates(name, blockProperties, unknown);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(name + ": the trait state table has no states for " + unknown);
        }
        return states;
    }

    /**
     * {@link #customStates(String, NbtMap)}, leaving out the states of an {@code enabled_states} value the trait state table does
     * not have yet (BDS accepts more values than the docs name a state for, such as {@code minecraft:sixteen_way_rotation}; the
     * mapping inference adds them from BDS's data): the value goes into {@code unknown} as {@code <trait> <value>}.
     */
    static List<NbtMap> customStates(String name, NbtMap blockProperties, Set<String> unknown) {
        List<NbtMap> properties = new ArrayList<>(blockProperties.getList("properties", NbtType.COMPOUND, List.of()));
        properties.addAll(traitProperties(blockProperties, unknown));
        List<NbtMap> states = new ArrayList<>();
        expand(name, properties, 0, new java.util.LinkedHashMap<>(), states);
        return states;
    }

    /**
     * The wire sends traits, not the states they add; the client adds those. The states of each {@code enabled_states}
     * value come from the generated {@link TraitStateTable} (the table Paletter's palettes use, checked against the hashes
     * BDS sent); only reading the value from the wire is done here: a flag per value
     * ({@code enabled_states.cardinal_direction: 1b}), the {@code multi_block} flag, or the bit set of
     * {@code minecraft:connection}.
     */
    static List<NbtMap> traitProperties(NbtMap blockProperties, Set<String> unknown) {
        List<NbtMap> out = new ArrayList<>();
        for (NbtMap trait : blockProperties.getList("traits", NbtType.COMPOUND, List.of())) {
            String name = trait.getString("name");
            List<String> values = new ArrayList<>();
            if (trait.get("enabled_states") instanceof NbtMap flags) {
                flags.forEach((flag, on) -> {
                    if (((Number) on).intValue() != 0) {
                        values.add(name.equals("minecraft:multi_block") ? "minecraft:multi_block_part" : "minecraft:" + flag);
                    }
                });
            } else if (trait.get("enabled_states") instanceof Number bits && (bits.intValue() & 1) != 0) { // minecraft:connection
                values.add("minecraft:cardinal_connections");
            }
            for (String value : values) {
                List<BlockProperty<?>> added;
                try {
                    added = TraitStateTable.of(name, value);
                } catch (IllegalArgumentException e) { // the table's answer for a value it has no states for
                    unknown.add(name + " " + value);
                    continue;
                }
                for (BlockProperty<?> state : added) {
                    if (out.stream().anyMatch(p -> p.getString("name").equals(state.name()))) {
                        continue; // added by another value too (cardinal_direction and corner_and_cardinal_direction)
                    }
                    List<?> stateValues = state.values();
                    if (trait.containsKey("part_count")) { // one minecraft:multi_block_part value per part
                        stateValues = stateValues.subList(0, ((Number) trait.get("part_count")).intValue());
                    }
                    out.add(property(state.name(), stateValues));
                }
            }
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NbtMap property(String name, List<?> values) {
        NbtType type = values.getFirst() instanceof Byte ? NbtType.BYTE : values.getFirst() instanceof Integer ? NbtType.INT : NbtType.STRING;
        return NbtMap.builder().putString("name", name).putList("enum", type, (List) values).build();
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
