package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.data.DataVersion;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The store's lookup ({@link BehaviourStore#block}, {@link BehaviourStore#item}: the newest capture covering an id decides)
 * with one rule on top: a value a direct BDS capture gave is never replaced by a third-party dump. Where the newest
 * capture is a dump of a newer version and says something else than the newest direct capture of an older version, the
 * BDS value stays (inherited, with that capture as provenance) and the dump's value is a dispute of it. Such a value
 * needs a new BDS capture: the report lists it. Where the dump says the same, it decides as before.
 */
final class BdsFirst {
    /** A value the rule kept: what the dump said instead. */
    record Kept(BehaviourStore.Resolved kept, BehaviourStore.Capture dump, Object dumpValue) {
    }

    private BdsFirst() {
    }

    static BehaviourStore.@Nullable Resolved block(BehaviourStore store, BehaviourKey<?> key, int hash, DataVersion version) {
        Kept kept = blockKept(store, key, hash, version);
        return kept != null ? kept.kept() : store.block(key, hash, version);
    }

    static BehaviourStore.@Nullable Resolved item(BehaviourStore store, BehaviourKey<?> key, String identifier, DataVersion version) {
        Kept kept = itemKept(store, key, identifier, version);
        return kept != null ? kept.kept() : store.item(key, identifier, version);
    }

    /** @return the value the rule keeps for a block state, or null where the store's own lookup stands */
    static @Nullable Kept blockKept(BehaviourStore store, BehaviourKey<?> key, int hash, DataVersion version) {
        BehaviourStore.Resolved newest = store.block(key, hash, version);
        if (newest == null || !newest.capture().source().thirdParty()) {
            return null;
        }
        for (int c = store.captures().size() - 1; c >= 0; c--) {
            BehaviourStore.Capture direct = store.captures().get(c);
            if (direct.source().thirdParty() || !older(direct, newest.capture())) {
                continue;
            }
            Object value = store.captured(c, key, hash);
            if (value != null) {
                return value.equals(newest.value()) ? null
                    : keep(value, direct, store.block(key, hash, direct.version()), newest);
            }
        }
        return null;
    }

    /** @return the value the rule keeps for an item, or null where the store's own lookup stands */
    static @Nullable Kept itemKept(BehaviourStore store, BehaviourKey<?> key, String identifier, DataVersion version) {
        BehaviourStore.Resolved newest = store.item(key, identifier, version);
        if (newest == null || !newest.capture().source().thirdParty()) {
            return null;
        }
        for (int c = store.captures().size() - 1; c >= 0; c--) {
            BehaviourStore.Capture direct = store.captures().get(c);
            if (direct.source().thirdParty() || !older(direct, newest.capture())) {
                continue;
            }
            Object value = store.captured(c, key, identifier);
            if (value != null) {
                return value.equals(newest.value()) ? null
                    : keep(value, direct, store.item(key, identifier, direct.version()), newest);
            }
        }
        return null;
    }

    private static boolean older(BehaviourStore.Capture direct, BehaviourStore.Capture dump) {
        return direct.version().protocolVersion() < dump.version().protocolVersion();
    }

    /** @param atItsVersion the lookup in the direct capture's own version: its note and disputes where it decided there */
    private static Kept keep(Object value, BehaviourStore.Capture direct, BehaviourStore.@Nullable Resolved atItsVersion,
                             BehaviourStore.Resolved newest) {
        boolean decided = atItsVersion != null && atItsVersion.capture() == direct;
        List<BehaviourStore.Dispute> disputes = new ArrayList<>(decided ? atItsVersion.disputes() : List.of());
        disputes.add(new BehaviourStore.Dispute(newest.capture(), newest.value()));
        return new Kept(new BehaviourStore.Resolved(value, direct, decided ? atItsVersion.postProcessing() : null, List.copyOf(disputes)),
            newest.capture(), newest.value());
    }
}
