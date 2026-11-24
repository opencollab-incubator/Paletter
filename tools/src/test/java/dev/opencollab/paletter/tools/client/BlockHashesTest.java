package dev.opencollab.paletter.tools.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.data.Nbt;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import dev.opencollab.paletter.tools.update.SpecCapture;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.Schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hashes BDS 1.26.63 sent in the creative inventory (block-network-ids-are-hashes=true). */
class BlockHashesTest {
    private static final Path SPEC = Path.of("../data/bds/1.26.63.1/spec");

    @Test
    void aBlockWithUnknownTraitStatesHasNoStatesNoDefaultHashAndNoDefault() {
        List<NbtMap> states = List.of(state("minecraft:dirt", Map.of()));
        JsonObject known = CaptureWriter.customState(states, null, null);
        assertEquals(1, known.get("states").getAsInt());
        assertTrue(known.has("defaultHash") && known.has("default"));
        assertFalse(known.has("traitStatesUnknown"));
        JsonObject unknown = CaptureWriter.customState(states, Set.of("minecraft:x y"), null);
        assertEquals(List.of("traitStatesUnknown"), List.copyOf(unknown.keySet()), "what is known to be wrong is omitted");
        assertEquals("minecraft:x y", unknown.getAsJsonArray("traitStatesUnknown").get(0).getAsString());
        JsonObject unsupported = CaptureWriter.customState(states, null, Set.of("minecraft:x z"));
        assertEquals(List.of("traitStatesUnsupported"), List.copyOf(unsupported.keySet()), "what is known to be wrong is omitted");
    }

    @Test
    void vanillaStates() {
        assertEquals(2186211206L, Integer.toUnsignedLong(BlockHashes.hash(state("minecraft:dirt", Map.of()))));
        assertEquals(4172483059L, Integer.toUnsignedLong(BlockHashes.hash(state("minecraft:oak_stairs", Map.of(
            "weirdo_direction", 0, "upside_down_bit", (byte) 0, "minecraft:corner", "none")))));
    }

    /**
     * Every custom block of the spec capture BDS put in the creative inventory, the trait variants (every combination of
     * {@code enabled_states}) among them: the first state the client derives is the default BDS sent the hash of.
     */
    @Test
    void customDefaultStatesAreTheOnesBdsSent() throws IOException {
        Map<String, Set<String>> unsupported = customDefaultStatesAreTheOnesBdsSent(SPEC, 2225);
        assertEquals(Map.of(), unsupported, "every enabled_states value is supported in 1.26.63.1");
    }

    /**
     * BDS 1.26.36.1 (protocol 1001) lists minecraft:sixteen_way_rotation with duplicate values: the trait state table marks it
     * unsupported there, so its blocks (the 8 placement_direction variants with it) are set apart, and every other block's
     * default is the one BDS sent. From 26.40 (1.26.45.1, protocol 2169) on the value adds its states.
     */
    @Test
    void sixteenWayRotationBlocksOf26_30AreSetApart() throws IOException {
        Path spec = SPEC.getParent().resolveSibling("1.26.36.1").resolve("spec");
        Map<String, Set<String>> unsupported = customDefaultStatesAreTheOnesBdsSent(spec, 1001);
        assertEquals(8, unsupported.size(), unsupported.toString());
        unsupported.forEach((block, values) -> {
            assertTrue(block.startsWith("paletter:b_trait_placement_direction_"), block);
            assertEquals(Set.of("minecraft:placement_direction minecraft:sixteen_way_rotation"), values, block);
        });
        assertTrue(dev.opencollab.paletter.internal.component.TraitStateTable.unsupported("minecraft:placement_direction",
            "minecraft:sixteen_way_rotation", 1001).startsWith("BDS 1.26.36.1 lists minecraft:sixteen_way_rotation as [down, up,"));
        assertEquals(null, dev.opencollab.paletter.internal.component.TraitStateTable.unsupported("minecraft:placement_direction",
            "minecraft:sixteen_way_rotation", 2169));
        // In 26.40 the same blocks have the states 0 to 15
        NbtMap properties = NbtMap.builder().putList("traits", NbtType.COMPOUND, NbtMap.builder().putString("name", "minecraft:placement_direction")
            .putCompound("enabled_states", NbtMap.builder().putByte("sixteen_way_rotation", (byte) 1).build()).build()).build();
        assertEquals(16, BlockHashes.customStates("test:block", properties, 2169).size());
        Set<String> unknown = new TreeSet<>();
        Set<String> unsupportedHere = new TreeSet<>();
        assertEquals(1, BlockHashes.customStates("test:block", properties, 1001, unknown, unsupportedHere).size());
        assertEquals(Set.of("minecraft:placement_direction minecraft:sixteen_way_rotation"), unsupportedHere);
    }

