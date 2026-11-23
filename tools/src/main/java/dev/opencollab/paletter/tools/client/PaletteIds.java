package dev.opencollab.paletter.tools.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.tools.Json;
import dev.opencollab.paletter.tools.Layout;
import org.cloudburstmc.nbt.NbtMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;

/**
 * The runtime id BDS gives every block state with sequential block ids ({@code block-network-ids-are-hashes=false}), from the palette
 * probe ({@link Layout#PALETTE_PROBE_ADDON}): its script places every state of every block type into a cell of its own next to the
 * player and prints where; the capture client records the block update BDS sends for each cell. The pair is the state's id, the
 * whole palette observed (not only the states of creative stacks), so the order of the states within a block is checked against BDS.
 * <p>
 * {@code vanilla/sequential-ids.json.gz}: {@code {_generated, bdsVersion, protocol, experiments, states: [[id, name, {state:
 * value}]], notObserved: [[name, {state: value}, why]]}}, the states by id.
 */
public final class PaletteIds {
    public static final String FILE = "sequential-ids.json.gz";
    /** The prefix of the probe's console lines. */
    public static final String MARKER = "PALETTE";

    private PaletteIds() {
    }

    /**
     * One state as the probe placed it.
     *
     * @param states the state values as the Script API gives them: booleans, numbers, strings
     */
    public record State(String name, JsonObject states) {
        /** {@code name[a=1,b=true,c="x"]}, the states sorted: the same for the Script API's values and the palette's NBT. */
        public String key() {
            StringBuilder key = new StringBuilder(name).append('[');
            new TreeMap<>(states.asMap()).forEach((state, value) -> key.append(state).append('=').append(value).append(','));
            return key.append(']').toString();
        }

        /** The key of a palette state: a byte is a boolean there, as the Script API gives it. */
        public static String key(NbtMap state) {
            JsonObject values = new JsonObject();
            BlockStates.states(state).forEach((name, value) -> values.add(name, switch (value) {
                case Byte b -> new JsonPrimitive(b != 0);
                case Number n -> new JsonPrimitive(n.longValue());
                default -> new JsonPrimitive(value.toString());
            }));
            return new State(BlockStates.name(state), values).key();
        }
    }

    /** A state and the runtime id BDS sent for its cell. */
    public record Entry(int id, State state) {
    }

    /**
     * @param ids          the observed states by id
     * @param notObserved  the states the probe placed whose id is not known, each with why
     */
    public record Result(List<Entry> ids, Map<State, String> notObserved) {
    }

