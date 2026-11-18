package org.geysermc.paletter.tools.behaviour;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.behaviour.ItemBehaviour;
import org.geysermc.paletter.tools.Layout;
import org.geysermc.paletter.tools.bds.BdsDistribution;
import org.geysermc.paletter.tools.bds.BdsRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code food-probe}: runs the food probe add-on ({@code data/addons/food-probe}, hand-written) on a BDS with the Beta APIs: a
 * simulated player tries to eat every item type, hungry and full, and the add-on prints what happened to its food level and
 * saturation and what the use events reported. This class draws the conclusions and writes
 * {@code data/bds/<version>/food-probe/}: {@code probe.log} (the lines of the BDS console, verbatim) and {@code capture.json}
 * (nutrition, saturation modifier, can always eat and use duration per item). {@code behaviour import} reads the capture
 * ({@link BehaviourImport#foodProbe}).
 * <p>
 * The probe is not essential. If its controls (two custom food items with known values) do not read as set, the capture says
 * {@code {"unverified": reason}} and the importer takes nothing from it; an item a trial does not decide is {@code unclear}.
 */
public final class FoodProbe {
    static final String DIRECTORY = "food-probe";
    static final String CAPTURE = "capture.json";
    static final String LOG = "probe.log";
    /** The directory of the add-on in the {@code behavior_packs} of the server it ran on. */
    public static final String PACK = "paletter_food_probe";

    /** Custom food items whose components the add-on sets: item, nutrition, saturation modifier, use duration in ticks, can always eat. */
    record Control(String item, int nutrition, double saturationModifier, int useDuration, boolean canAlwaysEat) {
    }

    static final List<Control> CONTROLS = List.of(new Control("probe:control_food", 4, 0.3, 32, false),
        new Control("probe:control_snack", 2, 0.6, 16, true));
    private static final int MAX_FOOD_LEVEL = 20;
    private static final double MODIFIER_TOLERANCE = 0.005;

    private FoodProbe() {
    }

    /**
     * Runs the probe and writes the console to {@code log}.
     *
     * @param betaModule the version of the beta {@code @minecraft/server} module the add-on depends on ({@code ScriptModules})
     * @param server an extracted BDS, or null to use the build's server in the cache
     */
    public static void run(Path root, String bdsVersion, boolean preview, String betaModule, Path server, Path cache, int port, int timeout, Path log)
        throws IOException, InterruptedException {
        Files.createDirectories(log.toAbsolutePath().getParent());
        BdsRunner runner = new BdsRunner(root.resolve(Layout.FOOD_PROBE_ADDON), "PROBE", PACK).withBetaApis(betaModule)
            .withBinaryPackFiles(Map.of(FoodPlatform.PATH, FoodPlatform.bytes()));
        if (server != null) {
            runner.run(server, log, timeout, port, false);
        } else {
            BdsDistribution.resolve(preview, bdsVersion).onServer(cache == null ? BdsDistribution.defaultCache() : cache,
                extracted -> runner.run(extracted, log, timeout, port, false));
        }
    }

    /**
     * What a run kept for its BDS build.
     *
     * @param version the BDS version the probe reported
     * @param build its Build ID
     * @param log the content of {@link #LOG}
     * @param capture the content of {@link #CAPTURE}
     */
    public record Measured(String version, String build, String log, String capture) {
    }

    /** Measures a console log of the probe. */
    public static Measured fromConsole(Path console) throws IOException {
        ProbeLog log = ProbeLog.parse(Files.readAllLines(console, StandardCharsets.UTF_8));
        return new Measured(log.version(), log.build(), String.join("\n", log.extract()) + "\n", Json.pretty(measure(log)));
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

    /** Measures a console log of the probe and writes the capture next to the other captures of its BDS build. */
    public static Path write(Path root, Path console) throws IOException {
        Measured measured = fromConsole(console);
        Path directory = root.resolve(Layout.BDS).resolve(measured.version()).resolve(DIRECTORY);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(LOG), measured.log());
        Files.writeString(directory.resolve(CAPTURE), measured.capture());
        System.out.println("Wrote " + directory.resolve(CAPTURE) + " from BDS " + measured.version() + " (Build ID " + measured.build() + ")");
        return directory;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // From observations to values

    static JsonObject measure(ProbeLog log) {
        JsonObject capture = new JsonObject();
        capture.addProperty("generated", "Generated by `food-probe` (tools, FoodProbe) from probe.log, the console of the add-on "
            + Layout.FOOD_PROBE_ADDON + " on this BDS build. Do not edit: run the command again.");
        JsonObject bds = new JsonObject();
        bds.addProperty("version", log.version());
        bds.addProperty("build", log.build());
        capture.add("bds", bds);
        capture.addProperty("log", LOG);
        Map<String, JsonObject> trials = new LinkedHashMap<>();
        log.optional("food").forEach(food -> trials.put(food.get("item").getAsString(), food));
        try {
            capture.addProperty("method", "A simulated player holds the item and uses it, with a food level of 10 and a saturation of 0 (hungry) and "
                + "with a food level of 20 (full). use_duration is what the item-start-use event reported, in ticks; nutrition is the food "
                + "level the player gained when the use completed; the saturation modifier is the saturation gained divided by twice the "
                + "nutrition, to 3 decimals, where the saturation stayed below the food level (else it was capped, and the item is unclear); "
                + "can_always_eat is whether a full player could use the food. The custom items probe:control_food and probe:control_snack, "
                + "whose components the add-on sets, read back as set.");
            capture.add("controls", controls(trials));
            JsonObject items = new JsonObject();
            JsonObject unclear = new JsonObject();
            for (JsonObject trial : new TreeMap<>(trials).values()) {
                item(trial, items, unclear);
            }
            capture.add("items", items);
            capture.add("unclear", unclear);
        } catch (IllegalStateException e) {
            capture.remove("controls");
            capture.addProperty(StateProbe.UNVERIFIED, e.getMessage());
        }
        return capture;
    }

    private static JsonObject controls(Map<String, JsonObject> trials) {
        JsonObject result = new JsonObject();
        for (Control control : CONTROLS) {
            JsonObject trial = trials.get(control.item());
            if (trial == null) {
                throw new IllegalStateException("control " + control.item() + " was not tried");
            }
            JsonObject values = new JsonObject();
            JsonObject unclear = new JsonObject();
            item(trial, values, unclear);
            if (!unclear.isEmpty() || !values.has(control.item())) {
                throw new IllegalStateException("control " + control.item() + " is unclear: " + unclear + " " + trial);
            }
            JsonObject read = values.getAsJsonObject(control.item());
            if (read.get("nutrition").getAsInt() != control.nutrition()
                || Math.abs(read.get("saturation_modifier").getAsDouble() - control.saturationModifier()) > MODIFIER_TOLERANCE
                || read.get("use_duration").getAsInt() != control.useDuration()
                || read.get("can_always_eat").getAsBoolean() != control.canAlwaysEat()) {
                throw new IllegalStateException("control " + control.item() + " reads " + read + ", not the nutrition " + control.nutrition()
                    + ", saturation modifier " + control.saturationModifier() + ", use duration " + control.useDuration() + " and can always eat "
                    + control.canAlwaysEat() + " it sets");
            }
            result.add(control.item(), read);
        }
        return result;
    }

    /** Adds what a trial decides about its item to {@code items}, or why it does not to {@code unclear}. */
    static void item(JsonObject observation, JsonObject items, JsonObject unclear) {
        String item = observation.get("item").getAsString();
        JsonObject hungry = observation.getAsJsonObject("hungry");
        JsonObject full = observation.getAsJsonObject("full");
        JsonObject values = new JsonObject();
        Integer hungryDuration = duration(hungry);
        Integer fullDuration = duration(full);
        if (hungryDuration != null && fullDuration != null && !hungryDuration.equals(fullDuration)) {
            unclear.addProperty(item, "the use duration was " + hungryDuration + " hungry and " + fullDuration + " full");
            return;
        }
        if (hungry.get("started").getAsBoolean() && hungryDuration == null || full.get("started").getAsBoolean() && fullDuration == null) {
            unclear.addProperty(item, "the use started but the event reported no duration");
            return;
        }
        if (hungryDuration != null || fullDuration != null) {
            values.addProperty("use_duration", hungryDuration != null ? hungryDuration : fullDuration);
        }
        if (hungry.get("completed").getAsBoolean()) {
            double[] hunger = pair(hungry, "hunger");
            double[] saturation = pair(hungry, "saturation");
            double gained = hunger[1] - hunger[0];
            if (gained > 0) {
                if (hunger[1] >= MAX_FOOD_LEVEL) {
                    unclear.addProperty(item, "the food level reached " + MAX_FOOD_LEVEL + ": the nutrition may be more than it gained");
                    return;
                }
                int nutrition = (int) Math.round(gained);
                values.addProperty("nutrition", nutrition);
                if (saturation[1] < hunger[1]) {
                    values.addProperty("saturation_modifier", Math.round((saturation[1] - saturation[0]) / (2.0 * nutrition) * 1000) / 1000.0);
                } else {
                    unclear.addProperty(item, "the saturation was capped by the food level, the modifier is not known");
                }
                values.addProperty("can_always_eat", full.get("started").getAsBoolean());
            }
        }
        if (!values.isEmpty()) {
            items.add(item, values);
        }
    }

    /** @return the use duration a started trial reported, null if it did not start or reported none */
    private static Integer duration(JsonObject trial) {
        JsonElement duration = trial.get("useDuration");
        return trial.get("started").getAsBoolean() && duration != null && !duration.isJsonNull() ? duration.getAsInt() : null;
    }

    private static double[] pair(JsonObject trial, String name) {
        List<JsonElement> values = trial.getAsJsonArray(name).asList();
        return new double[]{values.get(0).getAsDouble(), values.get(1).getAsDouble()};
    }

    /** The values of the capture's items for the importer, by item identifier; none if the capture is unverified. */
    static Values<String> items(JsonObject capture) {
        Values<String> values = new Values<>();
        if (capture.has(StateProbe.UNVERIFIED)) {
            return values;
        }
        for (Map.Entry<String, JsonElement> entry : capture.getAsJsonObject("items").entrySet()) {
            if (entry.getKey().startsWith("probe:")) {
                continue; // the controls: the add-on's own items
            }
            JsonObject item = entry.getValue().getAsJsonObject();
            if (item.has("use_duration")) {
                values.put(ItemBehaviour.USE_DURATION, entry.getKey(), item.get("use_duration").getAsInt());
            }
            if (item.has("nutrition")) {
                values.put(ItemBehaviour.NUTRITION, entry.getKey(), item.get("nutrition").getAsInt());
                values.put(ItemBehaviour.CAN_ALWAYS_EAT, entry.getKey(), item.get("can_always_eat").getAsBoolean());
            }
            if (item.has("saturation_modifier")) {
                values.put(ItemBehaviour.SATURATION_MODIFIER, entry.getKey(), item.get("saturation_modifier").getAsFloat());
            }
        }
        return values;
    }
}
