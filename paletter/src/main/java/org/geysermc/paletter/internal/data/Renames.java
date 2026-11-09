package org.geysermc.paletter.internal.data;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.annotation.Internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Identifiers Bedrock renamed, old to new, so content of an older version can be mapped forward to the newest one.
 * Bundled as {@code renames.txt}: lines {@code block <old> <new>} or {@code item <old> <new>}, {@code #} comments.
 * A line {@code item <old>@<aux> <new>[@<aux>]} splits a legacy variant off into a type of its own.
 * The same renames are listed in {@code data/model/names.json} ({@code renamed}), which keeps the old constants as
 * deprecated aliases.
 *
 * @since 1.0.0
 */
@Internal
public record Renames(@NonNull Map<String, String> blocks, @NonNull Map<String, String> items,
                      @NonNull Map<String, Split> splits) {
    public static final Renames NONE = new Renames(Map.of(), Map.of(), Map.of());

    public Renames {
        blocks = Map.copyOf(blocks);
        items = Map.copyOf(items);
        splits = Map.copyOf(splits);
    }

    public Renames(@NonNull Map<String, String> blocks, @NonNull Map<String, String> items) {
        this(blocks, items, Map.of());
    }

    /**
     * A legacy item variant that became a type of its own.
     */
    public record Split(@NonNull String identifier, int aux) {
    }

    public static @NonNull Renames parse(@NonNull List<String> lines) {
        Map<String, String> blocks = new HashMap<>();
        Map<String, String> items = new HashMap<>();
        Map<String, Split> splits = new HashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\s+");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Bad renames line: " + raw);
            }
            switch (parts[0]) {
                case "block" -> blocks.put(parts[1], parts[2]);
                case "item" -> {
                    if (parts[1].lastIndexOf('@') < 0) {
                        items.put(parts[1], parts[2]);
                    } else {
                        int to = parts[2].lastIndexOf('@');
                        splits.put(parts[1], new Split(to < 0 ? parts[2] : parts[2].substring(0, to),
                            to < 0 ? 0 : Integer.parseInt(parts[2].substring(to + 1))));
                    }
                }
                default -> throw new IllegalArgumentException("Bad renames line: " + raw);
            }
        }
        return new Renames(blocks, items, splits);
    }

    /**
     * @return what the block is called now (following chains of renames), or null if it was never renamed
     */
    public @Nullable String block(@NonNull String identifier) {
        return follow(blocks, identifier);
    }

    /**
     * @return the type and aux a legacy variant became, or null if it was not split off
     */
    public @Nullable Split split(@NonNull String identifier, int aux) {
        return splits.get(identifier + "@" + aux);
    }

    public @Nullable String item(@NonNull String identifier) {
        return follow(items, identifier);
    }

    private static @Nullable String follow(Map<String, String> renames, String identifier) {
        String current = renames.get(identifier);
        Set<String> seen = new HashSet<>();
        seen.add(identifier);
        while (current != null && renames.containsKey(current) && seen.add(current)) {
            current = renames.get(current);
        }
        return current;
    }
}
