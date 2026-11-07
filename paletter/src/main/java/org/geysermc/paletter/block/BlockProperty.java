package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;

import java.util.List;

/**
 * A state property of a custom block. The order properties are declared in determines the order of the generated states.
 *
 * @param name the property name, e.g. {@code geyser:lit}
 * @param type the value type
 * @param values the possible values; the first one is the default
 * @param <T> the value type: {@link Byte} for booleans (0 and 1, as Bedrock stores them), {@link Integer} or {@link String}
 */
public record BlockProperty<T>(@NonNull String name, @NonNull Type type, @NonNull List<T> values) {

    public enum Type {
        BOOLEAN, INTEGER, STRING
    }

    public BlockProperty {
        values = List.copyOf(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Block property " + name + " has no values");
        }
    }

    public static @NonNull BlockProperty<Byte> bool(@NonNull String name) {
        return new BlockProperty<>(name, Type.BOOLEAN, List.of((byte) 0, (byte) 1));
    }

    public static @NonNull BlockProperty<Integer> ints(@NonNull String name, @NonNull Integer... values) {
        return new BlockProperty<>(name, Type.INTEGER, List.of(values));
    }

    public static @NonNull BlockProperty<Integer> range(@NonNull String name, int fromInclusive, int toInclusive) {
        Integer[] values = new Integer[toInclusive - fromInclusive + 1];
        for (int i = 0; i < values.length; i++) {
            values[i] = fromInclusive + i;
        }
        return ints(name, values);
    }

    public static @NonNull BlockProperty<String> strings(@NonNull String name, @NonNull String... values) {
        return new BlockProperty<>(name, Type.STRING, List.of(values));
    }

    NbtMap toNbt() {
        NbtMapBuilder builder = NbtMap.builder().putString("name", name);
        switch (type) {
            case BOOLEAN -> builder.putList("enum", NbtType.BYTE, values.stream().map(Byte.class::cast).toList());
            case INTEGER -> builder.putList("enum", NbtType.INT, values.stream().map(Integer.class::cast).toList());
            case STRING -> builder.putList("enum", NbtType.STRING, values.stream().map(String.class::cast).toList());
        }
        return builder.build();
    }
}
