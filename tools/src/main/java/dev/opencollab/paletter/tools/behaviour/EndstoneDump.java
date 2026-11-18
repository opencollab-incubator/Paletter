package dev.opencollab.paletter.tools.behaviour;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.BlockShape;
import dev.opencollab.paletter.behaviour.ItemBehaviour;

import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads the JSON a native BDS dump in the Endstone DevTools schema writes: block states (an array of objects keyed by
 * {@code blockStateHash}) and items (an object keyed by identifier). CloudburstMC/Data ({@code blocks.json}, earlier
 * {@code block_properties.json}) and AllayMC/Allay ({@code unpacked/block_states_raw.json}, {@code items_raw.json})
 * publish this schema; CloudburstMC/Data moved the shapes under {@code geometry} in October 2026, both forms are read.
 * <p>
 * The tables below are the whole mapping from source fields to keys; any other field is reported as unmapped.
 */
final class EndstoneDump {
    private static final Map<String, Field> BLOCK_FIELDS = Map.ofEntries(
        Map.entry("hardness", new Field(BlockBehaviour.DESTROY_TIME, EndstoneDump::toFloat)),
        Map.entry("explosionResistance", new Field(BlockBehaviour.EXPLOSION_RESISTANCE, EndstoneDump::toFloat)),
        Map.entry("friction", new Field(BlockBehaviour.FRICTION, EndstoneDump::toFloat)),
        Map.entry("isSolid", new Field(BlockBehaviour.SOLID, JsonElement::getAsBoolean)),
        Map.entry("lightEmission", new Field(BlockBehaviour.LIGHT_EMISSION, JsonElement::getAsInt)),
        Map.entry("lightDampening", new Field(BlockBehaviour.LIGHT_DAMPENING, JsonElement::getAsInt)),
        Map.entry("canContainLiquidSource", new Field(BlockBehaviour.CAN_CONTAIN_LIQUID, JsonElement::getAsBoolean)),
        Map.entry("requiresCorrectToolForDrops", new Field(BlockBehaviour.REQUIRES_CORRECT_TOOL, JsonElement::getAsBoolean)),
        Map.entry("mapColor", new Field(BlockBehaviour.MAP_COLOR, e -> Integer.parseUnsignedInt(e.getAsString().substring(1), 16))),
        Map.entry("tintMethod", new Field(BlockBehaviour.TINT_METHOD, JsonElement::getAsString)),
        Map.entry("flameOdds", new Field(BlockBehaviour.FLAME_ODDS, JsonElement::getAsInt)),
        Map.entry("burnOdds", new Field(BlockBehaviour.BURN_ODDS, JsonElement::getAsInt)),
        Map.entry("translucency", new Field(BlockBehaviour.TRANSLUCENCY, EndstoneDump::toFloat)),
        Map.entry("thickness", new Field(BlockBehaviour.THICKNESS, EndstoneDump::toFloat)),
        Map.entry("collisionShape", new Field(BlockBehaviour.COLLISION_SHAPE, e -> boxes(e.getAsJsonArray()))),
        Map.entry("outlineShape", new Field(BlockBehaviour.OUTLINE_SHAPE, EndstoneDump::box)),
        Map.entry("geometry.collision", new Field(BlockBehaviour.COLLISION_SHAPE, e -> boxes(e.getAsJsonObject().getAsJsonArray("boxes")))),
        // model_base outlines (plants with a random offset) give the box before the offset
        Map.entry("geometry.outline", new Field(BlockBehaviour.OUTLINE_SHAPE, e -> box(e.getAsJsonObject().get("box"))))
    );
    /**
     * {@code liquidReactionOnTouch}, the source's name for BDS's {@code on_liquid_touches} reaction, as the three booleans the
     * Script API gives (liquid_blocking, destroyed_by_liquid, liquid_spread_causes_spawn). Read off the Script API dump of BDS
     * 1.26.63.1 against CloudburstMC/Data of the same version, where every block agrees; any state that does not shows under
     * Disagreements in the report.
     */
    private static final Map<String, List<Boolean>> LIQUID_REACTIONS = Map.of(
        "BLOCKING", List.of(true, false, false),
        "BROKEN", List.of(false, true, false),
        "POPPED", List.of(false, false, true),
        "NOREACTION", List.of(false, false, false));

