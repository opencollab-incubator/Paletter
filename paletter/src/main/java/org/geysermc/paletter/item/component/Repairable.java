package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Which items repair an item in an anvil, and by how much (add-on JSON {@code minecraft:repairable}).
 * <pre>{@code
 * Repairable.of()
 *     .repairedBy(Repairable.Entry.items("minecraft:diamond").amount("query.max_durability * 0.25"))
 *     .repairedBy(Repairable.Entry.items("example:sword").amount(100))
 * }</pre>
 *
 * @since 2.0.0
 */
public final class Repairable {
    private final List<Entry> entries;

    private Repairable(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static @NonNull Repairable of(@NonNull Entry... entries) {
        return new Repairable(List.of(entries));
    }

    public @NonNull Repairable repairedBy(@NonNull Entry entry) {
        List<Entry> all = new ArrayList<>(entries);
        all.add(entry);
        return new Repairable(all);
    }

    public @NonNull List<Entry> entries() {
        return entries;
    }

    /**
     * Items that repair, and the amount they repair.
     *
     * @since 2.0.0
     */
    public static final class Entry {
        private final List<String> items;
        private final @Nullable String tags;
        private final @Nullable Float amount;
        private final @Nullable String amountExpression;

        private Entry(List<String> items, @Nullable String tags, @Nullable Float amount, @Nullable String amountExpression) {
            this.items = List.copyOf(items);
            this.tags = tags;
            this.amount = amount;
            this.amountExpression = amountExpression;
        }

        /**
         * @param identifiers the repairing items, e.g. {@code minecraft:diamond}
         */
        public static @NonNull Entry items(@NonNull String... identifiers) {
            return new Entry(List.of(identifiers), null, null, null);
        }

        /**
         * @param types the repairing items, e.g. {@code BedrockItems.DIAMOND}
         * @since 2.0.0
         */
        public static @NonNull Entry items(org.geysermc.paletter.vanilla.@NonNull BedrockItemType... types) {
            String[] identifiers = new String[types.length];
            for (int i = 0; i < types.length; i++) {
                identifiers[i] = types[i].identifier();
            }
            return items(identifiers);
        }

        /**
         * @param molang a Molang item tag query; {@code "1"} matches every item
         */
        public static @NonNull Entry tags(@NonNull String molang) {
            return new Entry(List.of(), Objects.requireNonNull(molang, "molang"), null, null);
        }

        /**
         * @param amount the durability restored; without an amount the client's default applies
         */
        public @NonNull Entry amount(float amount) {
            return new Entry(items, tags, amount, null);
        }

        /**
         * @param molang a Molang expression for the durability restored, e.g. {@code query.max_durability * 0.25}
         */
        public @NonNull Entry amount(@NonNull String molang) {
            return new Entry(items, tags, null, Objects.requireNonNull(molang, "molang"));
        }

        public @NonNull List<String> items() {
            return items;
        }

        public @Nullable String tags() {
            return tags;
        }

        /**
         * @return the fixed amount, or null if there is none (see {@link #amountExpression()})
         */
        public @Nullable Float amount() {
            return amount;
        }

        /**
         * @return the Molang amount, or null if there is none (see {@link #amount()})
         */
        public @Nullable String amountExpression() {
            return amountExpression;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Entry other && items.equals(other.items) && Objects.equals(tags, other.tags)
                && Objects.equals(amount, other.amount) && Objects.equals(amountExpression, other.amountExpression);
        }

        @Override
        public int hashCode() {
            return Objects.hash(items, tags, amount, amountExpression);
        }

        @Override
        public String toString() {
            return "Entry[" + (tags != null ? "tags " + tags : items) + ", amount " + (amount != null ? amount : amountExpression) + "]";
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Repairable other && entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return "Repairable" + entries;
    }
}