    /**
     * Every custom block of a spec capture BDS put in the creative inventory, the trait variants (every combination of
     * {@code enabled_states}) among them: the first state the client derives is the default BDS sent the hash of.
     *
     * @return the blocks set apart for a value unsupported in the protocol, to those values
     */
    private static Map<String, Set<String>> customDefaultStatesAreTheOnesBdsSent(Path spec, int protocol) throws IOException {
        Map<String, Long> sent = new HashMap<>();
        try (Reader reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(spec.resolve(SpecCapture.CREATIVE_ITEMS))), StandardCharsets.UTF_8)) {
            for (JsonElement e : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("items")) {
                JsonObject item = e.getAsJsonObject();
                if (item.has("block_runtime_id")) {
                    sent.put(item.get("id").getAsString(), item.get("block_runtime_id").getAsLong());
                }
            }
        }
        // The pair chosen to collide: one of them has the next id, which HashCollisionCaptureTest checks
        List<String> colliding = Fixtures.read(SPEC.getParent().getParent().resolveSibling("model")).hashCollision();
        int traitBlocks = 0;
        Map<String, Set<String>> setApart = new java.util.TreeMap<>();
        for (NbtMap block : Nbt.readGzip(spec.resolve("custom_block_properties.nbt")).getList("blocks", NbtType.COMPOUND)) {
            String name = block.getString("name");
            if (!sent.containsKey(name) || colliding.contains(name)) {
                continue;
            }
            Set<String> unknown = new TreeSet<>();
            Set<String> unsupported = new TreeSet<>();
            List<NbtMap> states = BlockHashes.customStates(name, block.getCompound("properties"), protocol, unknown, unsupported);
            assertEquals(Set.of(), unknown, name);
            if (!unsupported.isEmpty()) {
                setApart.put(name, unsupported);
                continue;
            }
            assertEquals(sent.get(name), Integer.toUnsignedLong(BlockHashes.hash(states.getFirst())), name);
            assertEquals(states.size(), states.stream().map(BlockHashes::hash).distinct().count(), name);
            traitBlocks += block.getCompound("properties").containsKey("traits") ? 1 : 0;
        }
        assertTrue(traitBlocks >= 7 + 3 + 1 + 1, "a block per combination of every trait's enabled_states: " + traitBlocks);
        return setApart;
    }

    /**
     * Every {@code enabled_states} value of BDS's committed schema, as BDS sends an accepted one (a flag per value): the client's
     * states come from the trait state table, and a value it has no states for yet ({@code minecraft:sixteen_way_rotation}, which
     * BDS 1.26.63.1 accepts) is reported instead of stopping the spec capture.
     */
    @Test
    void everyEnabledStateOfTheSchemaIsReadOrReported() {
        Schema schema = Schema.newest(SPEC.getParent().getParent(), null);
        for (String trait : List.of("minecraft:placement_direction", "minecraft:placement_position")) {
            List<String> values = schema.enumValues("block:trait:" + trait, "enabled_states");
            assertFalse(values.isEmpty(), trait);
            for (String value : values) {
                NbtMap properties = NbtMap.builder().putList("traits", NbtType.COMPOUND, NbtMap.builder().putString("name", trait)
                    .putCompound("enabled_states", NbtMap.builder().putByte(value.substring(value.indexOf(':') + 1), (byte) 1).build()).build())
                    .build();
                Set<String> unknown = new TreeSet<>();
                List<NbtMap> states = BlockHashes.customStates("test:block", properties, 2225, unknown, new TreeSet<>());
                if (unknown.isEmpty()) {
                    assertTrue(states.size() > 1, trait + " " + value + " adds no state");
                } else {
                    assertEquals(Set.of(trait + " " + value), unknown);
                    assertEquals(1, states.size());
                }
            }
        }
    }

    private static NbtMap state(String name, Map<String, Object> states) {
        return NbtMap.builder().putString("name", name).putCompound("states", NbtMap.fromMap(states)).build();
    }
}