    /** Fields that are read elsewhere or deliberately not: identity, and shapes older dumps filled with garbage. */
    private static final Set<String> BLOCK_IGNORED = Set.of("name", "blockStateHash", "translationKey", "visualShape",
        "uiShape", "liquidClipShape", "geometry.visual", "geometry.ui", "geometry.liquidClip", "geometry.modelPosition");

    private static final Map<String, Field> ITEM_FIELDS = Map.of(
        "maxStackSize", new Field(ItemBehaviour.MAX_STACK_SIZE, JsonElement::getAsInt),
        "maxDamage", new Field(ItemBehaviour.MAX_DAMAGE, JsonElement::getAsInt),
        "attackDamage", new Field(ItemBehaviour.ATTACK_DAMAGE, JsonElement::getAsInt),
        "armorValue", new Field(ItemBehaviour.ARMOR_VALUE, JsonElement::getAsInt),
        "toughnessValue", new Field(ItemBehaviour.TOUGHNESS, JsonElement::getAsInt),
        "enchantValue", new Field(ItemBehaviour.ENCHANT_VALUE, JsonElement::getAsInt),
        "furnaceBurnDuration", new Field(ItemBehaviour.FURNACE_BURN_DURATION, EndstoneDump::wholeNumber),
        "furnaceXPMultiplier", new Field(ItemBehaviour.FURNACE_XP_MULTIPLIER, EndstoneDump::toFloat)
    );
    private static final Set<String> ITEM_IGNORED = Set.of("id", "isDamageable", "tags");

    private record Field(BehaviourKey<?> key, Function<JsonElement, Object> read) {
    }

    private EndstoneDump() {
    }

    /**
     * @return true if the JSON is an array of block state objects with a {@code blockStateHash} (the file names stayed,
     * the content changed: CloudburstMC/Data's {@code block_properties.json} held data driven block definitions once)
     */
    static boolean isBlockDump(Reader reader) throws IOException {
        JsonReader json = new JsonReader(reader);
        if (json.peek() != JsonToken.BEGIN_ARRAY) {
            return false;
        }
        json.beginArray();
        return json.hasNext() && JsonParser.parseReader(json).getAsJsonObject().has("blockStateHash");
    }

    static Values<Integer> blocks(Reader reader) throws IOException {
        Values<Integer> values = new Values<>();
        JsonReader json = new JsonReader(reader);
        json.beginArray();
        while (json.hasNext()) {
            JsonObject state = JsonParser.parseReader(json).getAsJsonObject();
            int hash = (int) state.get("blockStateHash").getAsLong();
            for (Map.Entry<String, JsonElement> entry : state.entrySet()) {
                if (entry.getKey().equals("geometry")) {
                    for (Map.Entry<String, JsonElement> part : entry.getValue().getAsJsonObject().entrySet()) {
                        if (part.getKey().equals("shapeSource")) {
                            // The dump's author says the shapes of this state were not read from BDS as they are
                            values.reportsPostProcessing.add(BlockBehaviour.COLLISION_SHAPE);
                            values.reportsPostProcessing.add(BlockBehaviour.OUTLINE_SHAPE);
                            values.postProcessed(BlockBehaviour.COLLISION_SHAPE, hash, "shapeSource: " + part.getValue().getAsString());
                            values.postProcessed(BlockBehaviour.OUTLINE_SHAPE, hash, "shapeSource: " + part.getValue().getAsString());
                        } else {
                            read(values, hash, "geometry." + part.getKey(), part.getValue(), BLOCK_FIELDS, BLOCK_IGNORED);
                        }
                    }
                } else {
                    read(values, hash, entry.getKey(), entry.getValue(), BLOCK_FIELDS, BLOCK_IGNORED);
                }
            }
        }
        json.endArray();
        return values;
    }

