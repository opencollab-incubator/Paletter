package org.geysermc.paletter.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Lenient NBT conversions used by the generated component types. Not meant to be used directly.
 * <p>
 * Every converter takes an NBT value and returns the Java value, or null if the value has an unexpected kind.
 * Numbers of another width are converted, so NBT written by encoders that used the wrong width still reads.
 */
public final class NbtReader {
    private NbtReader() {
    }

    /**
     * @return the converted value of {@code key}, or {@code fallback} when it is missing or of an unexpected kind
     */
    public static <T> T read(@NonNull NbtMap nbt, @NonNull String key, @NonNull Function<Object, T> converter, T fallback) {
        Object value = nbt.get(key);
        T converted = value == null ? null : converter.apply(value);
        return converted == null ? fallback : converted;
    }

    public static <T> @NonNull Function<Object, T> compound(@NonNull Function<NbtMap, T> reader) {
        return value -> value instanceof NbtMap map ? reader.apply(map) : null;
    }

    public static <T> @NonNull Function<Object, List<T>> listOf(@NonNull Function<Object, T> converter) {
        return value -> {
            if (!(value instanceof List<?> list)) {
                return null;
            }
            List<T> result = new ArrayList<>(list.size());
            for (Object element : list) {
                T converted = converter.apply(element);
                if (converted != null) {
                    result.add(converted);
                }
            }
            return Collections.unmodifiableList(result);
        };
    }

    public static <T> @NonNull Function<Object, Map<String, T>> mapOf(@NonNull Function<Object, T> converter) {
        return value -> {
            if (!(value instanceof NbtMap map)) {
                return null;
            }
            Map<String, T> result = new LinkedHashMap<>();
            map.forEach((key, element) -> {
                T converted = converter.apply(element);
                if (converted != null) {
                    result.put(key, converted);
                }
            });
            return Collections.unmodifiableMap(result);
        };
    }

    public static @Nullable Byte toByte(@Nullable Object value) {
        if (value instanceof Boolean bool) {
            return (byte) (bool ? 1 : 0);
        }
        return value instanceof Number number ? number.byteValue() : null;
    }

    public static @Nullable Boolean toBool(@Nullable Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value instanceof Number number ? number.intValue() != 0 : null;
    }

    public static @Nullable Short toShort(@Nullable Object value) {
        return value instanceof Number number ? number.shortValue() : null;
    }

    public static @Nullable Integer toInt(@Nullable Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    public static @Nullable Long toLong(@Nullable Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    public static @Nullable Float toFloat(@Nullable Object value) {
        return value instanceof Number number ? number.floatValue() : null;
    }

    public static @Nullable Double toDouble(@Nullable Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    public static @Nullable String toStr(@Nullable Object value) {
        return value instanceof String string ? string : null;
    }

    public static @Nullable NbtMap toCompound(@Nullable Object value) {
        return value instanceof NbtMap map ? map : null;
    }

    public static @Nullable NbtList<?> toRawList(@Nullable Object value) {
        return value instanceof NbtList<?> list ? list : null;
    }

    public static @Nullable Object toAny(@Nullable Object value) {
        return value;
    }

    public static @Nullable List<Integer> toIntList(@Nullable Object value) {
        if (value instanceof int[] array) {
            List<Integer> list = new ArrayList<>(array.length);
            for (int i : array) {
                list.add(i);
            }
            return Collections.unmodifiableList(list);
        }
        return value instanceof List<?> ? listOf(NbtReader::toInt).apply(value) : null;
    }

    // Writing

    public static int @NonNull [] intArray(@NonNull List<Integer> list) {
        int[] array = new int[list.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    public static <T> @NonNull NbtList<T> writeList(@NonNull NbtType<T> type, @NonNull List<T> elements) {
        return new NbtList<>(type, elements);
    }

    public static <E> @NonNull NbtList<NbtMap> writeList(@NonNull List<E> elements, @NonNull Function<E, NbtMap> writer) {
        List<NbtMap> nbt = new ArrayList<>(elements.size());
        for (E element : elements) {
            nbt.add(writer.apply(element));
        }
        return new NbtList<>(NbtType.COMPOUND, nbt);
    }

    public static @NonNull NbtMap writeMap(@NonNull Map<String, ?> values) {
        NbtMapBuilder builder = NbtMap.builder();
        values.forEach(builder::put);
        return builder.build();
    }

    public static <E> @NonNull NbtMap writeMap(@NonNull Map<String, E> values, @NonNull Function<E, NbtMap> writer) {
        NbtMapBuilder builder = NbtMap.builder();
        values.forEach((key, value) -> builder.putCompound(key, writer.apply(value)));
        return builder.build();
    }

    public static byte bool(boolean value) {
        return (byte) (value ? 1 : 0);
    }

    public static <T> @NonNull List<T> copy(@NonNull List<T> list) {
        return List.copyOf(list);
    }

    public static <T> @NonNull Map<String, T> copy(@NonNull Map<String, T> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
