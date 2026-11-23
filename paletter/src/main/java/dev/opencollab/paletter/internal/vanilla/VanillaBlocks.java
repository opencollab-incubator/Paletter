package dev.opencollab.paletter.internal.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The generated vanilla block definitions of the bundled data versions ({@code internal.vanilla.blocks}, written by
 * {@code :tools:generateBlocks}): the block states of a version are derived from them, not read from its data file.
 * Built on each call, so nothing is kept once the palette is read. Not API.
 */
public final class VanillaBlocks {
    /** The generated class, loaded by name. */
    private static final String DATA = "dev.opencollab.paletter.internal.vanilla.blocks.BlockData";

    private VanillaBlocks() {
    }

    /**
     * What the generated {@code BlockData} fills: one set of definitions per version, oldest first, each starting as a
     * copy of the version before.
     */
    public static final class Versions {
        private final Map<String, BlockDefinitions> versions = new LinkedHashMap<>();
        private @Nullable BlockDefinitions last;

        /** @return the definitions of the next version, a copy of the version before (empty for the first) */
        public @NonNull BlockDefinitions version(@NonNull String name) {
            BlockDefinitions next = last == null ? new BlockDefinitions() : last.copy();
            if (versions.put(name, next) != null) {
                throw new IllegalStateException("Version " + name + " is generated twice");
            }
            last = next;
            return next;
        }

        public @NonNull Map<String, BlockDefinitions> versions() {
            return versions;
        }
    }

    /**
     * @return the definitions of every generated version, by name, oldest first; none if the generated classes are missing
     * (the library compiles without them, so the generator can run on data that carries its block states)
     */
    public static @NonNull Map<String, BlockDefinitions> all() {
        Versions versions = new Versions();
        Method load;
        try {
            load = Class.forName(DATA).getMethod("load", Versions.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return Map.of();
        }
        try {
            load.invoke(null, versions);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("The generated block definitions do not build", e.getCause());
        }
        return versions.versions();
    }

    /** @return the names of the generated versions, oldest first */
    public static @NonNull List<String> versions() {
        return new ArrayList<>(all().keySet());
    }

    /** @return the palette of a generated version, or null if the version has no generated definitions */
    public static BlockDefinitions.@Nullable Palette palette(@NonNull String version) {
        BlockDefinitions definitions = all().get(version);
        return definitions == null ? null : definitions.derive();
    }
}