    static Values<String> items(Reader reader) {
        Values<String> values = new Values<>();
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        for (Map.Entry<String, JsonElement> item : root.entrySet()) {
            for (Map.Entry<String, JsonElement> entry : item.getValue().getAsJsonObject().entrySet()) {
                read(values, item.getKey(), entry.getKey(), entry.getValue(), ITEM_FIELDS, ITEM_IGNORED);
            }
        }
        return values;
    }

    private static <I> void read(Values<I> values, I id, String name, JsonElement value, Map<String, Field> fields, Set<String> ignored) {
        if (name.equals("liquidReactionOnTouch") && fields == BLOCK_FIELDS) {
            List<Boolean> reaction = LIQUID_REACTIONS.get(value.getAsString());
            if (reaction == null) {
                throw new IllegalArgumentException("Unknown liquidReactionOnTouch " + value + ": add it to LIQUID_REACTIONS from a Script API dump");
            }
            values.put(BlockBehaviour.LIQUID_BLOCKING, id, reaction.get(0));
            values.put(BlockBehaviour.DESTROYED_BY_LIQUID, id, reaction.get(1));
            values.put(BlockBehaviour.LIQUID_SPREAD_CAUSES_SPAWN, id, reaction.get(2));
            return;
        }
        Field field = fields.get(name);
        if (field != null) {
            if (!value.isJsonNull()) {
                values.put(field.key(), id, field.read().apply(value));
            }
        } else if (!ignored.contains(name)) {
            values.unmapped(name);
        }
    }

    private static Object toFloat(JsonElement element) {
        return decode(element.getAsDouble());
    }

    /**
     * The float a printed number stands for. Dumps print floats either exactly (the shortest or the full double form) or,
     * since CloudburstMC/Data's and Allay's 26.50 dumps for destroy times and shapes, truncated to six decimals
     * ({@code 1.8f} = 1.7999999523 printed as {@code 1.799999}, {@code 0.7f} as {@code 0.699999}). A number that no
     * float truncates to but the float one millionth further from zero does is read as that float, and so is one both
     * floats truncate to if that float is the shorter decimal ({@code 0.899999} is {@code 0.9f}); checked against what
     * BDS sends for the data driven blocks ({@code WireCrossCheckTest} in paletter).
     */
    static float decode(double printed) {
        float direct = (float) printed;
        float next = (float) (printed + Math.copySign(1e-6, printed));
        if (!truncatesTo(next, printed) || (double) direct == printed) {
            return direct;
        }
        // Both can be meant (0.899999 is 0.899999f and 0.9f truncated): the float with the shorter decimal form
        return !truncatesTo(direct, printed) || Float.toString(next).length() < Float.toString(direct).length() ? next : direct;
    }

    private static boolean truncatesTo(float value, double printed) {
        return (double) value == printed
            || new java.math.BigDecimal(value).setScale(6, java.math.RoundingMode.DOWN).compareTo(java.math.BigDecimal.valueOf(printed)) == 0;
    }

    private static Object wholeNumber(JsonElement element) {
        double value = element.getAsDouble();
        if (value != Math.rint(value)) {
            throw new IllegalArgumentException("Expected a whole number, got " + value);
        }
        return (int) value;
    }

    /** {@code [[minX, minY, minZ, maxX, maxY, maxZ], ...]} */
    static BlockShape boxes(JsonArray boxes) {
        float[] coordinates = new float[boxes.size() * 6];
        for (int i = 0; i < boxes.size(); i++) {
            List<JsonElement> box = boxes.get(i).getAsJsonArray().asList();
            for (int j = 0; j < 6; j++) {
                coordinates[i * 6 + j] = decode(box.get(j).getAsDouble());
            }
        }
        return BlockShape.of(coordinates);
    }

    /** {@code [minX, minY, minZ, maxX, maxY, maxZ]}; an all zero box is no outline */
    static BlockShape box(JsonElement box) {
        JsonArray array = new JsonArray();
        array.add(box);
        BlockShape shape = boxes(array);
        return shape.boxes().getFirst().equals(new BlockShape.Box(0, 0, 0, 0, 0, 0)) ? BlockShape.EMPTY : shape;
    }
}
