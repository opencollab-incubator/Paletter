package org.geysermc.paletter.block;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.PaletteException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Dense, version independent block state indices ("canonical" ids). Every state of the newest bundled version gets
 * one, in that version's vanilla palette order, so they are stable for a Paletter release. Custom blocks of layers are
 * appended, in layer order, per lineage of layers: the same layer stack gives the same indices in every version.
 * <p>
 * A consumer maps its own blocks to canonical ids once, and uses {@link org.geysermc.paletter.Palette#runtimeId(int)}
 * (an array lookup) to get the runtime id of a particular palette.
 *
 * @since 2.0.0
 */
public final class CanonicalBlocks {
    private final @Nullable CanonicalBlocks parent;
    private final int offset;
    private final NbtMap[] states;
    private final Object2IntMap<NbtMap> index;
    private final Map<Object, CanonicalBlocks> extensions = new WeakHashMap<>();

    private CanonicalBlocks(@Nullable CanonicalBlocks parent, List<NbtMap> states) {
        this.parent = parent;
        this.offset = parent == null ? 0 : parent.size();
        this.states = states.toArray(NbtMap[]::new);
        Object2IntOpenHashMap<NbtMap> index = new Object2IntOpenHashMap<>(states.size());
        index.defaultReturnValue(-1);
        for (int i = 0; i < this.states.length; i++) {
            NbtMap state = BlockStates.normalize(this.states[i]);
            this.states[i] = state;
            if (index.put(state, offset + i) != -1 || (parent != null && parent.index(state) != -1)) {
                throw new PaletteException("Duplicate canonical block state " + state);
            }
        }
        index.trim();
        this.index = index;
    }

    /**
     * @param states the states of the newest version, in its vanilla runtime id order
     */
    public static @NonNull CanonicalBlocks of(@NonNull List<NbtMap> states) {
        return new CanonicalBlocks(null, states);
    }

    /**
     * @param key identifies the extension (e.g. the layer adding the states); the same key returns the same instance
     *            for as long as it is referenced
     * @return these indices with the given states appended, or this if there are none
     */
    public @NonNull CanonicalBlocks extend(@NonNull Object key, @NonNull List<CustomBlock> blocks) {
        if (blocks.isEmpty()) {
            return this;
        }
        synchronized (extensions) {
            CanonicalBlocks existing = extensions.get(key);
            if (existing == null) {
                List<NbtMap> states = new ArrayList<>();
                for (CustomBlock block : blocks) {
                    states.addAll(block.states());
                }
                existing = new CanonicalBlocks(this, states);
                extensions.put(key, existing);
            }
            return existing;
        }
    }

    /**
     * @return the amount of canonical ids; valid ids are {@code 0} to {@code size() - 1}
     */
    public int size() {
        return offset + states.length;
    }

    /**
     * @return the amount of canonical ids of vanilla (newest version) states; custom states follow them
     */
    public int vanillaSize() {
        return parent == null ? states.length : parent.vanillaSize();
    }

    /**
     * @param state a block state as {@code {name, states}}; extra keys like {@code version} are ignored
     * @return its canonical id, or -1 if it is not a state of the newest version or of a custom block of this lineage
     */
    public int index(@NonNull NbtMap state) {
        NbtMap normalized = BlockStates.normalize(state);
        CanonicalBlocks current = this;
        while (current != null) {
            int found = current.index.getInt(normalized);
            if (found != -1) {
                return found;
            }
            current = current.parent;
        }
        return -1;
    }

    /**
     * @return the state of a canonical id
     * @throws IndexOutOfBoundsException if there is no such id
     */
    public @NonNull NbtMap state(int canonical) {
        if (canonical < 0) {
            throw new IndexOutOfBoundsException(canonical);
        }
        CanonicalBlocks current = this;
        while (canonical < current.offset) {
            current = current.parent;
        }
        return current.states[canonical - current.offset];
    }

    /**
     * @return true if the other indices are these, or these with states appended
     */
    public boolean extendsOrIs(@NonNull CanonicalBlocks other) {
        CanonicalBlocks current = this;
        while (current != null) {
            if (current == other) {
                return true;
            }
            current = current.parent;
        }
        return false;
    }

    @Override
    public String toString() {
        return "CanonicalBlocks{" + size() + " states, " + (size() - vanillaSize()) + " custom}";
    }
}
