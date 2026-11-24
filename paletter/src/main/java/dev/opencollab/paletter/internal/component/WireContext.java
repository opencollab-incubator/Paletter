package dev.opencollab.paletter.internal.component;

import dev.opencollab.paletter.PaletteReport;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.internal.PaletteReportBuilder;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.data.VanillaItem;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one compilation of components targets: the palette's version (its protocol picks the mapping window), where to
 * report what cannot be expressed, and the registry data transforms resolve descriptors against (whether an item exists,
 * the types of a block's states).
 * <p>
 * Encoders never fail on a value a version cannot express: they leave it out and add a warning to the report.
 */
public final class WireContext {
    private final DataVersion version;
    private final PaletteReportBuilder report;
    private final String source;
    private final String subject;
    private final Registry registry;
    private final @Nullable String minEngineVersion;

    /**
     * Item and block lookups for descriptors.
     */
    public interface Registry {
        boolean itemExists(@NonNull String identifier);

        /**
         * @return the states of the block's first state (their NBT types are the state types), or null if unknown
         */
        @Nullable NbtMap blockStates(@NonNull String identifier);
    }

    private WireContext(DataVersion version, PaletteReportBuilder report, String source, String subject, Registry registry,
                        @Nullable String minEngineVersion) {
        this.version = version;
        this.report = report;
        this.source = source;
        this.subject = subject;
        this.registry = registry;
        this.minEngineVersion = minEngineVersion;
    }

    public static @NonNull WireContext of(@NonNull VanillaData data, @NonNull PaletteReportBuilder report, @NonNull String source) {
        extrapolated(data.version(), report, source);
        return new WireContext(data.version(), report, source, "", registry(data), null);
    }

    public static @NonNull WireContext of(@NonNull DataVersion version, @NonNull PaletteReportBuilder report, @NonNull String source,
                                          @NonNull Registry registry) {
        extrapolated(version, report, source);
        return new WireContext(version, report, source, "", registry, null);
    }

    /** A version newer than every capture gets the newest observed shape: said once per context, not silently. */
    private static void extrapolated(DataVersion version, PaletteReportBuilder report, String source) {
        if (version.protocolVersion() > ComponentIndex.NEWEST_CAPTURED_PROTOCOL) {
            report.info(source, PaletteReport.UNCAPTURED_PROTOCOL, null, "custom item and block components of " + version + " are encoded with the shape observed at protocol "
                + ComponentIndex.NEWEST_CAPTURED_PROTOCOL + ", the newest captured; " + version + " is newer and was not captured");
        }
    }

    /**
     * The registries built so far, by data instance. Compared by identity: there are a few instances (one per version a
     * Paletter compiles), while hashing or comparing one walks all of its data. Copied on write, so the lookup of every
     * palette extension takes no lock.
     */
    private static volatile Cached[] registries = new Cached[0];

    private record Cached(WeakReference<VanillaData> data, Registry registry) {
    }

    /**
     * @return the lookups of a version's vanilla data, built once per data instance
     */
    public static @NonNull Registry registry(@NonNull VanillaData data) {
        Registry registry = cached(registries, data);
        if (registry != null) {
            return registry;
        }
        synchronized (WireContext.class) {
            Cached[] current = registries;
            registry = cached(current, data);
            if (registry == null) {
                registry = build(data);
                List<Cached> kept = new ArrayList<>(current.length + 1);
                for (Cached cached : current) {
                    if (cached.data.get() != null) {
                        kept.add(cached);
                    }
                }
                kept.add(new Cached(new WeakReference<>(data), registry));
                registries = kept.toArray(Cached[]::new);
            }
            return registry;
        }
    }

    private static @Nullable Registry cached(Cached[] registries, VanillaData data) {
        for (Cached cached : registries) {
            if (cached.data.get() == data) {
                return cached.registry;
            }
        }
        return null;
    }

    private static Registry build(VanillaData data) {
        Set<String> items = new HashSet<>();
        for (VanillaItem item : data.items()) {
            items.add(item.identifier());
        }
        Map<String, NbtMap> blocks = new HashMap<>();
        for (NbtMap state : data.blockStates()) {
            blocks.putIfAbsent(state.getString("name"), state.getCompound("states"));
        }
        return new Registry() {
            @Override
            public boolean itemExists(@NonNull String identifier) {
                return items.contains(identifier.indexOf(':') < 0 ? "minecraft:" + identifier : identifier);
            }

            @Override
            public @Nullable NbtMap blockStates(@NonNull String identifier) {
                return blocks.get(identifier.indexOf(':') < 0 ? "minecraft:" + identifier : identifier);
            }
        };
    }

    /**
     * @return a context for one item or block
     */
    public @NonNull WireContext subject(@NonNull String subject) {
        return new WireContext(version, report, source, subject, registry, minEngineVersion);
    }

    /**
     * @param minEngineVersion the {@code min_engine_version} of the pack the content is of (its layer's), null for the newest
     * @return a context for the content of such a pack
     */
    public @NonNull WireContext minEngineVersion(@Nullable String minEngineVersion) {
        return new WireContext(version, report, source, subject, registry, minEngineVersion);
    }

    /** @return the {@code min_engine_version} of the content's pack, null for the newest */
    public @Nullable String minEngineVersion() {
        return minEngineVersion;
    }

    public int protocol() {
        return version.protocolVersion();
    }

    public @NonNull DataVersion version() {
        return version;
    }

    public @NonNull Registry registry() {
        return registry;
    }

    public @NonNull String subject() {
        return subject;
    }

    /**
     * Reports a value that is left out of this version.
     */
    public void drop(@NonNull String component, @NonNull String message) {
        report.warn(source, PaletteReport.COMPONENT_CHANGED, subject.isEmpty() ? null : subject, subject + ": " + component + " " + message + " in " + version + "; left out");
    }

    /**
     * Reports a value that is sent, but not as it was given ({@code message} says as what).
     */
    public void changed(@NonNull String component, @NonNull String message) {
        report.warn(source, PaletteReport.COMPONENT_CHANGED, subject.isEmpty() ? null : subject, subject + ": " + component + " " + message + " in " + version);
    }
}
