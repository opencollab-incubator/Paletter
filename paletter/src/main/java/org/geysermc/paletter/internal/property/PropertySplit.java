package org.geysermc.paletter.internal.property;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.property.Properties;
import org.geysermc.paletter.property.Property;
import org.geysermc.paletter.property.PropertyKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits component or property data as sent to the client into {@link Properties}, named the way the bundled
 * {@code PropertyStore} names them: the entries of {@code components} are {@code component:} properties, every other
 * entry is a {@code wire:} property.
 */
@Internal
public final class PropertySplit {
    private PropertySplit() {
    }

    public static @NonNull Properties split(@Nullable NbtMap data) {
        return Properties.of(properties(data));
    }

    /**
     * @param overlay component values replacing those of the same name
     */
    public static @NonNull Properties split(@Nullable NbtMap data, @NonNull List<NbtMap> overlay) {
        List<Property> result = properties(data);
        for (NbtMap components : overlay) {
            components.forEach((name, value) -> {
                result.removeIf(p -> p.kind() == PropertyKind.COMPONENT && p.name().equals(name));
                result.add(new Property(PropertyKind.COMPONENT, name, value));
            });
        }
        return Properties.of(result);
    }

    private static List<Property> properties(@Nullable NbtMap data) {
        List<Property> result = new ArrayList<>();
        if (data == null) {
            return result;
        }
        data.forEach((key, value) -> {
            if (key.equals("components") && value instanceof NbtMap components) {
                components.forEach((name, component) -> result.add(new Property(PropertyKind.COMPONENT, name, component)));
            } else {
                result.add(new Property(PropertyKind.WIRE, key, value));
            }
        });
        return result;
    }
}
