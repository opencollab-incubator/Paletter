package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.vanilla.BedrockItemType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * A mutable view of a {@link CreativeLayout}. Not thread safe; obtain one from {@link CreativeLayout#edit()}, or
 * receive one in a {@link org.geysermc.paletter.PaletteLayer} creative edit.
 * <p>
 * Placement is relative to things that already exist, never by raw index, so edits keep working when a new
 * Bedrock version shuffles the vanilla layout:
 * <pre>{@code
 * editor.insert(BedrockItemStack.of(furnaceMinecart.type())).after(ItemMatcher.id("minecraft:hopper_minecart"));
 * editor.groupOrCreate(CreativeCategory.ITEMS, "geyser:tools").add(stack).placeAfterGroup("itemGroup.name.minecart");
 * editor.remove(ItemMatcher.id("minecraft:empty_map").and(ItemMatcher.damage(2)));
 * }</pre>
 * Placements that depend on an anchor return {@code false} when the anchor does not exist, so a fallback is a plain
 * {@code ||}: {@code insertion.after(anchor) || insertion.intoGroup(name)}.
 *
 * @since 1.0.0
 */
public final class CreativeEditor {
    private final Map<CreativeCategory, List<Object>> categories = new EnumMap<>(CreativeCategory.class);

    CreativeEditor(CreativeLayout base) {
        for (CreativeCategory category : CreativeCategory.values()) {
            List<Object> nodes = new ArrayList<>();
            for (CreativeNode node : base.nodes(category)) {
                switch (node) {
                    case CreativeItem item -> nodes.add(item);
                    case CreativeGroup group -> nodes.add(new MutableGroup(group.name(), group.icon(), new ArrayList<>(group.stacks())));
                }
            }
            categories.put(category, nodes);
        }
    }

    /**
     * Returns the current nodes of a category.
     *
     * @param category the category
     * @return a snapshot of the current nodes of a category
     */
    public @NonNull List<CreativeNode> view(@NonNull CreativeCategory category) {
        return categories.get(category).stream().map(CreativeEditor::freeze).toList();
    }

    /**
     * Checks whether a stack is in the layout being edited.
     *
     * @param matcher selects the stacks to look for
     * @return true if a stack matching the matcher is in the layout being edited
     * @since 1.0.0
     */
    public boolean contains(@NonNull ItemMatcher matcher) {
        return find(matcher, null) != null;
    }

    /**
     * Finds the group of a stack in the layout being edited.
     *
     * @param matcher selects the stacks to look for
     * @return the name of the group holding the first stack matching the matcher, or null if there is none or it is loose
     * @since 1.0.0
     */
    public @Nullable String groupOf(@NonNull ItemMatcher matcher) {
        Position position = find(matcher, null);
        return position == null || position.group == null ? null : position.group.name;
    }

    /**
     * Finds a group by name, in any category.
     *
     * @param name the group name
     * @return an editor of the first group of that name, or empty if there is none
     */
    public @NonNull Optional<GroupEditor> group(@NonNull String name) {
        MutableGroup group = findGroup(name);
        return group == null ? Optional.empty() : Optional.of(new GroupEditor(group));
    }

    /**
     * Finds or creates a group.
     *
     * @param category the category to create the group in
     * @param name the group name
     * @return the group with this name, or a new empty group at the end of the category if none exists.
     * An existing group is returned as is, even if it lives in another category
     */
    public @NonNull GroupEditor groupOrCreate(@NonNull CreativeCategory category, @NonNull String name) {
        MutableGroup group = findGroup(name);
        if (group == null) {
            group = new MutableGroup(name, null, new ArrayList<>());
            categories.get(category).add(group);
        }
        return new GroupEditor(group);
    }

    /**
     * Creates a new empty group at the end of a category.
     *
     * @param category the category
     * @param name the group name
     * @return an editor of the new group
     * @throws IllegalArgumentException if a group with this name already exists
     */
    public @NonNull GroupEditor createGroup(@NonNull CreativeCategory category, @NonNull String name) {
        if (findGroup(name) != null) {
            throw new IllegalArgumentException("Creative group " + name + " already exists");
        }
        return groupOrCreate(category, name);
    }

    /**
     * Removes a group and everything in it.
     *
     * @param name the group name
     * @return true if a group of that name existed and was removed
     */
    public boolean removeGroup(@NonNull String name) {
        MutableGroup group = findGroup(name);
        if (group == null) {
            return false;
        }
        categories.get(categoryOf(group)).remove(group);
        return true;
    }

    /**
     * Removes a group, keeping its stacks as loose stacks in its place. The group icon is dropped.
     *
     * @param name the group name
     * @return true if a group of that name existed and was dissolved
     */
    public boolean dissolveGroup(@NonNull String name) {
        MutableGroup group = findGroup(name);
        if (group == null) {
            return false;
        }
        List<Object> nodes = categories.get(categoryOf(group));
        int index = nodes.indexOf(group);
        nodes.remove(index);
        nodes.addAll(index, items(group.stacks));
        return true;
    }

    /**
     * Starts inserting stacks. Nothing happens until a placement method of the returned insertion is called.
     *
     * @param stacks the stacks to insert
     * @return the pending insertion
     */
    public @NonNull Insertion insert(@NonNull BedrockItemStack... stacks) {
        return new Insertion(List.of(stacks), null);
    }

    /**
     * Starts inserting stacks. Nothing happens until a placement method of the returned insertion is called.
     *
     * @param stacks the stacks to insert; the list is copied
     * @return the pending insertion
     */
    public @NonNull Insertion insert(@NonNull List<BedrockItemStack> stacks) {
        return new Insertion(List.copyOf(stacks), null);
    }

    /**
     * Starts moving all stacks matching a matcher. They are only removed once a placement succeeds.
     *
     * @param matcher selects the stacks to move
     * @return the pending move
     */
    public @NonNull Insertion move(@NonNull ItemMatcher matcher) {
        List<BedrockItemStack> stacks = new ArrayList<>();
        forEachStack(stacks::add, matcher);
        return new Insertion(stacks, matcher);
    }

    /**
     * Removes every stack matching the matcher, loose or grouped. Group icons that match are reset to the default.
     *
     * @param matcher selects the stacks to remove
     * @return the amount of stacks removed
     */
    public int remove(@NonNull ItemMatcher matcher) {
        int removed = 0;
        for (List<Object> nodes : categories.values()) {
            for (int i = nodes.size() - 1; i >= 0; i--) {
                Object node = nodes.get(i);
                if (node instanceof CreativeItem item) {
                    if (matcher.matches(item.stack())) {
                        nodes.remove(i);
                        removed++;
                    }
                } else {
                    MutableGroup group = (MutableGroup) node;
                    int before = group.stacks.size();
                    group.stacks.removeIf(matcher::matches);
                    removed += before - group.stacks.size();
                    if (group.icon != null && matcher.matches(group.icon)) {
                        group.icon = null;
                    }
                }
            }
        }
        return removed;
    }

    /**
     * Replaces every stack matching the matcher, including group icons.
     *
     * @param matcher selects the stacks to replace
     * @param replacement maps a matching stack to its replacement, which must not be null
     * @return the amount of stacks replaced
     */
    public int replace(@NonNull ItemMatcher matcher, @NonNull UnaryOperator<BedrockItemStack> replacement) {
        int replaced = 0;
        for (List<Object> nodes : categories.values()) {
            for (int i = 0; i < nodes.size(); i++) {
                Object node = nodes.get(i);
                if (node instanceof CreativeItem item) {
                    if (matcher.matches(item.stack())) {
                        nodes.set(i, new CreativeItem(Objects.requireNonNull(replacement.apply(item.stack()))));
                        replaced++;
                    }
                } else {
                    MutableGroup group = (MutableGroup) node;
                    for (int j = 0; j < group.stacks.size(); j++) {
                        if (matcher.matches(group.stacks.get(j))) {
                            group.stacks.set(j, Objects.requireNonNull(replacement.apply(group.stacks.get(j))));
                            replaced++;
                        }
                    }
                    if (group.icon != null && matcher.matches(group.icon)) {
                        group.icon = replacement.apply(group.icon);
                    }
                }
            }
        }
        return replaced;
    }

    /**
     * Freezes the current state.
     *
     * @return an immutable layout of the current state; this editor stays usable
     */
    public @NonNull CreativeLayout build() {
        Map<CreativeCategory, List<CreativeNode>> frozen = new EnumMap<>(CreativeCategory.class);
        for (Map.Entry<CreativeCategory, List<Object>> entry : categories.entrySet()) {
            frozen.put(entry.getKey(), entry.getValue().stream().map(CreativeEditor::freeze).toList());
        }
        return new CreativeLayout(frozen);
    }

    /**
     * A pending insertion (or move) of stacks. Each placement method returns whether the stacks were placed;
     * after a successful placement, further calls do nothing and return false.
     */
    public final class Insertion {
        private final List<BedrockItemStack> stacks;
        private final @Nullable ItemMatcher moving;
        private boolean done;

        private Insertion(List<BedrockItemStack> stacks, @Nullable ItemMatcher moving) {
            this.stacks = stacks;
            this.moving = moving;
        }

        /**
         * Places the stacks right after the first stack matching the anchor, inside the same group (or loose, if it is loose).
         *
         * @param anchor selects the stack to place after
         * @return true if the stacks were placed, false if nothing matches the anchor or they were placed already
         */
        public boolean after(@NonNull ItemMatcher anchor) {
            return relative(anchor, 1);
        }

        /**
         * Places the stacks right before the first stack matching the anchor, inside the same group (or loose, if it is loose).
         *
         * @param anchor selects the stack to place before
         * @return true if the stacks were placed, false if nothing matches the anchor or they were placed already
         */
        public boolean before(@NonNull ItemMatcher anchor) {
            return relative(anchor, 0);
        }

        /**
         * Places the stacks at the end of an existing group.
         *
         * @param group the group name, in any category
         * @return true if the stacks were placed, false if there is no such group or they were placed already
         */
        public boolean intoGroup(@NonNull String group) {
            return intoGroup(group, Integer.MAX_VALUE);
        }

        /**
         * Places the stacks in an existing group, at an index.
         *
         * @param group the group name, in any category
         * @param index the index in the group; an index past the end places at the end
         * @return true if the stacks were placed, false if there is no such group or they were placed already
         */
        public boolean intoGroup(@NonNull String group, int index) {
            if (done || findGroup(group) == null) {
                return false;
            }
            removeMoving();
            MutableGroup target = Objects.requireNonNull(findGroup(group));
            target.stacks.addAll(Math.min(index, target.stacks.size()), stacks);
            return done = true;
        }

        /**
         * Places the stacks at the end of a group, creating the group at the end of the category if it does not exist.
         *
         * @param category the category to create the group in
         * @param group the group name
         * @return true if the stacks were placed, false if they were placed already
         */
        public boolean intoGroup(@NonNull CreativeCategory category, @NonNull String group) {
            if (done) {
                return false;
            }
            removeMoving();
            groupOrCreate(category, group).group.stacks.addAll(stacks);
            return done = true;
        }

        /**
         * Places the stacks at the end of the group of that name in the category, creating the group at the end of
         * the category if the category has none (even if another category has a group of that name), as BDS does.
         *
         * @param category the category
         * @param group the group name
         * @return true if the stacks were placed, false if they were placed already
         */
        boolean intoCategoryGroup(CreativeCategory category, String group) {
            if (done) {
                return false;
            }
            removeMoving();
            MutableGroup target = null;
            for (Object node : categories.get(category)) {
                if (node instanceof MutableGroup candidate && candidate.name.equals(group)) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                target = new MutableGroup(group, null, new ArrayList<>());
                categories.get(category).add(target);
            }
            target.stacks.addAll(stacks);
            return done = true;
        }

        /**
         * Places the stacks as loose stacks at the end of a category.
         *
         * @param category the category
         * @return true if the stacks were placed, false if they were placed already
         */
        public boolean loose(@NonNull CreativeCategory category) {
            if (done) {
                return false;
            }
            removeMoving();
            categories.get(category).addAll(items(stacks));
            return done = true;
        }

        /**
         * Places the stacks as loose stacks right after a group.
         *
         * @param group the group name
         * @return true if the stacks were placed, false if there is no such group or they were placed already
         */
        public boolean afterGroup(@NonNull String group) {
            return besideGroup(group, 1);
        }

        /**
         * Places the stacks as loose stacks right before a group.
         *
         * @param group the group name
         * @return true if the stacks were placed, false if there is no such group or they were placed already
         */
        public boolean beforeGroup(@NonNull String group) {
            return besideGroup(group, 0);
        }

        private boolean besideGroup(String name, int offset) {
            if (done || findGroup(name) == null) {
                return false;
            }
            removeMoving();
            MutableGroup group = Objects.requireNonNull(findGroup(name));
            List<Object> nodes = categories.get(categoryOf(group));
            nodes.addAll(nodes.indexOf(group) + offset, items(stacks));
            return done = true;
        }

        private boolean relative(ItemMatcher anchor, int offset) {
            if (done || find(anchor, moving) == null) {
                return false;
            }
            removeMoving();
            // Positions shift when moved stacks are removed, so only look the anchor up afterwards
            Position position = Objects.requireNonNull(find(anchor, null));
            if (position.group != null) {
                position.group.stacks.addAll(position.index + offset, stacks);
            } else {
                categories.get(position.category).addAll(position.index + offset, items(stacks));
            }
            return done = true;
        }

        private void removeMoving() {
            if (moving != null) {
                CreativeEditor.this.remove(moving);
            }
        }
    }

    /**
     * A live handle to a group. Stays valid when the group is moved, but not after it is removed.
     */
    public final class GroupEditor {
        private final MutableGroup group;

        private GroupEditor(MutableGroup group) {
            this.group = group;
        }

        /**
         * Returns the group name.
         *
         * @return the group name
         */
        public @NonNull String name() {
            return group.name;
        }

        /**
         * Returns the category the group is in.
         *
         * @return the category
         * @throws IllegalStateException if the group was removed
         */
        public @NonNull CreativeCategory category() {
            return categoryOf(group);
        }

        /**
         * Returns the icon of the group.
         *
         * @return the icon, or null if the first stack that exists in the client's version is used
         */
        public @Nullable BedrockItemStack icon() {
            return group.icon;
        }

        /**
         * Sets the icon of the group.
         *
         * @param icon the icon, or null to use the first stack that exists in the client's version
         * @return this editor
         */
        public @NonNull GroupEditor icon(@Nullable BedrockItemStack icon) {
            group.icon = icon;
            return this;
        }

        /**
         * Returns the stacks of the group.
         *
         * @return a snapshot of the stacks, in order
         */
        public @NonNull List<BedrockItemStack> stacks() {
            return List.copyOf(group.stacks);
        }

        /**
         * Returns the number of stacks in the group.
         *
         * @return the number of stacks
         */
        public int size() {
            return group.stacks.size();
        }

        /**
         * Adds stacks at the end of the group.
         *
         * @param stacks the stacks to add
         * @return this editor
         */
        public @NonNull GroupEditor add(@NonNull BedrockItemStack... stacks) {
            group.stacks.addAll(Arrays.asList(stacks));
            return this;
        }

        /**
         * Adds a stack at an index of the group.
         *
         * @param index the index, between 0 and {@link #size()}
         * @param stack the stack to add
         * @return this editor
         * @throws IndexOutOfBoundsException if the index is out of range
         */
        public @NonNull GroupEditor add(int index, @NonNull BedrockItemStack stack) {
            group.stacks.add(index, stack);
            return this;
        }

        /**
         * Removes the stacks of the group matching the matcher. The icon is not changed.
         *
         * @param matcher selects the stacks to remove
         * @return the amount of stacks removed
         */
        public int remove(@NonNull ItemMatcher matcher) {
            int before = group.stacks.size();
            group.stacks.removeIf(matcher::matches);
            return before - group.stacks.size();
        }

        /**
         * Sorts the stacks of the group.
         *
         * @param comparator the order
         * @return this editor
         */
        public @NonNull GroupEditor sort(@NonNull Comparator<BedrockItemStack> comparator) {
            group.stacks.sort(comparator);
            return this;
        }

        /**
         * Moves this group to the end of a category.
         *
         * @param category the category
         * @return this editor
         */
        public @NonNull GroupEditor moveTo(@NonNull CreativeCategory category) {
            detach();
            categories.get(category).add(group);
            return this;
        }

        /**
         * Moves this group right after the node containing the first stack matching the anchor: after that stack if
         * it is loose, or after its group otherwise. Fails if the anchor is in this group itself.
         *
         * @param anchor selects the stack to place after
         * @return true if the group was moved, false if nothing matches the anchor or it is in this group
         */
        public boolean placeAfter(@NonNull ItemMatcher anchor) {
            return placeBeside(anchor, 1);
        }

        /**
         * Moves this group right before the node containing the first stack matching the anchor: before that stack if
         * it is loose, or before its group otherwise. Fails if the anchor is in this group itself.
         *
         * @param anchor selects the stack to place before
         * @return true if the group was moved, false if nothing matches the anchor or it is in this group
         */
        public boolean placeBefore(@NonNull ItemMatcher anchor) {
            return placeBeside(anchor, 0);
        }

        /**
         * Moves this group right after another group.
         *
         * @param other the name of the other group
         * @return true if the group was moved, false if there is no such group or it is this group
         */
        public boolean placeAfterGroup(@NonNull String other) {
            return placeBesideGroup(other, 1);
        }

        /**
         * Moves this group right before another group.
         *
         * @param other the name of the other group
         * @return true if the group was moved, false if there is no such group or it is this group
         */
        public boolean placeBeforeGroup(@NonNull String other) {
            return placeBesideGroup(other, 0);
        }

        private boolean placeBeside(ItemMatcher anchor, int offset) {
            Position position = find(anchor, null);
            if (position == null || position.group == group) {
                return false;
            }
            Object anchorNode = position.group != null ? position.group : categories.get(position.category).get(position.index);
            detach();
            List<Object> nodes = categories.get(position.category);
            nodes.add(indexOfIdentity(nodes, anchorNode) + offset, group);
            return true;
        }

        private boolean placeBesideGroup(String other, int offset) {
            MutableGroup target = findGroup(other);
            if (target == null || target == group) {
                return false;
            }
            detach();
            List<Object> nodes = categories.get(categoryOf(target));
            nodes.add(nodes.indexOf(target) + offset, group);
            return true;
        }

        private void detach() {
            categories.get(categoryOf(group)).remove(group);
        }
    }

    private static List<CreativeItem> items(List<BedrockItemStack> stacks) {
        return stacks.stream().map(CreativeItem::new).toList();
    }

    private static CreativeNode freeze(Object node) {
        if (node instanceof CreativeItem item) {
            return item;
        }
        MutableGroup group = (MutableGroup) node;
        return new CreativeGroup(group.name, group.icon, group.stacks);
    }

    private @Nullable MutableGroup findGroup(String name) {
        for (List<Object> nodes : categories.values()) {
            for (Object node : nodes) {
                if (node instanceof MutableGroup group && group.name.equals(name)) {
                    return group;
                }
            }
        }
        return null;
    }

    private CreativeCategory categoryOf(MutableGroup group) {
        for (Map.Entry<CreativeCategory, List<Object>> entry : categories.entrySet()) {
            if (indexOfIdentity(entry.getValue(), group) >= 0) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("Group " + group.name + " is no longer part of this layout");
    }

    private static int indexOfIdentity(List<Object> nodes, Object node) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i) == node) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @param excluding stacks matching this are skipped, so a move cannot anchor to itself
     */
    private @Nullable Position find(ItemMatcher matcher, @Nullable ItemMatcher excluding) {
        for (CreativeCategory category : CreativeCategory.values()) {
            List<Object> nodes = categories.get(category);
            for (int i = 0; i < nodes.size(); i++) {
                Object node = nodes.get(i);
                if (node instanceof CreativeItem item) {
                    BedrockItemStack stack = item.stack();
                    if (matcher.matches(stack) && (excluding == null || !excluding.matches(stack))) {
                        return new Position(category, null, i);
                    }
                } else {
                    MutableGroup group = (MutableGroup) node;
                    for (int j = 0; j < group.stacks.size(); j++) {
                        BedrockItemStack stack = group.stacks.get(j);
                        if (matcher.matches(stack) && (excluding == null || !excluding.matches(stack))) {
                            return new Position(category, group, j);
                        }
                    }
                }
            }
        }
        return null;
    }

    private void forEachStack(java.util.function.Consumer<BedrockItemStack> consumer, ItemMatcher matcher) {
        for (CreativeCategory category : CreativeCategory.values()) {
            for (Object node : categories.get(category)) {
                if (node instanceof CreativeItem item) {
                    if (matcher.matches(item.stack())) {
                        consumer.accept(item.stack());
                    }
                } else {
                    for (BedrockItemStack stack : ((MutableGroup) node).stacks) {
                        if (matcher.matches(stack)) {
                            consumer.accept(stack);
                        }
                    }
                }
            }
        }
    }

    private static final class MutableGroup {
        private final String name;
        private @Nullable BedrockItemStack icon;
        private final List<BedrockItemStack> stacks;

        private MutableGroup(String name, @Nullable BedrockItemStack icon, List<BedrockItemStack> stacks) {
            this.name = name;
            this.icon = icon;
            this.stacks = stacks;
        }
    }

    private record Position(CreativeCategory category, @Nullable MutableGroup group, int index) {
    }
}
