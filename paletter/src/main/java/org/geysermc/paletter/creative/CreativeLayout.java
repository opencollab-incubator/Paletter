package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.item.BedrockItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * An immutable, version independent creative inventory layout: per category, an ordered list of groups and loose stacks.
 * <p>
 * This is the model, not the wire format. The protocol has no notion of "loose" stacks; it only knows a flat list of
 * groups (unnamed ones for loose runs), each item pointing at a group index, and it crashes on empty groups.
 * {@link CreativeContent} handles that translation per client version, so none of it leaks into the layout.
 *
 * @since 1.0.0
 */
public final class CreativeLayout {
    private static final CreativeLayout EMPTY = new CreativeLayout(new EnumMap<>(CreativeCategory.class));

    private final Map<CreativeCategory, List<CreativeNode>> categories;

    CreativeLayout(Map<CreativeCategory, List<CreativeNode>> categories) {
        EnumMap<CreativeCategory, List<CreativeNode>> copy = new EnumMap<>(CreativeCategory.class);
        for (CreativeCategory category : CreativeCategory.values()) {
            copy.put(category, List.copyOf(categories.getOrDefault(category, List.of())));
        }
        this.categories = Collections.unmodifiableMap(copy);
    }

    public static @NonNull CreativeLayout empty() {
        return EMPTY;
    }

    public static @NonNull CreativeLayout of(@NonNull Map<CreativeCategory, List<CreativeNode>> categories) {
        return new CreativeLayout(categories);
    }

    /**
     * @return the nodes of a category, in order
     */
    public @NonNull List<CreativeNode> nodes(@NonNull CreativeCategory category) {
        return categories.get(category);
    }

    public @NonNull Optional<CreativeGroup> group(@NonNull String name) {
        for (List<CreativeNode> nodes : categories.values()) {
            for (CreativeNode node : nodes) {
                if (node instanceof CreativeGroup group && group.name().equals(name)) {
                    return Optional.of(group);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * @return every stack in display order, including stacks inside groups. Group icons are not included
     */
    public @NonNull Stream<BedrockItemStack> stacks() {
        List<BedrockItemStack> stacks = new ArrayList<>();
        forEachStack(stacks::add);
        return stacks.stream();
    }

    public void forEachStack(@NonNull Consumer<BedrockItemStack> consumer) {
        for (CreativeCategory category : CreativeCategory.values()) {
            for (CreativeNode node : categories.get(category)) {
                switch (node) {
                    case CreativeItem item -> consumer.accept(item.stack());
                    case CreativeGroup group -> group.stacks().forEach(consumer);
                }
            }
        }
    }

    private @NonNull List<CreativeLocation> locateAll(@NonNull ItemMatcher matcher) {
        List<CreativeLocation> locations = new ArrayList<>();
        for (CreativeCategory category : CreativeCategory.values()) {
            List<CreativeNode> nodes = categories.get(category);
            for (int i = 0; i < nodes.size(); i++) {
                switch (nodes.get(i)) {
                    case CreativeItem item -> {
                        if (matcher.matches(item.stack())) {
                            locations.add(new CreativeLocation(category, null, i));
                        }
                    }
                    case CreativeGroup group -> {
                        for (int j = 0; j < group.stacks().size(); j++) {
                            if (matcher.matches(group.stacks().get(j))) {
                                locations.add(new CreativeLocation(category, group.name(), j));
                            }
                        }
                    }
                }
            }
        }
        return locations;
    }

    /**
     * @return true if a stack matching the matcher is in the layout, loose or in a group
     * @since 1.0.0
     */
    public boolean contains(@NonNull ItemMatcher matcher) {
        return !locateAll(matcher).isEmpty();
    }

    /**
     * @return the name of the group holding the first stack matching the matcher, or null if there is none or it is loose
     * @since 1.0.0
     */
    public @Nullable String groupOf(@NonNull ItemMatcher matcher) {
        List<CreativeLocation> all = locateAll(matcher);
        return all.isEmpty() ? null : all.getFirst().group();
    }

    /**
     * @return a mutable editor starting from this layout. This layout is not changed
     */
    public @NonNull CreativeEditor edit() {
        return new CreativeEditor(this);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CreativeLayout other && categories.equals(other.categories);
    }

    @Override
    public int hashCode() {
        return categories.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("CreativeLayout{");
        for (CreativeCategory category : CreativeCategory.values()) {
            long groups = categories.get(category).stream().filter(CreativeGroup.class::isInstance).count();
            builder.append(category.bedrockName()).append('=').append(categories.get(category).size())
                .append(" nodes/").append(groups).append(" groups, ");
        }
        return builder.append('}').toString();
    }

    /**
     * Where a stack is in the layout.
     *
     * @param group the group name, or null if the stack is loose
     * @param index the index inside the group, or the node index inside the category if loose
     */
    private record CreativeLocation(@NonNull CreativeCategory category, @Nullable String group, int index) {
    }
}
