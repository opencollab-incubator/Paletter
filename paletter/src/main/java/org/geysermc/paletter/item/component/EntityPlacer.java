package org.geysermc.paletter.item.component;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Objects;

/**
 * Makes an item place an entity (add-on JSON {@code minecraft:entity_placer}).
 *
 * @since 2.0.0
 */
public final class EntityPlacer {
    private final String entity;
    private final List<BlockDescriptor> useOn;
    private final List<BlockDescriptor> dispenseOn;

    private EntityPlacer(String entity, List<BlockDescriptor> useOn, List<BlockDescriptor> dispenseOn) {
        this.entity = Objects.requireNonNull(entity, "entity");
        this.useOn = List.copyOf(useOn);
        this.dispenseOn = List.copyOf(dispenseOn);
    }

    /**
     * @param entity the entity identifier, e.g. {@code minecraft:minecart}
     * @return a placer usable on any block
     */
    public static @NonNull EntityPlacer of(@NonNull String entity) {
        return new EntityPlacer(entity, List.of(), List.of());
    }

    /**
     * @param blocks the blocks the item can be used on; none means any
     */
    public @NonNull EntityPlacer useOn(@NonNull BlockDescriptor... blocks) {
        return new EntityPlacer(entity, List.of(blocks), dispenseOn);
    }

    /**
     * @param blocks the blocks a dispenser can place the entity on; none means any
     */
    public @NonNull EntityPlacer dispenseOn(@NonNull BlockDescriptor... blocks) {
        return new EntityPlacer(entity, useOn, List.of(blocks));
    }

    public @NonNull String entity() {
        return entity;
    }

    public @NonNull List<BlockDescriptor> useOn() {
        return useOn;
    }

    public @NonNull List<BlockDescriptor> dispenseOn() {
        return dispenseOn;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EntityPlacer other && entity.equals(other.entity) && useOn.equals(other.useOn) && dispenseOn.equals(other.dispenseOn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entity, useOn, dispenseOn);
    }

    @Override
    public String toString() {
        return "EntityPlacer[" + entity + ", use on " + useOn + ", dispense on " + dispenseOn + "]";
    }
}
