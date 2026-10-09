package org.geysermc.paletter.api.component;

/**
 * All bedrock block component types
 * <p>
 * Warning! Since this class follows vanilla strictly, it can be considered unstable
 */
@SuppressWarnings("SpellCheckingInspection")
public final class BedrockBlockComponentTypes {
    public static final BedrockComponentType<?> LIQUID_DETECTION = BedrockComponentType.of("minecraft:liquid_detection");
    public static final BedrockComponentType<?> REDSTONE_CONDUCTIVITY = BedrockComponentType.of("minecraft:redstone_conductivity");
    public static final BedrockComponentType<?> SUPPORT = BedrockComponentType.of("minecraft:support");
    public static final BedrockComponentType<?> DESTRUCTIBLE_BY_MINING = BedrockComponentType.of("minecraft:destructible_by_mining");
    public static final BedrockComponentType<?> DESTRUCTION_PARTICLES = BedrockComponentType.of("minecraft:destruction_particles");
    public static final BedrockComponentType<?> MATERIAL_INSTANCES = BedrockComponentType.of("minecraft:material_instances");
    public static final BedrockComponentType<?> COLLISION_BOX = BedrockComponentType.of("minecraft:collision_box");
    public static final BedrockComponentType<?> GEOMETRY = BedrockComponentType.of("minecraft:geometry");
    public static final BedrockComponentType<?> PRECIPITATION_INTERACTIONS = BedrockComponentType.of("minecraft:precipitation_interactions");
    public static final BedrockComponentType<?> SELECTION_BOX = BedrockComponentType.of("minecraft:selection_box");
    public static final BedrockComponentType<?> CONNECTION_RULE = BedrockComponentType.of("minecraft:connection_rule");
    public static final BedrockComponentType<?> REPLACEABLE = BedrockComponentType.of("minecraft:replaceable");
}
