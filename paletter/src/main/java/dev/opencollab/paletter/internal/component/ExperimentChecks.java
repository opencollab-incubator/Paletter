package dev.opencollab.paletter.internal.component;

import dev.opencollab.paletter.PaletteReport;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.internal.PaletteReportBuilder;
import dev.opencollab.paletter.item.CustomItem;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports custom content that uses a component a palette's Bedrock version takes only with an experiment toggle the palette
 * does not enable ({@link ComponentIndex#GATED}, from the {@code spec-gating} captures). The component is still sent: what a
 * client does with it without the toggle is not captured. Not API.
 */
public final class ExperimentChecks {
    private ExperimentChecks() {
    }

    /**
     * @param protocol the first protocol of the palette's data version
     * @param enabled the experiment toggles the palette enables
     */
    public static void report(@NonNull List<CustomBlock> blocks, @NonNull List<CustomItem> items, int protocol,
                              @NonNull List<String> enabled, @NonNull PaletteReportBuilder report, @NonNull String source) {
        if (ComponentIndex.GATED.isEmpty()) {
            return;
        }
        for (CustomBlock block : blocks) {
            List<Object> components = new ArrayList<>(block.components());
            components.addAll(block.traits());
            block.permutations().forEach(permutation -> components.addAll(permutation.components()));
            check(block.identifier(), components, protocol, enabled, report, source);
        }
        for (CustomItem item : items) {
            check(item.identifier(), new ArrayList<>(item.components()), protocol, enabled, report, source);
        }
    }

    private static void check(String identifier, List<Object> components, int protocol, List<String> enabled,
                              PaletteReportBuilder report, String source) {
        for (Object component : components) {
            Map<Integer, String> gated = ComponentIndex.GATED.get(component.getClass());
            String toggle = gated == null ? null : gated.get(protocol);
            if (toggle != null && !enabled.contains(toggle)) {
                report.warn(source, PaletteReport.EXPERIMENT, identifier, identifier + ": " + component.getClass().getSimpleName() + " needs the experiment " + toggle
                    + " in this version (BDS rejects it without), which the palette does not enable; it is sent anyway");
            }
        }
    }
}
