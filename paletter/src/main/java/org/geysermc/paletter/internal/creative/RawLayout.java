package org.geysermc.paletter.internal.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.creative.CreativeCategory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The creative inventory of the bundled data of one version: per category an ordered list of groups and loose
 * stacks, by identifiers. Immutable.
 */
public final class RawLayout {
    private final Map<CreativeCategory, List<RawNode>> categories;

    private RawLayout(Map<CreativeCategory, List<RawNode>> categories) {
        EnumMap<CreativeCategory, List<RawNode>> copy = new EnumMap<>(CreativeCategory.class);
        for (CreativeCategory category : CreativeCategory.values()) {
            copy.put(category, List.copyOf(categories.getOrDefault(category, List.of())));
        }
        this.categories = Collections.unmodifiableMap(copy);
    }

    public static @NonNull RawLayout of(@NonNull Map<CreativeCategory, List<RawNode>> categories) {
        return new RawLayout(categories);
    }

    public static @NonNull RawLayout empty() {
        return new RawLayout(Map.of());
    }

    public @NonNull List<RawNode> nodes(@NonNull CreativeCategory category) {
        return categories.get(category);
    }

    /**
     * @return every stack in display order, including stacks inside groups. Group icons are not included
     */
    public @NonNull Stream<RawStack> stacks() {
        List<RawStack> stacks = new ArrayList<>();
        forEachStack(stacks::add);
        return stacks.stream();
    }

    public void forEachStack(@NonNull Consumer<RawStack> consumer) {
        for (CreativeCategory category : CreativeCategory.values()) {
            for (RawNode node : categories.get(category)) {
                switch (node) {
                    case RawStack stack -> consumer.accept(stack);
                    case RawGroup group -> group.stacks().forEach(consumer);
                }
            }
        }
    }

    /**
     * @return this layout without the stacks matching the predicate; group icons that match are reset to the default
     */
    public @NonNull RawLayout remove(@NonNull Predicate<RawStack> predicate) {
        Map<CreativeCategory, List<RawNode>> result = new EnumMap<>(CreativeCategory.class);
        for (CreativeCategory category : CreativeCategory.values()) {
            List<RawNode> nodes = new ArrayList<>();
            for (RawNode node : categories.get(category)) {
                switch (node) {
                    case RawStack stack -> {
                        if (!predicate.test(stack)) {
                            nodes.add(stack);
                        }
                    }
                    case RawGroup group -> nodes.add(new RawGroup(group.name(),
                        group.icon() != null && predicate.test(group.icon()) ? null : group.icon(),
                        group.stacks().stream().filter(stack -> !predicate.test(stack)).toList()));
                }
            }
            result.put(category, nodes);
        }
        return new RawLayout(result);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RawLayout other && categories.equals(other.categories);
    }

    @Override
    public int hashCode() {
        return categories.hashCode();
    }
}
