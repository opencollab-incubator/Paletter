package org.geysermc.paletter.internal.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.geysermc.paletter.item.RegisteredItem;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Shares equal vanilla {@link RegisteredItem}s between palettes, also across versions: an item that is unchanged between
 * two versions (same identifier, runtime id, version and component data) is one instance. Values are weak, so items of
 * palettes nobody references any more can be collected.
 */
public final class ItemInterner {
    private static final Map<Key, Ref> ITEMS = new ConcurrentHashMap<>();
    private static final ReferenceQueue<RegisteredItem> QUEUE = new ReferenceQueue<>();

    private ItemInterner() {
    }

    /**
     * @param create makes the item if there is no equal one alive
     * @return the shared item for these values
     */
    public static @NonNull RegisteredItem intern(@NonNull String identifier, int runtimeId, @NonNull ItemVersion version,
                                                 boolean componentBased, @Nullable NbtMap componentData,
                                                 @NonNull Supplier<RegisteredItem> create) {
        expunge();
        Key key = new Key(identifier, runtimeId, version, componentBased, componentData);
        while (true) {
            Ref existing = ITEMS.get(key);
            RegisteredItem item = existing == null ? null : existing.get();
            if (item != null) {
                return item;
            }
            RegisteredItem fresh = create.get();
            Ref ref = new Ref(key, fresh);
            if (existing == null ? ITEMS.putIfAbsent(key, ref) == null : ITEMS.replace(key, existing, ref)) {
                return fresh;
            }
        }
    }

    private static void expunge() {
        Ref stale;
        while ((stale = (Ref) QUEUE.poll()) != null) {
            ITEMS.remove(stale.key, stale);
        }
    }

    private record Key(String identifier, int runtimeId, ItemVersion version, boolean componentBased, @Nullable NbtMap componentData) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && runtimeId == other.runtimeId && componentBased == other.componentBased
                && version == other.version && identifier.equals(other.identifier) && Objects.equals(componentData, other.componentData);
        }

        @Override
        public int hashCode() {
            return Objects.hash(identifier, runtimeId, version, componentBased, componentData);
        }
    }

    private static final class Ref extends WeakReference<RegisteredItem> {
        private final Key key;

        Ref(Key key, RegisteredItem item) {
            super(item, QUEUE);
            this.key = key;
        }
    }
}
