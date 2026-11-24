package dev.opencollab.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import dev.opencollab.paletter.internal.block.BlockStates;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates the generated constants without loading any data: the constants classes call it from their static
 * initializers. {@link VanillaIndex} registers the constant fields by identifier and links them to the bundled data
 * on first use; types created later (absent from the data) link themselves to states of their own.
 */
final class VanillaTypes {
    static final String UNKNOWN = "minecraft:unknown";

    static final Map<String, BedrockState<?>> STATES = new ConcurrentHashMap<>();

    private VanillaTypes() {
    }

    /**
     * The block and item types the generated constants made, in the order of their fields: {@link VanillaIndex} links
     * these, without reflecting over the constants classes. Each list is only added to by the static initializer of its
     * class, which the JVM runs once.
     */
    static final List<BedrockBlockType> BLOCK_CONSTANTS = Collections.synchronizedList(new ArrayList<>());
    static final List<BedrockItemType> ITEM_CONSTANTS = Collections.synchronizedList(new ArrayList<>());

    /** What a block or item resolves to if the bundled data does not have it (a removed or preview one): {@code minecraft:unknown}. */
    static @NonNull BedrockBlockType block(@NonNull String name, @NonNull String identifier) {
        // Runs for every constant in the (interpreted) static initializers: no map, VanillaIndex links the list
        return register(new BedrockBlockType(name, identifier, UNKNOWN, false, null));
    }

    static @NonNull BedrockBlockType educationBlock(@NonNull String name, @NonNull String identifier) {
        return register(new BedrockBlockType(name, identifier, UNKNOWN, true, null));
    }

    /**
     * A block the newest bundled release does not have, with the properties the generator wrote: a block only the bundled
     * preview has ({@code @SincePreview}), as the preview has it, or a block Bedrock removed, as the newest release that had
     * it had it. The constant has these states in every palette, without any data being loaded for them.
     *
     * @param defaultOffset the position of the default state among the states, as enumerated from the properties
     * @param properties the properties in the data's state order: the first one varies fastest
     */
    static @NonNull BedrockBlockType blockWithStates(@NonNull String name, @NonNull String identifier, int defaultOffset,
                                                     @NonNull PreviewProperty... properties) {
        return register(new BedrockBlockType(name, identifier, UNKNOWN, false, new PreviewStates(defaultOffset, properties)));
    }

    private static BedrockBlockType register(BedrockBlockType type) {
        BLOCK_CONSTANTS.add(type);
        return type;
    }

    private static BedrockItemType register(BedrockItemType type) {
        ITEM_CONSTANTS.add(type);
        return type;
    }

    /** A property of a block of {@link #blockWithStates}: its name and its values as NBT ({@code Byte}, {@code Integer} or {@code String}). */
    static @NonNull PreviewProperty property(@NonNull String name, @NonNull Object... values) {
        return new PreviewProperty(name, values);
    }

    record PreviewProperty(String name, Object[] values) {
    }

    /** The states of a block of {@link #blockWithStates}. */
    record PreviewStates(int defaultOffset, PreviewProperty[] properties) {
        /** @return the states as {@code {name, states}}, in the preview's order: the full product, the first property fastest */
        List<NbtMap> states(String identifier) {
            int count = 1;
            for (PreviewProperty property : properties) {
                count *= property.values().length;
            }
            List<NbtMap> states = new ArrayList<>(count);
            for (int offset = 0; offset < count; offset++) {
                NbtMapBuilder values = NbtMap.builder();
                int rest = offset;
                for (PreviewProperty property : properties) {
                    values.put(property.name(), property.values()[rest % property.values().length]);
                    rest /= property.values().length;
                }
                states.add(BlockStates.of(identifier, values.build()));
            }
            return states;
        }
    }

    static @NonNull BedrockItemType educationItem(@NonNull String name, @NonNull String identifier) {
        return register(new BedrockItemType(name, identifier, UNKNOWN, true));
    }

    static @NonNull BedrockItemType item(@NonNull String name, @NonNull String identifier) {
        return register(new BedrockItemType(name, identifier, UNKNOWN, false));
    }

    /**
     * A named variant of a vanilla type ({@code BedrockItemVariants}). The index adopts it as the type's variant of that aux,
     * so it is the instance {@link BedrockItemType#variant(int)} answers.
     */
    static @NonNull BedrockItemVariant variant(@NonNull BedrockItemType type, int aux) {
        return aux == 0 ? type.variant() : type.name(new BedrockItemVariant(type, aux, -1));
    }

    private static volatile boolean namedLoaded;

    /** Initializes {@code BedrockItemVariants}, which registers the named variants on their types: constants only, no data. */
    static void loadNamedVariants() {
        if (!namedLoaded) {
            try {
                Class.forName(VanillaTypes.class.getPackageName() + ".BedrockItemVariants", true, VanillaTypes.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            namedLoaded = true;
        }
    }

    static <S extends BedrockState<?>> @NonNull S state(@NonNull S state) {
        BedrockState<?> existing = STATES.putIfAbsent(state.name(), state);
        if (existing != null) {
            throw new IllegalStateException("Two state constants named " + state.name());
        }
        return state;
    }
}
