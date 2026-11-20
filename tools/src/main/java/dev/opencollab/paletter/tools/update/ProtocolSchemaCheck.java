package dev.opencollab.paletter.tools.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Whether the protocol codec that decoded a build's captures (the newest the protocol library has) can be trusted for the build's
 * protocol. The evidence is BDS's own documentation generator: its {@code json_schemas/protocol} directory has one schema file per
 * packet and per type a packet uses, each stamped with the build and protocol it came from. The check compares, for every packet the
 * captures decode, the schema files the packet reaches (its payload and the types it refers to) between the docgen tree of the
 * codec's protocol and the tree of the build's protocol; the stamps are ignored, everything else counts (field names, types,
 * ordinals, enums). The raw trees stay in the cache ({@code docgen/<build>}); a protocol without a cached tree cannot be checked.
 */
final class ProtocolSchemaCheck {
    /**
     * The packets the captures decode, by the name BDS's schema files give them. Names that differ from the protocol library's
     * are noted: {@code ItemComponentPacket} is {@code ItemRegistryPacket} and {@code AvailableEntityIdentifiersPacket} is
     * {@code AvailableActorIdentifiersPacket} in BDS.
     */
    static final List<String> DECODED = List.of(
        "LoginPacket", "PlayStatusPacket", "NetworkSettingsPacket", "RequestNetworkSettingsPacket", "ClientToServerHandshakePacket",
        "ServerToClientHandshakePacket", "ResourcePacksInfoPacket", "ResourcePackStackPacket", "ResourcePackClientResponsePacket",
        "StartGamePacket", "ItemRegistryPacket", "CreativeContentPacket", "CraftingDataPacket", "TrimDataPacket",
        "BiomeDefinitionListPacket", "AvailableActorIdentifiersPacket", "VoxelShapesPacket", "InventoryContentPacket",
        "InventorySlotPacket", "RequestChunkRadiusPacket", "SetLocalPlayerAsInitializedPacket", "CommandRequestPacket",
        "CommandOutputPacket", "DisconnectPacket", "PacketViolationWarningPacket");

    private static final String PROTOCOL_DIR = "json_schemas/protocol";
    private static final Set<String> STAMPS = Set.of("x-minecraft-version", "x-protocol-version");

    private ProtocolSchemaCheck() {
    }

    /** The packets that changed, each with the schema files that differ (a file on one side only counts); empty when the codec is safe. */
    record Result(Map<String, List<String>> changed, List<String> notInSchema) {
        boolean safe() {
            return changed.isEmpty();
        }
    }

    /**
     * The protocol stamped in a docgen tree, or -1 if the tree has no protocol schema. Read from {@code StartGamePacket.json}, else
     * from the first stamped file of the directory: a tree whose packets were renamed is still found, and then fails the check.
     */
    static int protocolOf(Path docgen) throws IOException {
        Path protocol = docgen.resolve(PROTOCOL_DIR);
        if (!Files.isDirectory(protocol)) {
            return -1;
        }
        List<Path> files = new ArrayList<>(List.of(protocol.resolve("StartGamePacket.json")));
        try (Stream<Path> list = Files.list(protocol)) {
            list.filter(file -> file.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
        }
        for (Path file : files) {
            JsonElement stamp = Files.isRegularFile(file) ? read(file).get("x-protocol-version") : null;
            if (stamp != null) {
                return stamp.getAsInt();
            }
        }
        return -1;
    }

    /** The cached docgen tree stamped with the protocol, or null. */
    static Path find(Path docgenCache, int protocol) throws IOException {
        if (!Files.isDirectory(docgenCache)) {
            return null;
        }
        try (Stream<Path> trees = Files.list(docgenCache)) {
            for (Path tree : trees.sorted().toList()) {
                if (protocolOf(tree) == protocol) {
                    return tree;
                }
            }
        }
        return null;
    }

    /** Compares the packets of {@link #DECODED} between the two docgen trees. */
    static Result compare(Path codecTree, Path buildTree) throws IOException {
        Map<String, List<String>> changed = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String packet : DECODED) {
            if (!Files.isRegularFile(codecTree.resolve(PROTOCOL_DIR).resolve(packet + ".json"))
                && !Files.isRegularFile(buildTree.resolve(PROTOCOL_DIR).resolve(packet + ".json"))) {
                missing.add(packet);
                continue;
            }
            List<String> files = differing(codecTree.resolve(PROTOCOL_DIR), buildTree.resolve(PROTOCOL_DIR), packet + ".json");
            if (!files.isEmpty()) {
                changed.put(packet, files);
            }
        }
        return new Result(changed, missing);
    }

