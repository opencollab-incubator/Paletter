package dev.opencollab.paletter.tools.data;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.data.VanillaItem;
import dev.opencollab.paletter.tools.client.CapturedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.opencollab.paletter.tools.data.DiffText.*;

/** A network capture against a bundled version: items, item components, block properties, creative layout. See {@link Diff#capture}. */
final class CaptureDiff extends DiffBuilder {
    static Diff of(CapturedData capture, VanillaData reference) {
        CaptureDiff capturing = new CaptureDiff();
        capturing.captureItems(capture, reference);
        capturing.captureBlocks(capture, reference);
        capturing.captureCreative(capture, reference);
        return capturing.diff;
    }

    private void captureItems(CapturedData capture, VanillaData reference) {
        Map<String, VanillaItem> bundled = new LinkedHashMap<>();
        reference.items().forEach(item -> bundled.put(item.identifier(), item));
        putIfAny("items only in the capture", difference(capture.items().keySet(), bundled.keySet()));
        putIfAny("items only in the bundled data", difference(bundled.keySet(), capture.items().keySet()));
        List<String> ids = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        List<String> components = new ArrayList<>();
        capture.items().forEach((name, wire) -> {
            VanillaItem item = bundled.get(name);
            if (item == null) {
                return;
            }
            if (item.runtimeId() != wire.getShort("id")) {
                ids.add(name + ": " + item.runtimeId() + " -> " + wire.getShort("id"));
            }
            if (item.version() != wire.getInt("version") || item.componentBased() != wire.getBoolean("componentBased")) {
                flags.add(name + ": version " + item.version() + " -> " + wire.getInt("version")
                    + ", componentBased " + item.componentBased() + " -> " + wire.getBoolean("componentBased"));
            }
            NbtMap bundledData = item.componentData() == null ? NbtMap.EMPTY : item.componentData();
            List<String> diff = new ArrayList<>();
            nbt("", bundledData, wire.getCompound("data"), diff);
            if (!diff.isEmpty()) {
                components.add(name + ": " + String.join("; ", diff));
            }
        });
        putIfAny("item runtime id changes", ids);
        putIfAny("item version/componentBased changes", flags);
        putIfAny("item component data changes", components);
    }

    private void captureBlocks(CapturedData capture, VanillaData reference) {
        Map<String, NbtMap> bundled = new LinkedHashMap<>();
        for (BlockPropertyData block : reference.dataDrivenBlocks()) {
            bundled.put(block.getName(), block.getProperties());
        }
        putIfAny("block properties only in the capture", difference(capture.blockProperties().keySet(), bundled.keySet()));
        putIfAny("block properties only in the bundled data", difference(bundled.keySet(), capture.blockProperties().keySet()));
        List<String> changes = new ArrayList<>();
        capture.blockProperties().forEach((name, wire) -> {
            NbtMap properties = bundled.get(name);
            if (properties != null) {
                List<String> diff = new ArrayList<>();
                nbt("", properties, wire, diff);
                if (!diff.isEmpty()) {
                    changes.add(name + ": " + String.join("; ", diff));
                }
            }
        });
        putIfAny("block property changes", changes);
    }

    private void captureCreative(CapturedData capture, VanillaData reference) {
        for (CreativeCategory category : CreativeCategory.values()) {
            List<String> wire = flatten(capture.creative().nodes(category));
            List<String> bundled = flatten(reference.creative().nodes(category));
            if (wire.equals(bundled)) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            lines.add("size " + bundled.size() + " -> " + wire.size());
            Set<String> added = difference(new LinkedHashSet<>(wire), new LinkedHashSet<>(bundled));
            Set<String> removed = difference(new LinkedHashSet<>(bundled), new LinkedHashSet<>(wire));
            added.forEach(entry -> lines.add("+ " + entry));
            removed.forEach(entry -> lines.add("- " + entry));
            if (added.isEmpty() && removed.isEmpty()) {
                for (int i = 0; i < Math.min(wire.size(), bundled.size()); i++) {
                    if (!wire.get(i).equals(bundled.get(i))) {
                        lines.add("order differs from #" + i + ": " + bundled.get(i) + " -> " + wire.get(i));
                        break;
                    }
                }
            }
            putIfAny("creative " + category, lines);
        }
    }
}
