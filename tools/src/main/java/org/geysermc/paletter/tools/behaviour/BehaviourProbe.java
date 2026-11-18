package org.geysermc.paletter.tools.behaviour;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.Layout;
import org.geysermc.paletter.tools.bds.BdsDistribution;
import org.geysermc.paletter.tools.bds.BdsRunner;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code behaviour-probe}: runs the probe add-on ({@code data/addons/probe}, hand-written) on a BDS and turns its
 * observations into values. The add-on measures by experiment (bodies sliding over floors and into blocks, light
 * levels, explosions, fire) and prints what it saw; this class draws the conclusions, so the evidence is BDS output
 * and the rule from output to value is code with tests. It writes {@code data/bds/<version>/behaviour-probe/}:
 * {@code probe.log} (the probe's lines of the BDS console, verbatim) and {@code capture.json} (the measured values,
 * each with its method). {@code behaviour import} reads the capture ({@link BehaviourImport#probe}).
 * <p>
 * A conclusion the observations do not support is an error, never a guess.
 */
public final class BehaviourProbe {
    static final String DIRECTORY = "behaviour-probe";
    static final String CAPTURE = "capture.json";
    static final String LOG = "probe.log";
    /** The directory of the add-on in the {@code behavior_packs} of the server it ran on. */
    public static final String PACK = "paletter_probe";
    /** Seconds a run may take at least: the states of every block are placed and measured, which takes about 20 minutes of game time. */
    public static final int MIN_TIMEOUT = 3600;

    /** A body's horizontal speed falls by this factor times the friction of what it stands on each tick. */
    static final double MOB_DRAG = 0.91;
    /** Half the width of the probe body: it stops this far from the face that blocks it. */
    static final double HALF_WIDTH = 0.25;
    /** Contact positions are snapped to 4 decimals (BDS dumps give 0.1825); the probe measures them to within 1e-5. */
    private static final double SNAP = 10000;
    private static final double SNAP_TOLERANCE = 2e-5;
    private static final double EPSILON = 1e-3;
    private static final String[] DOOR_STATES = {"minecraft:cardinal_direction", "open_bit", "door_hinge_bit", "upper_block_bit"};

    private BehaviourProbe() {
    }

    /**
     * Runs the probe on a BDS and writes its console to {@code log}.
     *
     * @param preview whether {@code bdsVersion} is a preview build
     * @param server an extracted BDS, or null to use the build's server in the cache (downloaded and extracted for the run
     *               and removed after it, unless it was there already)
     */
    public static void run(Path root, String bdsVersion, boolean preview, Path server, Path cache, int port, int timeout, Path log)
        throws IOException, InterruptedException {
        BdsRunner runner = new BdsRunner(root.resolve(Layout.PROBE_ADDON), "PROBE", PACK).withPackFiles(blockFiles(root, bdsVersion));
        if (server != null) {
            runner.run(server, log, timeout, port, false);
        } else {
            BdsDistribution.resolve(preview, bdsVersion).onServer(cache == null ? BdsDistribution.defaultCache() : cache,
                extracted -> runner.run(extracted, log, timeout, port, false));
        }
    }

    /**
     * The format version of the blocks of the pack for a BDS version, as the spec add-on picks it ({@code fixtures.json}'s, no newer
     * than the build): a build loads no block file of a newer format ({@code unsupported version}). The files of the pack have none.
     *
     * @param version a build ({@code 1.26.63.1}) or what BDS reports ({@code 1.26.70-beta24})
     */
    static String blockFormat(Path root, String version) throws IOException {
        Matcher number = Pattern.compile("^\\d+\\.\\d+\\.\\d+").matcher(version);
        if (!number.find()) {
            throw new IOException("Not a BDS version: " + version);
        }
        return AddonGenerator.formatVersionFor(Fixtures.read(root.resolve(Layout.MODEL)).formatVersion, number.group());
    }

    /** The block files of the pack (pack path to content) with the {@link #blockFormat format version} of the build. */
    static Map<String, String> blockFiles(Path root, String bdsVersion) throws IOException {
        String format = blockFormat(root, bdsVersion);
        Path blocks = root.resolve(Layout.PROBE_ADDON).resolve("blocks");
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> list = Files.list(blocks)) {
            for (Path path : list.sorted().toList()) {
                JsonObject block = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                JsonObject file = new JsonObject();
                file.addProperty("format_version", format);
                block.entrySet().forEach(entry -> file.add(entry.getKey(), entry.getValue()));
                files.put("blocks/" + path.getFileName(), file + "\n");
            }
        }
        return files;
    }

    /**
     * What a run of the probe kept for its BDS build.
     *
     * @param version the BDS version the probe reported
     * @param build its Build ID
     * @param log the content of {@link #LOG}
     * @param capture the content of {@link #CAPTURE}
     */
    public record Measured(String version, String build, String log, String capture) {
    }

    /** Measures a console log of the probe. */
    public static Measured fromConsole(Path root, Path console) throws IOException {
        ProbeLog log = ProbeLog.parse(Files.readAllLines(console, StandardCharsets.UTF_8));
        JsonObject capture = measure(log, blockFormat(root, log.version()));
        return new Measured(log.version(), log.build(), String.join("\n", log.extract()) + "\n", Json.pretty(capture));
    }

    /** Whether two {@link #CAPTURE} files say the same about BDS: the build they were taken on is left out. */
    public static boolean sameCapture(String a, String b) {
        return withoutBuild(a).equals(withoutBuild(b));
    }

    private static JsonObject withoutBuild(String capture) {
        JsonObject json = JsonParser.parseString(capture).getAsJsonObject();
        json.remove("bds");
        return json;
    }

    /**
     * Measures a console log of the probe and writes the capture next to the other captures of its BDS build.
     *
     * @return the directory written
     */
    public static Path write(Path root, Path console) throws IOException {
        Measured measured = fromConsole(root, console);
        Path directory = root.resolve("data/bds").resolve(measured.version()).resolve(DIRECTORY);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(LOG), measured.log());
        Files.writeString(directory.resolve(CAPTURE), measured.capture());
        System.out.println("Wrote " + directory.resolve(CAPTURE) + " from BDS " + measured.version() + " (Build ID " + measured.build() + ")");
        return directory;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // From observations to values

    static JsonObject measure(ProbeLog log, String blockFormatVersion) {
        JsonObject capture = new JsonObject();
        capture.addProperty("generated", "Generated by `behaviour-probe` (tools, BehaviourProbe) from probe.log, the console of the add-on "
            + Layout.PROBE_ADDON + " on this BDS build. Do not edit: run the command again.");
        JsonObject bds = new JsonObject();
        bds.addProperty("version", log.version());
        bds.addProperty("build", log.build());
        capture.add("bds", bds);
        capture.addProperty("block_format_version", blockFormatVersion);
        capture.addProperty("log", LOG);

        JsonArray defaults = new JsonArray();
        JsonObject friction = friction(log, defaults);
        light(log, defaults);
        explosion(log, defaults);
        collisionAndFire(log, defaults);
        capture.add("defaults", defaults);
        capture.add("friction", friction);
        capture.add("doors", doors(log));
        capture.add("climbing", climbing(log, defaults));
        StateProbe.addTo(capture, log);
        return capture;
    }

    private static void addDefault(JsonArray defaults, String key, JsonElement value, String method) {
        JsonObject entry = new JsonObject();
        entry.addProperty("key", key);
        entry.add("value", value);
        entry.addProperty("method", method);
        defaults.add(entry);
    }

    private static JsonElement number(Number value) {
        return new com.google.gson.JsonPrimitive(value);
    }

    // ---- friction ----

    /**
     * A body slid over 90 blocks of each floor: it loses a constant share of its speed every tick, {@link #MOB_DRAG} times
     * the floor's friction. The component {@code minecraft:friction} is checked against that: engine friction is 1 minus
     * the component value for every value probed.
     */
    private static JsonObject friction(ProbeLog log, JsonArray defaults) {
        Map<String, Double> engine = new LinkedHashMap<>();
        for (JsonObject floor : log.of("friction")) {
            engine.put(floor.get("block").getAsString(), engineFriction(floor.getAsJsonArray("entity")));
        }
        JsonArray components = new JsonArray();
        for (Map.Entry<String, Double> entry : engine.entrySet()) {
            if (!entry.getKey().startsWith("probe:friction_")) {
                continue;
            }
            double component = Integer.parseInt(entry.getKey().substring("probe:friction_".length())) / 100.0;
            if (Math.abs(entry.getValue() - (1 - component)) > 0.002) {
                throw new IllegalStateException(entry.getKey() + ": engine friction " + entry.getValue() + " is not 1 - " + component);
            }
            JsonObject sample = new JsonObject();
            sample.addProperty("component", component);
            sample.addProperty("engine", entry.getValue());
            components.add(sample);
        }
        if (components.isEmpty()) {
            throw new IllegalStateException("No probe:friction_* floors in the log");
        }
        double plain = engine.get("probe:plain");
        addDefault(defaults, "block:friction", number(plain), "A body slid over 90 blocks of a custom block that sets no friction: it lost "
            + "friction * " + MOB_DRAG + " of its speed per tick, as it does on the vanilla floors listed under friction.vanilla. "
            + "The conversion of the component is confirmed (friction.components).");
        JsonObject result = new JsonObject();
        result.addProperty("method", "Entity speed loss per tick (median over the slide) divided by " + MOB_DRAG + ", 3 decimals; the floor of "
            + "every lane is a block of one kind");
        result.addProperty("engine_friction_is", "1 - the value of minecraft:friction (every probed value, within 0.002)");
        result.add("components", components);
        JsonObject vanilla = new JsonObject();
        engine.forEach((block, value) -> {
            if (block.startsWith("minecraft:")) {
                vanilla.addProperty(block, value);
            }
        });
        result.add("vanilla", vanilla);
        return result;
    }

    static double engineFriction(JsonArray track) {
        List<Double> velocities = new ArrayList<>();
        track.forEach(tick -> velocities.add(tick.getAsJsonArray().get(1).getAsDouble()));
        return StateProbe.slideFriction(velocities, Double.MAX_VALUE).orElseThrow(() -> new IllegalStateException("Not enough of a slide to measure friction: "
            + velocities.size() + " ticks"));
    }

    // ---- light ----

    /**
     * Light: next to glowstone, behind a block, light is lost by the block's dampening; a block of light dampening 14 and
     * one of 0 are the references (BDS accepts 0 to 15). Emission: the light next to the block.
     */
    private static void light(ProbeLog log, JsonArray defaults) {
        Map<String, JsonObject> tunnels = byBlock(log.of("tunnel"));
        JsonObject plain = tunnels.get("probe:plain");
        JsonObject low = tunnels.get("probe:light_dampening_0");
        JsonObject high = tunnels.get("probe:light_dampening_14");
        int dampening;
        String why;
        if (same(plain, low, "behind", "inside")) {
            dampening = 0;
            why = "the same light behind and inside as light_dampening 0";
        } else if (same(plain, high, "behind", "inside")) {
            dampening = 14;
            why = "the same light behind and inside as light_dampening 14";
        } else if (plain.get("inside").getAsInt() < high.get("inside").getAsInt() && plain.get("behind").getAsInt() <= high.get("behind").getAsInt()) {
            dampening = 15;
            why = "less light gets into it than into light_dampening 14 (" + plain.get("inside") + " against " + high.get("inside")
                + ") and none gets through, as with stone; BDS accepts at most 15";
        } else {
            throw new IllegalStateException("Light dampening of probe:plain is not decided by the tunnels: " + plain);
        }
        addDefault(defaults, "block:light_dampening", number(dampening), "Block light from glowstone next to a custom block that sets no "
            + "light_dampening, read behind it and inside it: " + why + ".");

        Map<String, JsonObject> emit = new LinkedHashMap<>();
        for (JsonObject cell : log.of("light")) {
            if (cell.get("kind").getAsString().equals("emit")) {
                emit.put(cell.get("block").getAsString(), cell);
            }
        }
        int none = emit.get("minecraft:stone").get("light").getAsInt();
        int glow = emit.get("probe:light_emission_15").get("light").getAsInt();
        int plainLight = emit.get("probe:plain").get("light").getAsInt();
        if (plainLight != none || glow <= none) {
            throw new IllegalStateException("Light emission of probe:plain is not decided: " + emit);
        }
        addDefault(defaults, "block:light_emission", number(0), "The light next to a custom block that sets no light_emission is " + plainLight
            + ", as next to stone; next to light_emission 15 it is " + glow + ". An emission of 1 would light nothing next to it either, "
            + "so 0 is the value that lights nothing.");
    }

    private static Map<String, JsonObject> byBlock(List<JsonObject> observations) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (JsonObject observation : observations) {
            result.put(observation.get("block").getAsString(), observation);
        }
        return result;
    }

    private static boolean same(JsonObject a, JsonObject b, String... fields) {
        for (String field : fields) {
            if (a.get(field).getAsInt() != b.get(field).getAsInt()) {
                return false;
            }
        }
        return true;
    }

    // ---- explosion resistance ----

    /**
     * Blocks beside explosions, at the distances where the explicit resistances 0 and 3 are destroyed measurably differently
     * (Fisher's exact test, p below 0.01): the default block is destroyed as often as explicit {@code explosion_resistance: 0}
     * (p at least 0.05) and measurably more often than resistance 3 and the vanilla dirt and sand (p below 0.01).
     */
    private static void explosion(ProbeLog log, JsonArray defaults) {
        JsonObject all = log.of("explosion").get(0);
        Map<String, int[]> pooled = new LinkedHashMap<>();
        List<String> distances = new ArrayList<>();
        JsonObject low = all.getAsJsonObject("probe:explosion_resistance_0");
        JsonObject high = all.getAsJsonObject("probe:explosion_resistance_3");
        for (String distance : low.keySet()) {
            // where the references differ: closer both are mostly destroyed, farther neither is
            JsonArray a = low.getAsJsonArray(distance);
            JsonArray b = high.getAsJsonArray(distance);
            if (fisher(a.get(0).getAsInt(), a.get(1).getAsInt(), b.get(0).getAsInt(), b.get(1).getAsInt()) < 0.01) {
                distances.add(distance);
            }
        }
        if (distances.isEmpty()) {
            throw new IllegalStateException("The explosions distinguish no resistances: " + all);
        }
        for (String block : all.keySet()) {
            int[] count = new int[2];
            for (String distance : distances) {
                count[0] += all.getAsJsonObject(block).getAsJsonArray(distance).get(0).getAsInt();
                count[1] += all.getAsJsonObject(block).getAsJsonArray(distance).get(1).getAsInt();
            }
            pooled.put(block, count);
        }
        int[] plain = pooled.get("probe:plain");
        StringBuilder evidence = new StringBuilder();
        for (Map.Entry<String, int[]> entry : pooled.entrySet()) {
            String block = entry.getKey();
            if (block.equals("probe:plain") || block.equals("probe:destructible_by_explosion")) {
                continue;
            }
            double p = fisher(plain[0], plain[1], entry.getValue()[0], entry.getValue()[1]);
            boolean zero = block.equals("probe:explosion_resistance_0");
            if (zero ? p < 0.05 : p >= 0.01) {
                throw new IllegalStateException("Explosion resistance of probe:plain is not decided: against " + block + " p = " + p + " (" + pooled + ")");
            }
            evidence.append(evidence.length() == 0 ? "" : "; ").append(block.replace("probe:", "").replace("minecraft:", "")).append(' ')
                .append(entry.getValue()[0]).append('/').append(entry.getValue()[1])
                .append(p < 0.001 ? " p<0.001" : String.format(Locale.ROOT, " p=%.3f", p));
        }
        addDefault(defaults, "block:explosion_resistance", number(0), "Destroyed beside explosions (power 4) at distance " + String.join(", ", distances)
            + ": a custom block that sets no resistance " + plain[0] + "/" + plain[1] + ", the same as explosion_resistance 0 and more often than "
            + "the others (Fisher's exact test, against each: " + evidence + "). The unit is the component's.");
    }

    /** Two sided Fisher exact test: the chance of a table at least as unlikely as 2x2 {@code [a, n - a], [b, m - b]}. */
    static double fisher(int a, int n, int b, int m) {
        int total = n + m;
        int destroyed = a + b;
        double observed = hypergeometric(a, n, destroyed, total);
        double p = 0;
        for (int x = Math.max(0, destroyed - m); x <= Math.min(n, destroyed); x++) {
            double chance = hypergeometric(x, n, destroyed, total);
            if (chance <= observed * (1 + 1e-9)) {
                p += chance;
            }
        }
        return Math.min(1, p);
    }

    private static double hypergeometric(int x, int n, int destroyed, int total) {
        return Math.exp(logChoose(n, x) + logChoose(total - n, destroyed - x) - logChoose(total, destroyed));
    }

    private static double logChoose(int n, int k) {
        double result = 0;
        for (int i = 1; i <= k; i++) {
            result += Math.log(n - k + i) - Math.log(i);
        }
        return result;
    }

    // ---- collision and fire ----

    /**
     * Collision: bodies pushed at the block from all four sides stop at the faces of a full cube (like stone), and a body
     * dropped onto it lands on its top (a block without collision lets it through). Fire: fire on top of 12 blocks for 400
     * ticks, with fire ticking.
     */
    private static void collisionAndFire(ProbeLog log, JsonArray defaults) {
        Map<String, JsonObject> drops = byBlock(log.of("drop"));
        for (String block : List.of("minecraft:stone", "probe:plain")) {
            JsonObject drop = drops.get(block);
            if (Math.abs(drop.get("y").getAsDouble() - drop.get("blockY").getAsDouble() - 1) > EPSILON) {
                throw new IllegalStateException(block + " did not stop a falling body one block above its bottom: " + drop);
            }
        }
        JsonObject through = drops.get("probe:no_collision");
        if (through.get("y").isJsonNull() || through.get("y").getAsDouble() > through.get("blockY").getAsDouble()) {
            throw new IllegalStateException("A block without collision stopped the body: " + through);
        }
        Map<String, Map<String, Map<String, JsonObject>>> cells = cells(log);
        double[] plainBox = solidBox(cells.get("probe:plain"));
        double[] stoneBox = solidBox(cells.get("minecraft:stone"));
        if (!java.util.Arrays.equals(plainBox, stoneBox) || !java.util.Arrays.equals(plainBox, new double[]{0, 1, 0, 1})) {
            throw new IllegalStateException("The default collision is not a full cube in x and z: " + java.util.Arrays.toString(plainBox));
        }
        JsonArray box = new JsonArray();
        for (double coordinate : new double[]{0, 0, 0, 1, 1, 1}) {
            box.add(coordinate);
        }
        JsonArray boxes = new JsonArray();
        boxes.add(box);
        addDefault(defaults, "block:collision_shape", boxes, "Bodies pushed at a custom block that sets no collision_box from the four sides "
            + "stopped at x 0 to 1 and z 0 to 1 exactly as at stone (bodies 0.5 tall level with its bottom half), and a body dropped onto "
            + "it landed on its top, 1 above its bottom, as on stone (a block with collision_box false let it fall through).");

        JsonObject fires = log.of("flammable").get(0);
        int total = fires.getAsJsonObject("probe:plain").get("total").getAsInt();
        if (fires.getAsJsonObject("probe:plain").get("remaining").getAsInt() != total
            || fires.getAsJsonObject("minecraft:stone").get("remaining").getAsInt() != total
            || fires.getAsJsonObject("probe:flammable").get("remaining").getAsInt() != 0
            || fires.getAsJsonObject("minecraft:oak_planks").get("remaining").getAsInt() != 0) {
            throw new IllegalStateException("The fire test does not tell flammable blocks apart: " + fires);
        }
        String method = "Fire on top of " + total + " custom blocks that set no flammable component, 400 ticks with fire ticking: all " + total
            + " were still there, as with stone; with minecraft:flammable true (and oak planks) none was.";
        addDefault(defaults, "block:burn_odds", number(0), method + " It does not burn away.");
        addDefault(defaults, "block:flame_odds", number(0), method + " A block that cannot burn away is not flammable, so it has no odds of catching "
            + "fire; the test does not read that value separately.");
    }

    // ---- doors and solid blocks ----

    /** Observations by block, then half, then state key, then side. */
    private static Map<String, Map<String, Map<String, JsonObject>>> cells(ProbeLog log) {
        Map<String, Map<String, Map<String, JsonObject>>> result = new LinkedHashMap<>();
        for (JsonObject cell : log.of("cell")) {
            String state = cell.get("state").isJsonNull() ? "" : stateKey(cell.getAsJsonObject("state"));
            result.computeIfAbsent(cell.get("block").getAsString(), k -> new LinkedHashMap<>())
                .computeIfAbsent(cell.get("half").getAsString() + "|" + state, k -> new LinkedHashMap<>())
                .put(cell.get("side").getAsString(), cell);
        }
        return result;
    }

    private static String stateKey(JsonObject state) {
        StringBuilder key = new StringBuilder();
        for (String name : DOOR_STATES) {
            key.append(key.length() == 0 ? "" : ",").append(name).append('=').append(state.get(name).getAsString());
        }
        return key.toString();
    }

    /**
     * Whether a body that started on the side of the cell was stopped by something in it. A free body ends beyond the
     * cell's centre; a stop at the face of something in the cell is, on the side the body came from, between 0.25 (a face at
     * the cell's far edge) and 1.25 (a face at its near edge) for the positive side, and mirrored for the negative side.
     */
    static boolean blocked(String side, JsonObject observation) {
        double rel = observation.get("rel").getAsDouble();
        boolean positive = side.startsWith("+");
        boolean within = positive ? rel >= HALF_WIDTH - EPSILON && rel <= 1 + HALF_WIDTH + EPSILON
            : rel <= 1 - HALF_WIDTH + EPSILON && rel >= -HALF_WIDTH - EPSILON;
        return within && observation.get("y").getAsDouble() >= observation.get("level").getAsDouble() - EPSILON;
    }

    /**
     * The interval of the obstacle on one axis, or null if the body was not stopped from both sides: from the negative
     * side the body stops 0.25 before the obstacle's minimum, from the positive side 0.25 after its maximum.
     */
    private static double[] interval(Map<String, JsonObject> sides, String axis) {
        JsonObject negative = sides.get("-" + axis);
        JsonObject positive = sides.get("+" + axis);
        if (!blocked("-" + axis, negative) || !blocked("+" + axis, positive)) {
            return null;
        }
        return new double[]{snap(negative.get("rel").getAsDouble() + HALF_WIDTH), snap(positive.get("rel").getAsDouble() - HALF_WIDTH)};
    }

    static double snap(double value) {
        double snapped = Math.round(value * SNAP) / SNAP;
        if (Math.abs(snapped - value) > SNAP_TOLERANCE) {
            throw new IllegalStateException(value + " is not a contact position (" + snapped + " is the nearest)");
        }
        return snapped;
    }

    /** @return {minX, maxX, minZ, maxZ} of a block that stopped bodies from all four sides */
    private static double[] solidBox(Map<String, Map<String, JsonObject>> halves) {
        Map<String, JsonObject> sides = halves.get("lower|");
        double[] x = interval(sides, "x");
        double[] z = interval(sides, "z");
        if (x == null || z == null) {
            throw new IllegalStateException("The block did not stop bodies from all sides: " + sides);
        }
        return new double[]{x[0], x[1], z[0], z[1]};
    }

    /**
     * Every door in every state, both halves: a door panel is thin on one axis, so exactly one axis stops bodies from both
     * sides, and its interval is the panel's. The other axis (the length of the panel) and the height are not measured:
     * the body is 0.5 wide and 0.5 tall.
     */
    private static JsonObject doors(ProbeLog log) {
        JsonObject result = new JsonObject();
        Map<String, JsonArray> unmeasured = new TreeMap<>();
        Map<String, Map<String, Map<String, JsonObject>>> cells = cells(log);
        for (Map.Entry<String, Map<String, Map<String, JsonObject>>> block : new TreeMap<>(cells).entrySet()) {
            if (!block.getKey().endsWith("_door")) {
                continue;
            }
            JsonArray measured = new JsonArray();
            JsonArray failed = new JsonArray();
            for (Map.Entry<String, Map<String, JsonObject>> cell : block.getValue().entrySet()) {
                Map<String, JsonObject> sides = cell.getValue();
                double[] x = interval(sides, "x");
                double[] z = interval(sides, "z");
                String states = cell.getKey().substring(cell.getKey().indexOf('|') + 1);
                if ((x == null) == (z == null)) {
                    failed.add(states);
                    continue;
                }
                JsonObject entry = new JsonObject();
                JsonObject stateObject = new JsonObject();
                JsonObject first = sides.values().iterator().next().getAsJsonObject("state");
                for (String name : DOOR_STATES) {
                    stateObject.add(name, first.get(name));
                }
                entry.add("states", stateObject);
                entry.addProperty("axis", x != null ? "x" : "z");
                double[] panel = x != null ? x : z;
                entry.addProperty("min", panel[0]);
                entry.addProperty("max", panel[1]);
                measured.add(entry);
            }
            result.add(block.getKey(), measured);
            if (!failed.isEmpty()) {
                unmeasured.put(block.getKey(), failed);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("No doors in the probe log");
        }
        if (!unmeasured.isEmpty()) {
            throw new IllegalStateException("Doors with states the probe could not measure (no or two blocking axes): " + unmeasured);
        }
        return result;
    }


    // ---- climbing ----

    /** Blocks that must climb in every state, and blocks that must climb in none: the check that the probe measures climbing. */
    static final List<String> CLIMBING = List.of("minecraft:ladder", "minecraft:vine");
    static final List<String> NOT_CLIMBING = List.of("minecraft:stone", "minecraft:air");

    /**
     * Every state of every block type, with a body that can climb ({@code minecraft:can_climb}) at its feet: the states where
     * {@code Entity.isClimbing} was true. The controls (ladder and vine in every state, stone and air in none) must hold, or
     * the measurement says nothing.
     */
    static JsonObject climbing(ProbeLog log, JsonArray defaults) {
        Map<String, JsonObject> blocks = new TreeMap<>(byBlock(log.of("climb")));
        if (blocks.isEmpty()) {
            throw new IllegalStateException("No climb observations in the probe log");
        }
        for (String control : CLIMBING) {
            JsonObject block = blocks.get(control);
            if (block == null || block.getAsJsonArray("climbing").size() != block.get("states").getAsInt()) {
                throw new IllegalStateException("Climbing control failed: " + control + " should climb in every state: " + block);
            }
        }
        for (String control : NOT_CLIMBING) {
            JsonObject block = blocks.get(control);
            if (block == null || !block.getAsJsonArray("climbing").isEmpty()) {
                throw new IllegalStateException("Climbing control failed: " + control + " should climb in no state: " + block);
            }
        }
        JsonObject plain = blocks.get("probe:plain");
        if (plain == null || !plain.getAsJsonArray("climbing").isEmpty()) {
            throw new IllegalStateException("probe:plain should not be climbable (no add-on component sets it): " + plain);
        }
        addDefault(defaults, "block:climbable", new com.google.gson.JsonPrimitive(false), "A body that can climb, at the feet of a custom "
            + "block that sets no component: Entity.isClimbing was false, as in stone and air (and true in a ladder and a vine). No add-on "
            + "component sets it.");

        JsonObject result = new JsonObject();
        result.addProperty("method", "Every state of every block type in its own cell (barriers around it), a body with minecraft:can_climb, "
            + "no wall climbing and no gravity at its feet; Entity.isClimbing after 3 ticks. Controls: " + CLIMBING + " climb in every state, "
            + NOT_CLIMBING + " in none");
        JsonArray probed = new JsonArray();
        JsonObject climbing = new JsonObject();
        JsonObject lost = new JsonObject();
        int states = 0;
        for (Map.Entry<String, JsonObject> block : blocks.entrySet()) {
            probed.add(block.getKey());
            states += block.getValue().get("states").getAsInt();
            if (block.getValue().has("lost") && !block.getValue().getAsJsonArray("lost").isEmpty()) {
                lost.add(block.getKey(), block.getValue().getAsJsonArray("lost"));
            }
            if (!block.getValue().getAsJsonArray("climbing").isEmpty()) {
                JsonObject entry = new JsonObject();
                entry.add("states", block.getValue().get("states"));
                entry.add("climbing", block.getValue().getAsJsonArray("climbing"));
                climbing.add(block.getKey(), entry);
            }
        }
        result.addProperty("states", states);
        result.add("probed", probed);
        result.add("climbing", climbing);
        // states after which the body was gone (a block removed it): not measured
        result.add("lost", lost);
        return result;
    }
}