    /** The files reachable from {@code root} (in either tree) whose normalized content differs or that only one tree has. */
    private static List<String> differing(Path codec, Path build, String root) throws IOException {
        Set<String> seen = new TreeSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(root));
        List<String> differing = new ArrayList<>();
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!seen.add(name)) {
                continue;
            }
            JsonObject before = Files.isRegularFile(codec.resolve(name)) ? normalized(read(codec.resolve(name))) : null;
            JsonObject after = Files.isRegularFile(build.resolve(name)) ? normalized(read(build.resolve(name))) : null;
            if (before == null && after == null) {
                continue;
            }
            if (before == null || after == null) {
                differing.add(name + (before == null ? " (new)" : " (removed)"));
            } else if (!before.equals(after)) {
                differing.add(name);
            }
            // Both sides: a type the new schema starts to use is read from the new tree
            if (before != null) {
                references(before, queue);
            }
            if (after != null) {
                references(after, queue);
            }
        }
        return differing;
    }

    private static void references(JsonElement element, Deque<String> queue) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals("$ref") && entry.getValue().isJsonPrimitive()) {
                    String ref = entry.getValue().getAsString();
                    queue.add(ref.startsWith("./") ? ref.substring(2) : ref);
                } else {
                    references(entry.getValue(), queue);
                }
            }
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> references(child, queue));
        }
    }

    /** The schema without the build and protocol stamps (which differ in every file of another build). */
    private static JsonObject normalized(JsonObject schema) {
        JsonObject copy = schema.deepCopy();
        STAMPS.forEach(copy::remove);
        return copy;
    }

    private static JsonObject read(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /**
     * The report rows of a run whose captures were decoded with another protocol's codec: "safe" or the packets that changed.
     * A protocol without a cached docgen tree is a skipped check (no guessing); trees that have a schema file for none of the
     * {@link #DECODED} packets are a FAIL (BDS renamed or moved its protocol schemas: a PASS would be vacuous).
     *
     * @param docgen the directory of the docgen trees ({@code <cache>/docgen}), one per build
     */
    static void report(Report report, Path docgen, int codecProtocol, int protocol) {
        String name = "Protocol codec safety";
        try {
            Path codecTree = find(docgen, codecProtocol);
            Path buildTree = find(docgen, protocol);
            if (codecTree == null || buildTree == null) {
                report.check(Report.Status.SKIP, name, "no cached BDS docgen tree for protocol " + (codecTree == null ? codecProtocol : protocol)
                    + ": the packets cannot be compared");
                return;
            }
            Result result = compare(codecTree, buildTree);
            String versions = "BDS docgen " + codecTree.getFileName() + " (" + codecProtocol + ") vs " + buildTree.getFileName() + " (" + protocol + ")";
            if (result.notInSchema().size() == DECODED.size()) {
                report.check(Report.Status.FAIL, name, "none of the " + DECODED.size() + " decoded packets has a schema file in " + PROTOCOL_DIR
                    + " (" + versions + "): BDS renamed or moved its protocol schemas");
                report.action("map ProtocolSchemaCheck.DECODED to the schema file names of BDS's protocol schemas");
                return;
            }
            if (result.safe()) {
                report.check(Report.Status.PASS, name, "safe: none of the " + (DECODED.size() - result.notInSchema().size())
                    + " decoded packets changed between the " + codecProtocol + " codec and protocol " + protocol + " (" + versions + ")");
            } else {
                report.check(Report.Status.DIFF, name, result.changed().size() + " decoded packets changed between the " + codecProtocol
                    + " codec and protocol " + protocol + ": " + String.join(", ", result.changed().keySet()) + " (" + versions + ")");
                report.action("the captures of the changed packets may be decoded wrong: update the protocol library's codec and rerun the captures");
                List<String> lines = new ArrayList<>();
                result.changed().forEach((packet, files) ->
                    lines.add("- `" + packet + "`: " + String.join(", ", files.stream().map(file -> "`" + file + "`").toList())));
                report.evidence("Packets changed (BDS docgen schema, " + codecProtocol + " to " + protocol + ")", lines);
            }
            if (!result.notInSchema().isEmpty()) {
                report.evidence("Decoded packets without a BDS schema file", result.notInSchema().stream().map(packet -> "- " + packet).toList());
            }
        } catch (IOException | RuntimeException e) {
            report.check(Report.Status.SKIP, name, "could not read the docgen trees: " + e);
        }
    }
}
