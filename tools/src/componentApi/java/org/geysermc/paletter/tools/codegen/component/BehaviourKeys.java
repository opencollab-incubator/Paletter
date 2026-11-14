package org.geysermc.paletter.tools.codegen.component;

import java.util.List;

/**
 * The behaviour keys ({@code org.geysermc.paletter.behaviour.BlockBehaviour} and {@code ItemBehaviour}): the one hand-kept
 * list {@link BehaviourSource} generates the key constants and the runtime {@code WireRules} from.
 * <p>
 * A key that custom content sets through an add-on component names it with the wire leaf the key is read from; the
 * rule onto that leaf comes from {@code data/model/mappings.json}, so generation fails when the leaf is renamed or its
 * rule changes. The shapes are read by hand ({@code org.geysermc.paletter.internal.behaviour.ShapeRules}): no inferred
 * rule explains the boxes yet.
 */
public final class BehaviourKeys {
    /** Whose property a key is. */
    public enum Owner {
        BLOCK,
        ITEM
    }

    /**
     * @param name the name of the key, also its key in the bundled data
     * @param type the simple name of the value type: {@code Float}, {@code Integer}, {@code Boolean}, {@code String} or
     * {@code BlockShape}
     * @param doc the Javadoc of the constant, or empty
     * @param wire how custom content sets the key, or null if no component does
     */
    public record Key(Owner owner, String name, String type, String doc, Wire wire) {
    }

    /** How custom content sets a key. */
    public sealed interface Wire {
        /** @return the add-on components a user sets to set the key */
        List<String> components();
    }

    /**
     * A key read from one wire leaf the add-on component writes.
     *
     * @param component the add-on component, e.g. {@code minecraft:friction}
     * @param field the field of the component that writes the leaf (as in {@code mappings.json}); empty for the value of
     * the component itself (a number or boolean form)
     * @param leaf the wire leaf, {@code <wire component>.<field>}
     */
    public record Leaf(String component, String field, String leaf) implements Wire {
        @Override
        public List<String> components() {
            return List.of(component);
        }
    }

    /**
     * A key read by a hand-written method of {@code ShapeRules}.
     *
     * @param method the public static method of {@code ShapeRules} that takes the properties
     * @param components the add-on components the method reads
     */
    public record Hand(String method, List<String> components) implements Wire {
    }

    static final String BLOCK_DOC = """
        The behaviour keys of block states. Values are per state and per data version; where they come from is in
        {@link Behaviour#provenance}. Units are those of Bedrock Dedicated Server's engine, not of add-on components (custom
        blocks are converted, see {@link Behaviour}).""";
    static final String ITEM_DOC = "The behaviour keys of item types. Values are per item type (not per aux value) and per data version.";

    /** Every key, in the order of {@code keys()}. */
    public static final List<Key> KEYS = List.of(
        block("destroy_time", "Float", "Destroy time (\"hardness\") in seconds with bare hands before tool and effect factors; -1 for unbreakable blocks.",
            new Leaf("minecraft:destructible_by_mining", "seconds_to_destroy", "minecraft:destructible_by_mining.value")),
        block("explosion_resistance", "Float", "", null),
        block("friction", "Float", "Friction of an entity standing on the block: 0.6 for most blocks, 0.98 ice, 0.989 blue ice.",
            new Leaf("minecraft:friction", "", "minecraft:friction.value")),
        block("solid", "Boolean", "", null),
        block("collision_shape", "BlockShape", "The boxes entities collide with, without neighbours or position offsets (see {@link BlockShape}).",
            new Hand("collisionShape", List.of("minecraft:collision_box", "minecraft:transformation"))),
        block("outline_shape", "BlockShape", "The selection outline, one box, without the random offset some plants have.",
            new Hand("outlineShape", List.of("minecraft:selection_box", "minecraft:transformation"))),
        block("light_emission", "Integer", "", new Leaf("minecraft:light_emission", "", "minecraft:light_emission.emission")),
        block("light_dampening", "Integer", "", new Leaf("minecraft:light_dampening", "", "minecraft:light_dampening.lightLevel")),
        block("can_contain_liquid", "Boolean", "Whether a liquid source can share the block (waterlogging).", null),
        block("liquid_reaction", "String", "What flowing liquid does to the block: {@code BLOCKING}, {@code BROKEN}, {@code POPPED}, {@code NO_REACTION}.", null),
        block("requires_correct_tool", "Boolean", "", null),
        block("map_color", "Integer", "Map colour as {@code 0xRRGGBBAA}.", null),
        block("tint_method", "String", "Biome tint of the texture, e.g. {@code None}, {@code Grass}, {@code Foliage}.", null),
        block("flame_odds", "Integer", "Chance modifier of catching fire.", null),
        block("burn_odds", "Integer", "Chance modifier of burning away.", null),
        block("translucency", "Float", "", null),
        block("thickness", "Float", "", null),
        block("climbable", "Boolean", "Whether entities climb the block. No bundled source yet (needs the native capture): always null.", null),

        item("max_stack_size", "Integer", "", new Leaf("minecraft:max_stack_size", "value", "item_properties.max_stack_size")),
        item("max_damage", "Integer", "Durability; 0 for items that take no damage.",
            new Leaf("minecraft:durability", "max_durability", "minecraft:durability.max_durability")),
        item("attack_damage", "Integer", "", new Leaf("minecraft:damage", "value", "item_properties.damage")),
        item("armor_value", "Integer", "", null),
        item("toughness", "Integer", "", null),
        item("enchant_value", "Integer", "", null),
        item("furnace_burn_duration", "Integer", "Ticks the item burns in a furnace; 0 if it is no fuel.", null),
        item("furnace_xp_multiplier", "Float", "", null),
        item("use_duration", "Integer", "Ticks of using the item (eating, drinking, ...). Known for component based items (from what BDS sends); null for\n"
            + "items whose use is native code (bow, trident, potion) until the native capture exists.",
            new Leaf("minecraft:use_modifiers", "use_duration", "item_properties.use_duration")),
        item("use_animation", "Integer", "The use animation id BDS sends ({@code item_properties.use_animation}: 1 eat, 2 drink, 6 spear, ...).",
            new Leaf("minecraft:use_animation", "value", "item_properties.use_animation")));

    private BehaviourKeys() {
    }

    private static Key block(String name, String type, String doc, Wire wire) {
        return new Key(Owner.BLOCK, name, type, doc, wire);
    }

    private static Key item(String name, String type, String doc, Wire wire) {
        return new Key(Owner.ITEM, name, type, doc, wire);
    }
}
