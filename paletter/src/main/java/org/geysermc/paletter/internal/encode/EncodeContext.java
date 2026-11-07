package org.geysermc.paletter.internal.encode;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.geysermc.paletter.PaletteReport;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.wire.BedrockComponent;
import org.geysermc.paletter.internal.wire.ComponentType;
import org.geysermc.paletter.internal.wire.block.BlockComponentTypes;
import org.geysermc.paletter.internal.wire.item.ItemComponentTypes;

import java.util.List;
import java.util.function.Supplier;

/**
 * What one encoding targets: the protocol version of the palette, and where to report what cannot be expressed in it.
 * <p>
 * Encoders never fail on a semantic value a version cannot express: they leave it out and add a warning to the report.
 * Every component goes through {@link #put}, which picks the generated shape whose protocol window covers the target.
 */
public final class EncodeContext {
    private final DataVersion version;
    private final PaletteReport report;
    private final String source;

    private EncodeContext(DataVersion version, PaletteReport report, String source) {
        this.version = version;
        this.report = report;
        this.source = source;
    }

    /**
     * @param version the data version of the palette being compiled
     * @param report the palette's report
     * @param source what the encoded content comes from, typically a layer id
     */
    public static @NonNull EncodeContext of(@NonNull DataVersion version, @NonNull PaletteReport report, @NonNull String source) {
        return new EncodeContext(version, report, source);
    }

    public int protocol() {
        return version.protocolVersion();
    }

    public @NonNull DataVersion version() {
        return version;
    }

    /**
     * Reports a value that is left out of this version.
     *
     * @param subject the item or block, e.g. {@code example:ruby}
     */
    public void drop(@NonNull String subject, @NonNull String message) {
        report.warn(source, subject + ": " + message + " in " + version + "; left out");
    }

    /**
     * Writes a component in the shape {@code type}, if that shape is the one for this version. Otherwise reports and
     * drops it: either the version has no such component, or its shape for this version is one this encoder does not
     * produce yet (a new spec entry without an encoder update, which the encoder tests catch).
     *
     * @return whether the component was written
     */
    public boolean put(@NonNull NbtMapBuilder out, @NonNull String subject, @NonNull ComponentType<?> type,
                       @NonNull Supplier<? extends BedrockComponent> value) {
        if (type.supports(protocol())) {
            out.putCompound(type.name(), value.get().toNbt());
            return true;
        }
        unsupported(subject, type);
        return false;
    }

    /**
     * Reports that the shape {@code type} does not apply to this version.
     */
    public void unsupported(@NonNull String subject, @NonNull ComponentType<?> type) {
        List<ComponentType<?>> windows = type.kind() == ComponentType.Kind.ITEM
            ? ItemComponentTypes.windows(type.name()) : BlockComponentTypes.windows(type.name());
        boolean otherShape = windows.stream().anyMatch(window -> window.supports(protocol()));
        drop(subject, type.name() + (otherShape
            ? " has a network shape Paletter does not encode yet"
            : " is not supported"));
    }

    /**
     * @return whether the shape {@code type} applies to this version, reporting it if not
     */
    public boolean supports(@NonNull String subject, @NonNull ComponentType<?> type) {
        if (type.supports(protocol())) {
            return true;
        }
        unsupported(subject, type);
        return false;
    }
}
