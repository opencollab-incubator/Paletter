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
        JsonObject known = CaptureWriter.customState(states, null);
        assertEquals(1, known.get("states").getAsInt());
        assertTrue(known.has("defaultHash") && known.has("default"));
        assertFalse(known.has("traitStatesUnknown"));
        JsonObject unknown = CaptureWriter.customState(states, Set.of("minecraft:x y"));
        assertEquals(List.of("traitStatesUnknown"), List.copyOf(unknown.keySet()), "what is known to be wrong is omitted");
        assertEquals("minecraft:x y", unknown.getAsJsonArray("traitStatesUnknown").get(0).getAsString());
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
        Map<String, Long> sent = new HashMap<>();
        try (Reader reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(SPEC.resolve(SpecCapture.CREATIVE_ITEMS))), StandardCharsets.UTF_8)) {
            for (JsonElement e : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("items")) {
                JsonObject item = e.getAsJsonObject();
                if (item.has("block_runtime_id")) {
                    sent.put(item.get("id").getAsString(), item.get("block_runtime_id").getAsLong());
                }
            }
        }
        int traitBlocks = 0;
        for (NbtMap block : Nbt.readGzip(SPEC.resolve("custom_block_properties.nbt")).getList("blocks", NbtType.COMPOUND)) {
            String name = block.getString("name");
            if (!sent.containsKey(name)) {
                continue;
            }
            List<NbtMap> states = BlockHashes.customStates(name, block.getCompound("properties"));
            assertEquals(sent.get(name), Integer.toUnsignedLong(BlockHashes.hash(states.getFirst())), name);
            assertEquals(states.size(), states.stream().map(BlockHashes::hash).distinct().count(), name);
            traitBlocks += block.getCompound("properties").containsKey("traits") ? 1 : 0;
        }
        assertTrue(traitBlocks >= 7 + 3 + 1 + 1, "a block per combination of every trait's enabled_states: " + traitBlocks);
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
                List<NbtMap> states = BlockHashes.customStates("test:block", properties, unknown);
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
