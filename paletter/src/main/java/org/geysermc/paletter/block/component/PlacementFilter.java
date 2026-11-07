package org.geysermc.paletter.block.component;

import org.checkerframework.checker.nullness.qual.NonNull;

import org.geysermc.paletter.vanilla.BedrockBlockType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One placement condition of a block (add-on JSON {@code minecraft:placement_filter} condition): the block may be placed
 * on any of the faces, against any of the blocks or tag queries. A block passes the filter if any of its conditions
 * holds.
 * <pre>{@code
 * PlacementFilter.allowOn(Face.UP).blocks("minecraft:grass_block", "minecraft:dirt")
 * PlacementFilter.allowOnAnyFace().tags("q.any_tag('stone')")
 * }</pre>
 *
 * @since 2.0.0
 */
public final class PlacementFilter {
    private final Set<Face> faces;
    private final List<String> blocks;
    private final List<String> tags;

    private PlacementFilter(Set<Face> faces, List<String> blocks, List<String> tags) {
        this.faces = Collections.unmodifiableSet(faces.isEmpty() ? EnumSet.noneOf(Face.class) : EnumSet.copyOf(faces));
        this.blocks = List.copyOf(blocks);
        this.tags = List.copyOf(tags);
    }

    /**
     * @param faces the faces of the block below/beside it the block may be placed on
     * @return a condition on these faces against any block, until {@link #blocks} or {@link #tags} narrow it
     */
    public static @NonNull PlacementFilter allowOn(@NonNull Face... faces) {
        if (faces.length == 0) {
            throw new IllegalArgumentException("A placement condition needs at least one face");
        }
        return new PlacementFilter(EnumSet.copyOf(List.of(faces)), List.of(), List.of());
    }

    public static @NonNull PlacementFilter allowOnAnyFace() {
        return new PlacementFilter(EnumSet.allOf(Face.class), List.of(), List.of());
    }

    /**
     * @param identifiers blocks it may be placed against, in addition to those already listed
     */
    public @NonNull PlacementFilter blocks(@NonNull String... identifiers) {
        List<String> all = new ArrayList<>(blocks);
        all.addAll(List.of(identifiers));
        return new PlacementFilter(faces, all, tags);
    }

    /**
     * @param types blocks it may be placed against, in addition to those already listed
     * @since 2.0.0
     */
    public @NonNull PlacementFilter blocks(@NonNull BedrockBlockType... types) {
        String[] identifiers = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            identifiers[i] = types[i].identifier();
        }
        return blocks(identifiers);
    }

    /**
     * @param molang Molang block tag queries it may be placed against, e.g. {@code q.any_tag('stone')}
     */
    public @NonNull PlacementFilter tags(@NonNull String... molang) {
        List<String> all = new ArrayList<>(tags);
        all.addAll(List.of(molang));
        return new PlacementFilter(faces, blocks, all);
    }

    public @NonNull Set<Face> faces() {
        return faces;
    }

    public @NonNull List<String> blocks() {
        return blocks;
    }

    public @NonNull List<String> tags() {
        return tags;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlacementFilter other && faces.equals(other.faces) && blocks.equals(other.blocks) && tags.equals(other.tags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(faces, blocks, tags);
    }

    @Override
    public String toString() {
        return "PlacementFilter[" + faces + ", blocks " + blocks + ", tags " + tags + "]";
    }
}