    /**
     * Pairs the probe's cells with the block updates of the client.
     *
     * @param lines   the probe's console lines ({@code PALETTE <index>/<total> <slice>})
     * @param updates the runtime id of the last block update of each position ({@link CaptureClient.Result#blockUpdates})
     */
    public static Result pair(List<String> lines, Map<String, Integer> updates) throws IOException {
        JsonObject probe = JsonParser.parseString(join(lines)).getAsJsonObject();
        JsonArray cells = probe.getAsJsonArray("cells");
        java.util.Set<Integer> moved = new java.util.HashSet<>();
        probe.getAsJsonArray("moved").forEach(index -> moved.add(index.getAsInt()));
        List<Entry> ids = new ArrayList<>();
        Map<State, String> notObserved = new java.util.LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            JsonArray cell = cells.get(i).getAsJsonArray();
            State state = new State(cell.get(3).getAsString(), cell.get(4).getAsJsonObject());
            Integer id = updates.get(cell.get(0).getAsInt() + " " + cell.get(1).getAsInt() + " " + cell.get(2).getAsInt());
            if (moved.contains(i)) {
                notObserved.put(state, "the cell did not hold the state when read back (it fell, flowed or broke)");
            } else if (id == null) {
                notObserved.put(state, "no block update for its cell");
            } else {
                ids.add(new Entry(id, state));
            }
        }
        ids.sort(java.util.Comparator.comparingInt(Entry::id));
        return new Result(ids, notObserved);
    }

    /** The JSON of the numbered console chunks, in order; fails on a missing chunk. */
    static String join(List<String> lines) throws IOException {
        Map<Integer, String> chunks = new TreeMap<>();
        int total = -1;
        for (String line : lines) {
            int at = line.indexOf(MARKER + " ");
            if (at < 0) {
                continue;
            }
            String rest = line.substring(at + MARKER.length() + 1);
            int slash = rest.indexOf('/');
            int space = rest.indexOf(' ');
            if (slash < 0 || space < slash) {
                continue;
            }
            int index = Integer.parseInt(rest.substring(0, slash));
            total = Integer.parseInt(rest.substring(slash + 1, space));
            chunks.put(index, rest.substring(space + 1));
        }
        if (total < 0 || chunks.size() != total) {
            throw new IOException("the palette probe printed " + chunks.size() + " of " + (total < 0 ? "?" : total) + " chunks");
        }
        return String.join("", chunks.values());
    }

    /** The file's bytes (gzip). */
    public static byte[] render(String bdsVersion, int protocol, List<String> experiments, Result result) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("_generated", "Generated by `update` (tools, PaletteIds) from the palette probe (" + Layout.PALETTE_PROBE_ADDON
            + ") on BDS " + bdsVersion + " with sequential block ids: the runtime id of the block update of each state's cell. Do not edit by hand");
        json.addProperty("bdsVersion", bdsVersion);
        json.addProperty("protocol", protocol);
        JsonArray toggles = new JsonArray();
        new TreeSet<>(experiments).forEach(toggles::add);
        json.add("experiments", toggles);
        JsonArray states = new JsonArray();
        for (Entry entry : result.ids()) {
            JsonArray row = new JsonArray();
            row.add(entry.id());
            row.add(entry.state().name());
            row.add(entry.state().states());
            states.add(row);
        }
        json.add("states", states);
        JsonArray notObserved = new JsonArray();
        result.notObserved().forEach((state, why) -> {
            JsonArray row = new JsonArray();
            row.add(state.name());
            row.add(state.states());
            row.add(why);
            notObserved.add(row);
        });
        json.add("notObserved", notObserved);
        // One state per line: a diff of two captures shows the states that moved
        String text = Json.COMPACT.toJson(json).replace("[[", "[\n[").replace("],[", "],\n[");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(bytes)) {
            out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    /** The observed states of a file, by id. */
    public static List<Entry> read(Path file) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (JsonElement element : Json.object(file).getAsJsonArray("states")) {
            JsonArray row = element.getAsJsonArray();
            entries.add(new Entry(row.get(0).getAsInt(), new State(row.get(1).getAsString(), row.get(2).getAsJsonObject())));
        }
        return entries;
    }

    /** The experiments of the probe's world. */
    public static List<String> experiments(Path file) throws IOException {
        return Json.strings(Json.object(file), "experiments");
    }

    /**
     * The observed ids that are not the index of their state in {@code palette} (the states in sequential order: by the name hash of
     * the block, then in the block's own order), one line each; empty if every one is.
     */
    public static List<String> mismatches(List<NbtMap> palette, List<Entry> observed) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < palette.size(); i++) {
            index.put(State.key(palette.get(i)), i);
        }
        List<String> wrong = new ArrayList<>();
        for (Entry entry : observed) {
            Integer expected = index.get(entry.state().key());
            if (expected == null || expected != entry.id()) {
                wrong.add(entry.state().key() + ": BDS " + entry.id() + ", palette " + (expected == null ? "has no such state" : expected)
                    + (entry.id() < palette.size() ? " (BDS's id is " + State.key(palette.get(entry.id())) + " there)" : ""));
            }
        }
        return wrong;
    }

    /** Writes the file. */
    public static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }
}
