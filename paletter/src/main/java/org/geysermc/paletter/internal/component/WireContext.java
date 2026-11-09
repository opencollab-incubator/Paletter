package org.geysermc.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.PaletteReport;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;

import java.util.HashMap;
import java.util.HashSet;
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
    private final PaletteReport report;
    private final String source;
    private final String subject;
    private final Registry registry;

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

    private WireContext(DataVersion version, PaletteReport report, String source, String subject, Registry registry) {
        this.version = version;
        this.report = report;
        this.source = source;
        this.subject = subject;
        this.registry = registry;
    }

    public static @NonNull WireContext of(@NonNull VanillaData data, @NonNull PaletteReport report, @NonNull String source) {
        return new WireContext(data.version(), report, source, "", registry(data));
    }

    public static @NonNull WireContext of(@NonNull DataVersion version, @NonNull PaletteReport report, @NonNull String source,
                                          @NonNull Registry registry) {
        return new WireContext(version, report, source, "", registry);
    }

    private static final Map<VanillaData, Registry> REGISTRIES = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * @return the lookups of a version's vanilla data, built once per data instance
     */
    public static @NonNull Registry registry(@NonNull VanillaData data) {
        return REGISTRIES.computeIfAbsent(data, d -> {
            Set<String> items = new HashSet<>();
            for (VanillaItem item : d.items()) {
                items.add(item.identifier());
            }
            Map<String, NbtMap> blocks = new HashMap<>();
            for (NbtMap state : d.blockStates()) {
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
        });
    }

    /**
     * @return a context for one item or block
     */
    public @NonNull WireContext subject(@NonNull String subject) {
        return new WireContext(version, report, source, subject, registry);
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
        report.warn(source, subject + ": " + component + " " + message + " in " + version + "; left out");
    }
}
