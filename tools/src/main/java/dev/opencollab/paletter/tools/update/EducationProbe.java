package dev.opencollab.paletter.tools.update;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.internal.data.PaletteDataCodec;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.tools.Layout;
import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.bds.BdsServer;
import dev.opencollab.paletter.tools.client.Capture;
import dev.opencollab.paletter.tools.client.CapturedData;
import dev.opencollab.paletter.tools.client.LevelDat;
import dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * {@code education-capture}: which content is Education Edition, from what BDS sends. Education content is only offered
 * when the world has {@code educationFeaturesEnabled} (the Education Edition switch of {@code level.dat}); this starts
 * one world twice, with the switch off and on, captures what BDS sends a client both times and keeps the identifiers
 * that only the Education Edition world has ({@link #diff}). The result is {@code data/bds/<bds version>/education.json},
 * the input of the constants generator ({@code @Education}, {@code isEducation()}).
 * <p>
 * Options: {@code --version 1.26.63.1}, {@code --cache DIR}, {@code --port 19300}, {@code --reference 26.60} (bundled
 * version the capture resolves block hashes against; default the one with the newest protocol not above the server's),
 * {@code --out DIR} (captures; default {@code <cache>/runs/education-<platform>-<version>}), {@code --timeout 300}.
 * The server is extracted into {@code <cache>/servers/education-<platform>-<version>} and removed afterwards.
 */
public final class EducationProbe {
    private static final String WORLD = "paletter_education";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private EducationProbe() {
    }

    /**
     * What the Education Edition world has that the other lacks.
     *
     * @param items item identifiers: in the creative inventory or the item registry only with education on
     * @param blocks block identifiers: the blocks of those creative stacks, and the blocks the education world adds to
     *               the start game block properties
     */
    public record Result(List<String> items, List<String> blocks) {
        public boolean isEmpty() {
            return items.isEmpty() && blocks.isEmpty();
        }

        public JsonObject toJson(String bdsVersion, int protocol) {
            JsonObject json = new JsonObject();
            json.addProperty("bdsVersion", bdsVersion);
            json.addProperty("protocol", protocol);
            json.add("items", array(items));
            json.add("blocks", array(blocks));
            return json;
        }

        private static JsonArray array(List<String> values) {
            JsonArray array = new JsonArray();
            values.forEach(array::add);
            return array;
        }
    }

    /**
     * The identifiers captured with education on and not with it off.
     */
    public static Result diff(CapturedData off, CapturedData on) {
        TreeSet<String> items = new TreeSet<>();
        TreeSet<String> blocks = new TreeSet<>();
        for (String item : on.items().keySet()) {
            if (!off.items().containsKey(item)) {
                items.add(item);
            }
        }
        for (String block : on.blockProperties().keySet()) {
            if (!off.blockProperties().containsKey(block)) {
                blocks.add(block);
            }
        }
        TreeSet<String> before = new TreeSet<>();
        off.creative().forEachStack(stack -> before.add(stack.identifier()));
        on.creative().forEachStack(stack -> {
            if (!before.contains(stack.identifier())) {
                items.add(stack.identifier());
                NbtMap state = stack.blockState();
                if (state != null) {
                    blocks.add(state.getString("name"));
                }
            }
        });
        return new Result(List.copyOf(items), List.copyOf(blocks));
    }

    public static void run(Path root, Map<String, String> options) throws Exception {
        Path cache = options.containsKey("cache") ? Path.of(options.get("cache")) : BdsDistribution.defaultCache();
        BdsDistribution distribution = BdsDistribution.resolve(false, options.get("version"));
        Path server = distribution.serverDirectory(cache, "education-");
        Path out = options.containsKey("out") ? Path.of(options.get("out")).toAbsolutePath()
            : distribution.runDirectory(cache, "education-");
        int port = Integer.parseInt(options.getOrDefault("port", "19300"));
        int timeout = Integer.parseInt(options.getOrDefault("timeout", "300"));
        BdsDistribution.deleteRecursively(out);
        Files.createDirectories(out);
        if (!Files.exists(BdsServer.executable(server))) {
            BdsDistribution.extract(distribution.zip(cache, root), server);
        }
        try {
            Result result = capture(root, options, distribution, server, out, port, timeout);
            System.out.println("Education content of BDS " + distribution.version() + ": " + result.items().size() + " items, "
                + result.blocks().size() + " blocks");
        } finally {
            BdsDistribution.deleteRecursively(server);
        }
    }

    private static Result capture(Path root, Map<String, String> options, BdsDistribution distribution, Path server, Path out,
                                  int port, int timeout) throws Exception {
        Capture.Reachable reachable = createWorld(server, port, out, timeout);
        if (reachable == null || reachable.protocol() == 0) {
            throw new IOException("BDS answers no ping or LAN discovery with a protocol; pass the version to education-capture");
        }
        int protocol = reachable.protocol();
        VanillaData reference = reference(root, options.get("reference"), protocol);
        System.out.println("BDS " + distribution.version() + ", protocol " + protocol + ", hashes resolved against " + reference.version().name());
        Result result = compare(server, port, protocol, reference, reachable.netherNet(), distribution.version(), out, timeout);
        Path target = BdsDistribution.inside(root.resolve(Layout.BDS), distribution.version()).resolve("education.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, render(result, distribution.version(), protocol), StandardCharsets.UTF_8);
        System.out.println("Wrote " + root.relativize(target));
        return result;
    }

    /** The content of {@code education.json}. */
    public static String render(Result result, String bdsVersion, int protocol) {
        return GSON.toJson(result.toJson(bdsVersion, protocol)) + "\n";
    }

    /** Whether two {@code education.json} files list the same identifiers: the build and protocol they were taken on are left out. */
    public static boolean sameContent(String a, String b) {
        JsonObject first = JsonParser.parseString(a).getAsJsonObject();
        JsonObject second = JsonParser.parseString(b).getAsJsonObject();
        for (JsonObject json : new JsonObject[] {first, second}) {
            json.remove("bdsVersion");
            json.remove("protocol");
        }
        return first.equals(second);
    }

    /** The server properties of the education world; the world is created on {@code port}. */
    private static Map<String, String> properties(int port) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("level-name", WORLD);
        properties.put("level-type", "FLAT");
        properties.put("level-seed", "0");
        properties.put("gamemode", "creative");
        properties.put("server-port", String.valueOf(port));
        properties.put("server-portv6", String.valueOf(port + 1));
        properties.put("transport", "raknet");
        properties.put("enable-lan-visibility", "true");
        properties.put("online-mode", "false");
        properties.put("allow-list", "false");
        properties.put("view-distance", "4");
        properties.put("block-network-ids-are-hashes", "true");
        return properties;
    }

    /**
     * Creates the education world on {@code server} with one start of BDS.
     *
     * @return how the started server was reached, null if it answered neither a RakNet ping nor LAN discovery
     */
    public static Capture.Reachable createWorld(Path server, int port, Path out, int timeout) throws Exception {
        BdsServer.configure(server, properties(port));
        BdsDistribution.deleteRecursively(server.resolve("worlds").resolve(WORLD));
        Files.createDirectories(server.resolve("worlds").resolve(WORLD));
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(out.resolve("bds-create.log"));
            bds.awaitStarted(timeout);
            return Capture.reachable(bds, new InetSocketAddress("127.0.0.1", port), 20);
        }
    }

    /**
     * Captures the world made by {@link #createWorld} with education off and on (two starts of BDS) and keeps what only the
     * second has.
     *
     * @param reference the palette the block hashes are resolved against
     * @param netherNet whether the server is reached over NetherNet instead of RakNet
     * @throws IOException if the two captures are the same
     */
    public static Result compare(Path server, int port, int protocol, VanillaData reference, boolean netherNet, String bdsVersion,
                                 Path out, int timeout) throws Exception {
        Map<String, String> properties = properties(port);
        Path levelDat = server.resolve("worlds").resolve(WORLD).resolve("level.dat");
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        // Education off is the world as created; both captures go through the same level.dat round trip
        Map<Boolean, Path> captures = new LinkedHashMap<>();
        properties.put("enable-lan-visibility", "false");
        BdsServer.configure(server, properties);
        for (boolean education : new boolean[] {false, true}) {
            LevelDat.setEducation(levelDat, education);
            Path dir = out.resolve(education ? "education-on" : "education-off");
            try (BdsServer bds = new BdsServer(server, false)) {
                bds.start(out.resolve("bds-" + (education ? "on" : "off") + ".log"));
                bds.awaitStarted(timeout);
                Capture.capture(address, protocol, reference, bdsVersion, dir, null, 90, netherNet);
            }
            if (LevelDat.education(levelDat) != education) {
                throw new IOException("BDS did not keep educationFeaturesEnabled=" + education + " in level.dat");
            }
            captures.put(education, dir);
        }
        Result result = diff(CapturedData.read(captures.get(false)), CapturedData.read(captures.get(true)));
        if (result.isEmpty()) {
            throw new IOException("The Education Edition world has no content the other lacks: BDS " + bdsVersion
                + " ignored educationFeaturesEnabled, or the capture is incomplete");
        }
        return result;
    }

    /**
     * The bundled version {@code name}, or the one of this protocol (a bundled preview too: it was imported from this
     * build's own states, so hashes of blocks that changed in it resolve), else the newest release not above it.
     */
    public static VanillaData reference(Path root, String name, int protocol) throws IOException {
        Path data = root.resolve(ConstantsGenerator.DATA);
        if (name == null) {
            name = reference(ConstantsGenerator.readIndex(data), protocol).name();
        }
        try (InputStream stream = Files.newInputStream(data.resolve(name + ".nbt"))) {
            return PaletteDataCodec.read(stream);
        }
    }

    /** The bundled version a capture of {@code protocol} resolves block hashes with. */
    static ConstantsGenerator.Bundle reference(List<ConstantsGenerator.Bundle> bundles, int protocol) throws IOException {
        ConstantsGenerator.Bundle best = null;
        for (ConstantsGenerator.Bundle bundle : bundles) {
            if (bundle.protocol() == protocol) {
                return bundle;
            }
            if (!bundle.preview() && bundle.protocol() < protocol) {
                best = bundle;
            }
        }
        if (best == null) {
            throw new IOException("No bundled version for protocol " + protocol + "; pass --reference");
        }
        return best;
    }
}
