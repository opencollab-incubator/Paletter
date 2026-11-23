package dev.opencollab.paletter.internal.behaviour;

import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.behaviour.vanilla.BehaviourData;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The behaviour values of vanilla blocks and items of every bundled data version, and where they come from: generated
 * code ({@code internal.behaviour.vanilla}, written by {@code behaviour import} in the tools) that builds this once, the
 * first time {@link #bundled()} is called. A {@code Paletter} built with {@code behaviour(false)} never calls it, so the
 * generated classes are never loaded. Immutable.
 * <p>
 * A palette of a version that has no data of its own uses the newest older one ({@link #version}), as for a preview.
 * Besides the values: what a capture's source said it post-processed, the values other captures disputed, both per
 * capture ({@link #note}, {@link #disputes}), and the BDS defaults of custom content ({@link #customDefault}).
 */
@Internal
public final class VanillaBehaviour {
    /** The BDS default of a key for custom content, and the evidence it comes from. */
    public record Default(@NonNull Object value, Provenance.@NonNull Source source, @NonNull String reference) {
    }

    /** Another capture's value for the same block state or item. */
    public record Dispute(@NonNull Capture capture, @NonNull Object value) {
    }

    /** A note ({@code alternative} null) or a dispute of a capture, for one key and one block or item: a value or a {@link Cell}. */
    private record Mark(@Nullable Capture alternative, @NonNull Object value) {
    }

    private record MarkKey(Capture capture, BehaviourKey<?> key, String identifier) {
    }

    private final List<VanillaVersion> versions;
    private final Map<MarkKey, List<Mark>> marks;
    private final Map<BehaviourKey<?>, Default> defaults;

    private VanillaBehaviour(List<VanillaVersion> versions, Map<MarkKey, List<Mark>> marks, Map<BehaviourKey<?>, Default> defaults) {
        this.versions = versions;
        this.marks = marks;
        this.defaults = defaults;
    }

    /** @return the bundled values, built on the first call */
    public static @NonNull VanillaBehaviour bundled() {
        return Bundled.DATA;
    }

    /** Loaded on the first {@link #bundled()}, and the generated classes with it. */
    private static final class Bundled {
        static final VanillaBehaviour DATA = BehaviourData.load();
    }

    /** @return the versions, oldest first */
    public @NonNull List<VanillaVersion> versions() {
        return versions;
    }

    /** @return the values of a version: of its own name, else of the newest one not newer by protocol; null if there is none */
    public @Nullable VanillaVersion version(@NonNull DataVersion version) {
        for (VanillaVersion candidate : versions) {
            if (candidate.version().name().equals(version.name())) {
                return candidate;
            }
        }
        for (int i = versions.size() - 1; i >= 0; i--) {
            if (versions.get(i).version().protocolVersion() <= version.protocolVersion()) {
                return versions.get(i);
            }
        }
        return null;
    }

    /**
     * @param states the {@code states} compound of a block state; empty for an item
     * @return how the capture's source post-processed its value of the key for a block state or item, or null
     */
    public @Nullable String note(@NonNull Capture capture, @NonNull BehaviourKey<?> key, @NonNull String identifier, @NonNull NbtMap states) {
        String note = null;
        for (Mark mark : marks.getOrDefault(new MarkKey(capture, key, identifier), List.of())) {
            if (mark.alternative() == null && valueOf(mark, states) instanceof String text) {
                note = text;
            }
        }
        return note;
    }

    /** @return what other captures said where they disagree with the capture's value and nothing settles it */
    public @NonNull List<Dispute> disputes(@NonNull Capture capture, @NonNull BehaviourKey<?> key, @NonNull String identifier, @NonNull NbtMap states) {
        List<Dispute> disputes = List.of();
        for (Mark mark : marks.getOrDefault(new MarkKey(capture, key, identifier), List.of())) {
            Object value = mark.alternative() == null ? null : valueOf(mark, states);
            if (value != null) {
                if (disputes.isEmpty()) {
                    disputes = new ArrayList<>();
                }
                disputes.add(new Dispute(mark.alternative(), value));
            }
        }
        return disputes.isEmpty() ? disputes : List.copyOf(disputes);
    }

    private static @Nullable Object valueOf(Mark mark, NbtMap states) {
        return mark.value() instanceof Cell cell ? cell.get(states) : mark.value();
    }

    /** @return the BDS default of a key for custom content without the component, or null if it is not known */
    public @Nullable Default customDefault(@NonNull BehaviourKey<?> key) {
        return defaults.get(key);
    }

    public static @NonNull Builder builder() {
        return new Builder();
    }

    /** What the generated data calls: the versions oldest first, then the marks and defaults. */
    public static final class Builder {
        private final List<VanillaVersion> versions = new ArrayList<>();
        private final Map<MarkKey, List<Mark>> marks = new HashMap<>();
        private final Map<BehaviourKey<?>, Default> defaults = new LinkedHashMap<>();
        private final Map<Object, Object> pool = new HashMap<>();
        private @Nullable VanillaVersionBuilder current;

        private Builder() {
        }

        /** @return the builder of the next version, from the values of the version before (none for the first) */
        public @NonNull VanillaVersionBuilder version(@NonNull DataVersion version) {
            finish();
            current = new VanillaVersionBuilder(version, versions.isEmpty() ? null : versions.getLast());
            return current;
        }

        private void finish() {
            if (current != null) {
                versions.add(current.build(pool));
                current = null;
            }
        }

        /** Says how the capture's source post-processed its values of the key for a block (a value or a {@link Cell}) or item. */
        public @NonNull Builder note(@NonNull Capture capture, @NonNull BehaviourKey<?> key, @NonNull String identifier, @NonNull Object note) {
            marks.computeIfAbsent(new MarkKey(capture, key, identifier), k -> new ArrayList<>()).add(new Mark(null, note));
            return this;
        }

        /** Says what {@code alternative} said where it disagrees with the capture (a value or a {@link Cell}, null where it does not). */
        public @NonNull Builder dispute(@NonNull Capture capture, @NonNull BehaviourKey<?> key, @NonNull String identifier,
                                        @NonNull Capture alternative, @NonNull Object values) {
            marks.computeIfAbsent(new MarkKey(capture, key, identifier), k -> new ArrayList<>())
                .add(new Mark(Objects.requireNonNull(alternative), values));
            return this;
        }

        public @NonNull Builder customDefault(@NonNull BehaviourKey<?> key, @NonNull Object value, Provenance.@NonNull Source source,
                                              @NonNull String reference) {
            defaults.put(key, new Default(value, source, reference));
            return this;
        }

        public @NonNull VanillaBehaviour build() {
            finish();
            marks.replaceAll((key, list) -> List.copyOf(list));
            return new VanillaBehaviour(List.copyOf(versions), Map.copyOf(marks), Map.copyOf(defaults));
        }
    }
}
