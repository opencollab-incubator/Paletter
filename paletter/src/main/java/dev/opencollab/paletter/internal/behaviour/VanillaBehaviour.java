package dev.opencollab.paletter.internal.behaviour;

import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.data.DataVersion;
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

    /**
     * A note ({@code alternative} null) or a dispute of a capture, for one key and one block or item: a value or a
     * {@link Cell}, for the versions {@code from} to {@code to} (positions in {@link #versions()}; a block's states may
     * differ between versions, so a mark holds for the versions its cell was made for).
     */
    private record Mark(@Nullable Capture alternative, @NonNull Object value, int from, int to) {
    }

    /** A mark as the generated data gives it, its versions by name. */
    private record Given(MarkKey key, @Nullable Capture alternative, Object value, String from, String to) {
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

    /**
     * Loaded on the first {@link #bundled()}, and the generated classes with it. By name, not by a reference: the library
     * compiles without the generated package, so the generator (which runs on the compiled library) can always write it
     * again (docs/updating.md).
     */
    private static final class Bundled {
        static final VanillaBehaviour DATA = load();

        private static VanillaBehaviour load() {
            try {
                return (VanillaBehaviour) Class.forName(GENERATED).getMethod("load").invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("The generated behaviour data " + GENERATED + " is missing or broken: run "
                    + "./gradlew :tools:generateBehaviour", e);
            }
        }
    }

    /** The generated class that builds the bundled values. */
    public static final String GENERATED = "dev.opencollab.paletter.internal.behaviour.vanilla.BehaviourData";

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
     * @param version the values the capture decided in (a mark may hold for some versions only)
     * @param states the {@code states} compound of a block state; empty for an item
     * @return how the capture's source post-processed its value of the key for a block state or item, or null
     */
    public @Nullable String note(@NonNull VanillaVersion version, @NonNull Capture capture, @NonNull BehaviourKey<?> key,
                                 @NonNull String identifier, @NonNull NbtMap states) {
        int at = versions.indexOf(version);
        String note = null;
        for (Mark mark : marks.getOrDefault(new MarkKey(capture, key, identifier), List.of())) {
            if (mark.alternative() == null && mark.from() <= at && at <= mark.to() && valueOf(mark, states) instanceof String text) {
                note = text;
            }
        }
        return note;
    }

    /** @return what other captures said where they disagree with the capture's value and nothing settles it */
    public @NonNull List<Dispute> disputes(@NonNull VanillaVersion version, @NonNull Capture capture, @NonNull BehaviourKey<?> key,
                                           @NonNull String identifier, @NonNull NbtMap states) {
        int at = versions.indexOf(version);
        List<Dispute> disputes = List.of();
        for (Mark mark : marks.getOrDefault(new MarkKey(capture, key, identifier), List.of())) {
            Object value = mark.alternative() == null || mark.from() > at || at > mark.to() ? null : valueOf(mark, states);
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
        private final List<Given> marks = new ArrayList<>();
        private final Map<BehaviourKey<?>, Default> defaults = new LinkedHashMap<>();
        private final Map<Object, Object> pool = new HashMap<>();
        private final Map<String, BlockSchema.Parsed> schemas = new HashMap<>();
        private @Nullable VanillaVersionBuilder current;

        private Builder() {
        }

        /** @return the builder of the next version, from the values of the version before (none for the first) */
        public @NonNull VanillaVersionBuilder version(@NonNull DataVersion version) {
            finish();
            current = new VanillaVersionBuilder(version, versions.isEmpty() ? null : versions.getLast(), schemas);
            return current;
        }

        private void finish() {
            if (current != null) {
                versions.add(current.build(pool));
                current = null;
            }
        }

        /**
         * Says how the capture's source post-processed its values of the key for a block (a value or a {@link Cell}) or item,
         * in the versions {@code from} to {@code to}.
         */
        public @NonNull Builder note(@NonNull DataVersion from, @NonNull DataVersion to, @NonNull Capture capture, @NonNull BehaviourKey<?> key,
                                     @NonNull String identifier, @NonNull Object note) {
            marks.add(new Given(new MarkKey(capture, key, identifier), null, note, from.name(), to.name()));
            return this;
        }

        /**
         * Says what {@code alternative} said where it disagrees with the capture (a value or a {@link Cell}, null where it does
         * not), in the versions {@code from} to {@code to}.
         */
        public @NonNull Builder dispute(@NonNull DataVersion from, @NonNull DataVersion to, @NonNull Capture capture, @NonNull BehaviourKey<?> key,
                                        @NonNull String identifier, @NonNull Capture alternative, @NonNull Object values) {
            marks.add(new Given(new MarkKey(capture, key, identifier), Objects.requireNonNull(alternative), values, from.name(), to.name()));
            return this;
        }

        public @NonNull Builder customDefault(@NonNull BehaviourKey<?> key, @NonNull Object value, Provenance.@NonNull Source source,
                                              @NonNull String reference) {
            defaults.put(key, new Default(value, source, reference));
            return this;
        }

        public @NonNull VanillaBehaviour build() {
            finish();
            List<String> names = versions.stream().map(v -> v.version().name()).toList();
            Map<MarkKey, List<Mark>> byKey = new HashMap<>();
            for (Given given : marks) {
                int from = names.indexOf(given.from());
                int to = names.indexOf(given.to());
                if (from < 0 || to < from) {
                    throw new IllegalArgumentException("A mark of " + given.key() + " for versions " + given.from() + " to " + given.to()
                        + " of " + names);
                }
                byKey.computeIfAbsent(given.key(), k -> new ArrayList<>()).add(new Mark(given.alternative(), given.value(), from, to));
            }
            byKey.replaceAll((key, list) -> List.copyOf(list));
            return new VanillaBehaviour(List.copyOf(versions), Map.copyOf(byKey), Map.copyOf(defaults));
        }
    }
}
