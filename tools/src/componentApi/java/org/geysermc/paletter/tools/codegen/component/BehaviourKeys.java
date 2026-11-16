package org.geysermc.paletter.tools.codegen.component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The behaviour keys ({@code org.geysermc.paletter.behaviour.BlockBehaviour} and {@code ItemBehaviour}): a naming table
 * from Paletter's key to the BDS property it is, which {@link BehaviourSource} generates the key constants and the runtime
 * {@code WireRules} from, and {@code behaviour import} (tools) reads the values with.
 * <p>
 * A key names the BDS property it is (its sources, strongest first), never a guess:
 * <ul>
 *     <li>{@code SAPI:<Class>.<member>}: what the Script API says, as {@code data/addons/behaviour-dump} names it (e.g.
 *     {@code SAPI:Block.isSolid}, {@code SAPI:BlockPermutation.canContainLiquid(Water)}); checked against the newest
 *     {@code data/bds/<build>/behaviour-dump.json.gz}, and a property its world check found world-dependent is only allowed
 *     as a later source, for the blocks the check did not find depending on the world;</li>
 *     <li>{@code PROBE:<name>}: what {@code behaviour-probe} measured (a member of the newest {@code behaviour-probe/capture.json});</li>
 *     <li>{@code NATIVE:<Type>.<member>}: a field or virtual of BDS's own classes (names from the LeviLamina headers, the
 *     binary has no member names). No Paletter capture reads these yet: a key with only a native source is filled from the
 *     third-party native dumps ({@code behaviour import}: CloudburstMC/Data, AllayMC/Allay), which the report labels.</li>
 * </ul>
 * Generation fails when a source is not in its newest capture, and when a property of the newest behaviour dump has neither a
 * key nor an entry in {@link #IGNORED}: new BDS properties are looked at, not silently dropped.
 * <p>
 * A key that custom content sets through an add-on component names it with the wire leaf the key is read from; the rule onto
 * that leaf comes from {@code data/model/mappings.json}, so generation fails when the leaf is renamed or its rule changes.
 * The shapes are read by hand ({@code org.geysermc.paletter.internal.behaviour.ShapeRules}).
 */
public final class BehaviourKeys {
    /** Whose property a key is. */
    public enum Owner {
        BLOCK,
        ITEM
    }

    /** How a key's value is made from its sources' values. */
    public enum Conversion {
        /** The value as BDS gives it (the first source with a value). */
        NONE,
        /** An {@code RGBA} of the Script API (floats 0 to 1, each a multiple of 1/255) as the int {@code 0xRRGGBBAA}. */
        RGBA,
        /**
         * The source is asked per {@link #DIRECTIONS direction} ({@code {direction}} in its name): bit {@code i} is set when the
         * answer for direction {@code i} is true.
         */
        DIRECTION_BITS,
        /** An item without the component (the source has no value) has 0: the Script API's durability of an item that takes none. */
        ABSENT_ZERO
    }

    /** The directions of {@link Conversion#DIRECTION_BITS}, bit 0 first: Bedrock's facing order. */
    public static final List<String> DIRECTIONS = List.of("Down", "Up", "North", "South", "West", "East");

    /**
     * @param name the name of the key, also its key in the bundled data
     * @param type the simple name of the value type: {@code Float}, {@code Integer}, {@code Boolean}, {@code String} or
     * {@code BlockShape}
     * @param doc the Javadoc of the constant, or empty
     * @param sources the BDS properties the key is, strongest first (see the class)
     * @param wire how custom content sets the key, or null if no component does
     */
    public record Key(Owner owner, String name, String type, String doc, List<String> sources, Conversion conversion, Wire wire) {
        /** @return the sources with the prefix (e.g. {@code SAPI:}), without it, strongest first */
        public List<String> sources(String prefix) {
            return sources.stream().filter(source -> source.startsWith(prefix)).map(source -> source.substring(prefix.length())).toList();
        }
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
     * @param leaf the wire leaf, {@code <wire component>.<field>}; null for the one leaf the field's value rules write
     */
    public record Leaf(String component, String field, String leaf) implements Wire {
        /** A key read from the one wire leaf the field's value rules write ({@code mappings.json}). */
        public Leaf(String component, String field) {
            this(component, field, null);
        }

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
        The behaviour keys of block states, read with {@code palette.blockBehaviour(state).get(key)} ({@link BlockValues}).
        Values are per state and per data version; {@link BlockValues#explain} says where one comes from. Each key is a property
        of Bedrock Dedicated Server (its source is named on the key), in BDS's units, not those of add-on components: custom
        blocks are converted (the component {@code minecraft:friction} 0.4 is a friction of 0.6).""";
    static final String ITEM_DOC = """
        The behaviour keys of item types, read with {@code palette.itemBehaviour(type).get(key)} ({@link ItemValues}). Values
        are per item type (not per aux value) and per data version. Each key is a property of Bedrock Dedicated Server (its
        source is named on the key).""";

    /** Every key, in the order of {@code keys()}. */
    public static final List<Key> KEYS = List.of(
        block("destroy_time", "Float", "Destroy time (\"hardness\") in seconds with bare hands before tool and effect factors; -1 for unbreakable blocks.",
            List.of("NATIVE:BlockComponentDirectData.mDestroySpeed"),
            new Leaf("minecraft:destructible_by_mining", "seconds_to_destroy")),
        block("explosion_resistance", "Float", "", List.of("NATIVE:BlockComponentDirectData.mExplosionResistance"), null),
        block("friction", "Float", "Friction of an entity standing on the block: 0.6 for most blocks, 0.98 ice, 0.989 blue ice.",
            List.of("NATIVE:BlockComponentDirectData.mFriction"), new Leaf("minecraft:friction", "")),
        block("solid", "Boolean", "Whether the block is solid and impassable ({@code Block.isSolid}: BDS's cached {@code Block::_isSolid()}).",
            List.of("SAPI:Block.isSolid"), null),
        block("collision_shape", "BlockShape", "The boxes entities collide with, without neighbours or position offsets (see {@link BlockShape}).",
            List.of("PROBE:doors", "NATIVE:BlockType.getCollisionShape"),
            new Hand("collisionShape", List.of("minecraft:collision_box", "minecraft:transformation"))),
        block("outline_shape", "BlockShape", "The selection outline, one box, without the random offset some plants have.",
            List.of("NATIVE:BlockType.getOutline"), new Hand("outlineShape", List.of("minecraft:selection_box", "minecraft:transformation"))),
        block("light_emission", "Integer", "", List.of("NATIVE:BlockComponentDirectData.mLightEmission"),
            new Leaf("minecraft:light_emission", "")),
        block("light_dampening", "Integer", "", List.of("NATIVE:BlockComponentDirectData.mLight"),
            new Leaf("minecraft:light_dampening", "")),
        block("can_contain_liquid", "Boolean", "Whether water can share the block (waterlogging).",
            List.of("SAPI:BlockPermutation.canContainLiquid(Water)"), null),
        block("liquid_blocking", "Boolean", "Whether the block stops flowing water.",
            List.of("SAPI:BlockPermutation.isLiquidBlocking(Water)"), null),
        block("destroyed_by_liquid", "Boolean", "Whether flowing water destroys the block (without dropping it).",
            List.of("SAPI:BlockPermutation.canBeDestroyedByLiquidSpread(Water)"), null),
        block("liquid_spread_causes_spawn", "Boolean", "Whether flowing water breaks the block and it drops as an item.",
            List.of("SAPI:BlockPermutation.liquidSpreadCausesSpawn(Water)"), null),
        block("liquid_flow_directions", "Integer", "The directions water can flow into the block from: bit 0 down, 1 up, 2 north, 3 south, "
            + "4 west, 5 east.", List.of("SAPI:Block.liquidCanFlowFromDirection(Water,{direction})"), Conversion.DIRECTION_BITS, null),
        block("requires_correct_tool", "Boolean", "", List.of("NATIVE:BlockType.mRequiresCorrectToolForDrops"), null),
        block("map_color", "Integer", "The base map colour as {@code 0xRRGGBBAA}, before any biome tint ({@link #TINT_METHOD}); the tinted colour "
            + "depends on the biome and is no property of the state.",
            List.of("SAPI:BlockMapColorComponent.color", "SAPI:Block.getMapColor"), Conversion.RGBA, null),
        block("tint_method", "String", "Biome tint of the map colour and texture, e.g. {@code None}, {@code Grass}, {@code DefaultFoliage}.",
            List.of("SAPI:BlockMapColorComponent.tintMethod"), null),
        block("flame_odds", "Integer", "Chance modifier of catching fire.", List.of("NATIVE:BlockComponentDirectData.mFlameOdds"), null),
        block("burn_odds", "Integer", "Chance modifier of burning away.", List.of("NATIVE:BlockComponentDirectData.mBurnOdds"), null),
        block("translucency", "Float", "", List.of("NATIVE:BlockType.mTranslucency"), null),
        block("thickness", "Float", "", List.of("NATIVE:BlockType.mThickness"), null),
        block("climbable", "Boolean", "Whether an entity that can climb ({@code minecraft:can_climb}) climbs in the block, like in a ladder: "
            + "{@code BlockProperty::Climbable} of the block type, measured with {@code Entity.isClimbing}. No add-on component sets it.",
            List.of("PROBE:climbing", "NATIVE:BlockType.mProperties.Climbable"), null),
        block("movement_type", "String", "What a piston does with the block: {@code Push}, {@code PushPull}, {@code Popped} or {@code Immovable}.",
            List.of("SAPI:BlockMovableComponent.movementType"), null),
        block("sticky_type", "String", "Whether the block pulls its neighbours along when moved ({@code Same}, as slime and honey) or not ({@code None}).",
            List.of("SAPI:BlockMovableComponent.stickyType"), null),
        block("accumulates_snow", "Boolean", "", List.of("SAPI:BlockPrecipitationInteractionsComponent.accumulatesSnow"), null),
        block("snow_loggable", "Boolean", "Whether snow can share the block.", List.of("SAPI:BlockPrecipitationInteractionsComponent.isSnowLoggable"), null),
        block("obstructs_rain", "Boolean", "", List.of("SAPI:BlockPrecipitationInteractionsComponent.obstructsRain"), null),
        block("dampens_vibrations", "Boolean", "Whether the block dampens vibrations (wool).",
            List.of("SAPI:BlockVibrationPropertiesComponent.getCanDampenVibrations"), null),
        block("occludes_vibrations", "Boolean", "Whether the block stops vibrations passing through it.",
            List.of("SAPI:BlockVibrationPropertiesComponent.getCanOccludeVibrations"), null),

        item("max_stack_size", "Integer", "", List.of("SAPI:ItemStack.maxAmount"),
            new Leaf("minecraft:max_stack_size", "value", "item_properties.max_stack_size")),
        item("max_damage", "Integer", "Durability; 0 for items that take no damage.",
            List.of("SAPI:ItemDurabilityComponent.maxDurability"), Conversion.ABSENT_ZERO,
            new Leaf("minecraft:durability", "max_durability")),
        item("attack_damage", "Integer", "", List.of("NATIVE:Item.getAttackDamage"), new Leaf("minecraft:damage", "value", "item_properties.damage")),
        item("armor_value", "Integer", "", List.of("NATIVE:Item.getArmorValue"), null),
        item("toughness", "Integer", "", List.of("NATIVE:Item.getToughnessValue"), null),
        item("enchant_value", "Integer", "", List.of("NATIVE:Item.getEnchantValue"), null),
        item("furnace_burn_duration", "Integer", "Ticks the item burns in a furnace; 0 if it is no fuel.",
            List.of("NATIVE:FurnaceBlockActor.getBurnDuration"), null),
        item("furnace_xp_multiplier", "Float", "", List.of("NATIVE:Item.mFurnaceXPmultiplier"), null),
        item("use_duration", "Integer", "Ticks of using the item (eating, drinking, ...). Known for component based items (from what BDS sends); null for\n"
            + "items whose use is native code (bow, trident, potion) until the native capture exists.",
            List.of("NATIVE:Item.mMaxUseDuration"), new Leaf("minecraft:use_modifiers", "use_duration", "item_properties.use_duration")),
        item("use_animation", "Integer", "The use animation id BDS sends ({@code item_properties.use_animation}: 1 eat, 2 drink, 6 spear, ...).",
            List.of("NATIVE:Item.mUseAnim"), new Leaf("minecraft:use_animation", "value", "item_properties.use_animation")),
        item("weight", "Integer", "How much of a bundle one item fills, in 64ths.", List.of("SAPI:ItemStack.weight"), null),
        item("nutrition", "Integer", "Hunger points eating the item restores. The Script API has it only for component based food.",
            List.of("SAPI:ItemFoodComponent.nutrition"), null),
        item("saturation_modifier", "Float", "The saturation modifier of food. The Script API has it only for component based food.",
            List.of("SAPI:ItemFoodComponent.saturationModifier"), null),
        item("can_always_eat", "Boolean", "Whether the food can be eaten when not hungry. The Script API has it only for component based food.",
            List.of("SAPI:ItemFoodComponent.canAlwaysEat"), null),
        item("cooldown_category", "String", "The cooldown category the item shares its cooldown with, e.g. {@code minecraft:ender_pearl}.",
            List.of("SAPI:ItemCooldownComponent.cooldownCategory"), null),
        item("cooldown_ticks", "Integer", "Ticks of cooldown after using the item.", List.of("SAPI:ItemCooldownComponent.cooldownTicks"), null),
        item("compost_chance", "Integer", "Chance in percent that the item adds a layer to a composter; null if it cannot be composted.",
            List.of("SAPI:ItemCompostableComponent.compostingChance"), null));

    /**
     * Properties of the behaviour dump that are deliberately no key, with the reason. Generation fails on a property that is
     * neither a key's source nor listed here, and on an entry the newest dump does not have.
     */
    public static final Map<String, String> IGNORED = ignored(
        "SAPI:Block.isAir", "the block's identity (air)",
        "SAPI:Block.isLiquid", "the block's identity (water and lava blocks)",
        "SAPI:Block.isWaterlogged", "world-dependent: whether water shares the position (the dump's world check)",
        "SAPI:Block.getLightLevel", "world-dependent: the light at the position (the dump's world check)",
        "SAPI:Block.getSkyLightLevel", "world-dependent: the sky light at the position (the dump's world check)",
        "SAPI:Block.getRedstonePower", "the redstone power at the position: world state",
        "SAPI:BlockMapColorComponent.tintedColor", "world-dependent: the map colour tinted by the biome (the dump's world check); map_color is the base colour",
        "SAPI:BlockFluidContainerComponent.fillLevel", "the contents of the block entity (cauldron)",
        "SAPI:BlockFluidContainerComponent.fluidColor", "the contents of the block entity (cauldron)",
        "SAPI:BlockFluidContainerComponent.getFluidType", "the contents of the block entity (cauldron)",
        "SAPI:BlockPistonComponent.isMoving", "the state of the block entity (piston)",
        "SAPI:BlockPistonComponent.state", "the state of the block entity (piston)",
        "SAPI:BlockRecipeCraftingComponent.players", "the players using the block entity (crafter)",
        "SAPI:BlockRecipeProcessingComponent.inputSlotCount", "the slots of a processing block entity: not modelled",
        "SAPI:BlockSignComponent.isWaxed", "the state of the block entity (sign)",
        "SAPI:BlockRedstoneProducerComponent.power", "the redstone output of producers: not modelled",
        "SAPI:BlockRedstoneProducerComponent.getConnectedFaces", "the redstone output of producers: not modelled",
        "SAPI:BlockRedstoneProducerComponent.getStronglyPoweredFace", "the redstone output of producers: not modelled",
        "SAPI:BlockInstrumentComponent.getInstrumentName({direction})", "the note block instrument per face: a sound, not modelled",
        "SAPI:ItemStack.isStackable", "derived: whether maxAmount is more than 1",
        "SAPI:ItemDurabilityComponent.getDamageChance", "the chance of taking damage per use at unbreaking level 0: not modelled",
        "SAPI:ItemDurabilityComponent.getDamageChanceRange", "BDS 1.26.63.1 fails to read it for most items (\"Failed to get property 'damageRange'\")",
        "SAPI:ItemDurabilityComponent.unbreakable", "the stack's unbreakable flag, not the item type's",
        "SAPI:ItemEnchantableComponent.slots", "BDS 1.26.63.1 returns undefined elements (the dump records null): nothing to read",
        "SAPI:ItemFoodComponent.usingConvertsTo", "the item left after eating, an item identifier: not modelled",
        "SAPI:ItemPotionComponent.potionDeliveryType", "the potion of the stack (its aux value), not the item type's",
        "SAPI:ItemPotionComponent.potionEffectType", "the potion of the stack (its aux value), not the item type's",
        "SAPI:ItemDyeableComponent.defaultColor", "the default dye colour of dyeable items: not modelled");

    private BehaviourKeys() {
    }

    /** A property with {@code {direction}} in its name to the property per {@link #DIRECTIONS direction}; others to themselves. */
    public static List<String> expand(String property) {
        if (!property.contains("{direction}")) {
            return List.of(property);
        }
        return DIRECTIONS.stream().map(direction -> property.replace("{direction}", direction)).toList();
    }

    private static Key block(String name, String type, String doc, List<String> sources, Wire wire) {
        return new Key(Owner.BLOCK, name, type, doc, sources, Conversion.NONE, wire);
    }

    private static Key block(String name, String type, String doc, List<String> sources, Conversion conversion, Wire wire) {
        return new Key(Owner.BLOCK, name, type, doc, sources, conversion, wire);
    }

    private static Key item(String name, String type, String doc, List<String> sources, Wire wire) {
        return new Key(Owner.ITEM, name, type, doc, sources, Conversion.NONE, wire);
    }

    private static Key item(String name, String type, String doc, List<String> sources, Conversion conversion, Wire wire) {
        return new Key(Owner.ITEM, name, type, doc, sources, conversion, wire);
    }

    private static Map<String, String> ignored(String... pairs) {
        Map<String, String> map = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
