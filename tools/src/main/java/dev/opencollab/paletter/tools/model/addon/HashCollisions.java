package dev.opencollab.paletter.tools.model.addon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.block.BlockHashes;
import dev.opencollab.paletter.internal.block.BlockStates;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.List;

/**
 * The pair of custom blocks of the spec add-on whose network hashes collide, so the capture shows which of the two BDS gives
 * the hash and which the next id (docs/wire-notes.md, hash collisions). {@code hashCollision} in {@code data/model/fixtures.json}
 * holds the pair {@link #firstPair} finds; the generator writes them as plain blocks without properties, in the creative inventory,
 * where their stacks carry the ids.
 */
public final class HashCollisions {
    /** The names searched: this prefix and 0, 1, 2, ... */
    public static final String PREFIX = AddonGenerator.NAMESPACE + ":hash_";

    private HashCollisions() {
    }

    /**
     * The first two names {@code <prefix><n>} whose single state (no properties) has the same hash: the earlier name, then the
     * name at which the search stops.
     */
    public static List<String> firstPair(String prefix) {
        Int2ObjectMap<Integer> seen = new Int2ObjectOpenHashMap<>();
        for (int n = 0; ; n++) {
            Integer earlier = seen.putIfAbsent(BlockHashes.hash(BlockStates.of(prefix + n)), Integer.valueOf(n));
            if (earlier != null) {
                return List.of(prefix + earlier, prefix + n);
            }
        }
    }

    /** The block file of one of the pair: no properties, no components, in the creative inventory. */
    static JsonObject block(String identifier, String formatVersion) {
        return JsonParser.parseString("""
            {"format_version": "%s", "minecraft:block": {"description": {"identifier": "%s", "menu_category": {"category": "construction"}},
              "components": {}}}""".formatted(formatVersion, identifier)).getAsJsonObject();
    }
}
