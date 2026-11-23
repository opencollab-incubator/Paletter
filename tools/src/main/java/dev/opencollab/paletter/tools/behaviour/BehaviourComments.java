package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.tools.behaviour.CellFit.Constant;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The comment of each change of a version's class: what changed, the capture that decided it and the other captures of the
 * version that confirm it (or say otherwise). A change no direct BDS capture decided is {@code SOURCE ONLY}: only a
 * third-party dump says the value changed. It is used like any value (BDS evidence would win, and there is none; the
 * version before is no evidence for this one), and listed in the report for a human to check.
 */
final class BehaviourComments {
    private static final Pattern BDS = Pattern.compile("data/bds/([^/ ]+)/");
    private static final Pattern COMMIT = Pattern.compile("^(.*)@([0-9a-f]{7})[0-9a-f]*$");
    private static final Pattern PART = Pattern.compile("\\(([^()]*)\\)$");

    /** A change of a value that only third-party dumps decided. */
    record SourceOnly(String version, BehaviourKey<?> key, String id, String change, String deciders) {
    }

    /** One changed value: the capture that decided it (-1: none), the state hash or item, and the value. */
    record Decided(int capture, int hash, @Nullable String item, @Nullable Object value) {
    }

    private final BehaviourStore store;
    private final Function<Object, String> format;
    final List<SourceOnly> sourceOnly = new ArrayList<>();

    /** @param format how a value reads in a comment */
    BehaviourComments(BehaviourStore store, Function<Object, String> format) {
        this.store = store;
        this.format = format;
    }

    /** @return a capture in a few words: {@code BDS Script API dump 1.26.63.1}, {@code CloudburstMC/Data 0021592} */
    static String label(BehaviourStore.Capture capture) {
        String reference = capture.reference();
        Matcher bds = BDS.matcher(reference);
        String build = bds.find() ? bds.group(1) : capture.version().name();
        Matcher part = PART.matcher(reference);
        return switch (capture.source()) {
            case SCRIPT_API -> "BDS Script API dump " + build;
            case NATIVE -> "BDS native capture " + build;
            case BDS_PROBE -> "BDS behaviour probe " + build + (part.find() ? " (" + part.group(1) + ")" : "");
            default -> {
                Matcher commit = COMMIT.matcher(reference);
                yield commit.matches() ? commit.group(1) + " " + commit.group(2) : reference;
            }
        };
    }

    /** @return the values of a block's key that differ from {@code before} (every state if it is null) */
    static List<Decided> changed(BehaviourModel.@Nullable Block before, BehaviourModel.Block after, int k) {
        List<Decided> changed = new ArrayList<>();
        for (int s = 0; s < after.hashes().length; s++) {
            if (before == null || !Objects.equals(before.values()[k][s], after.values()[k][s])) {
                changed.add(new Decided(after.captures()[k][s], after.hashes()[s], null, after.values()[k][s]));
            }
        }
        return changed;
    }

    /** @return what a block's key changed from and to, for the comment */
    String what(BehaviourModel.@Nullable Block before, BehaviourModel.Block after, int k, int changed) {
        String name = BehaviourModel.BLOCK_KEYS.get(k).name();
        Object was = before == null ? new Constant(null) : before.fits()[k];
        if (was instanceof Constant old && after.fits()[k] instanceof Constant now) {
            return name + " " + format(old.value()) + " -> " + format(now.value());
        }
        return name + " per state (" + changed + " of " + after.hashes().length + " states)";
    }

    /** @return what an item's key changed from and to */
    String what(Object @Nullable [] before, BehaviourModel.Item after, int k) {
        return BehaviourModel.ITEM_KEYS.get(k).name() + " " + format(before == null ? null : before[k]) + " -> " + format(after.values()[k]);
    }

    /**
     * @param id the block or item, or what the change is of ("every block")
     * @return the comment: the change, who decided it, who confirms it or says otherwise, and SOURCE ONLY if no direct capture decided
     */
    String comment(BehaviourModel.Version version, BehaviourKey<?> key, String id, String what, List<Decided> decided) {
        Map<String, Integer> deciders = new LinkedHashMap<>();
        boolean thirdPartyOnly = true;
        for (Decided one : decided) {
            if (one.capture() >= 0) {
                BehaviourStore.Capture capture = store.captures().get(one.capture());
                thirdPartyOnly &= capture.source().thirdParty();
                deciders.merge(label(capture), 1, Integer::sum);
            }
        }
        if (deciders.isEmpty()) {
            return what + ": no capture of " + version.version().name() + " or before has it";
        }
        StringBuilder text = new StringBuilder(what).append(": ").append(String.join(", ", deciders.size() == 1 ? deciders.keySet()
            : deciders.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")").toList()));
        Map<String, Boolean> others = new TreeMap<>();
        for (BehaviourStore.Capture other : store.captures()) {
            if (!other.version().name().equals(version.version().name()) || deciders.containsKey(label(other))) {
                continue;
            }
            for (Decided one : decided) {
                Object said = one.item() != null ? store.captured(other.index(), key, one.item()) : store.captured(other.index(), key, one.hash());
                if (said != null && one.value() != null) {
                    others.merge(label(other), said.equals(one.value()), Boolean::logicalAnd);
                }
            }
        }
        List<String> confirmed = others.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
        List<String> otherwise = others.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList();
        if (!confirmed.isEmpty()) {
            text.append(", confirmed by ").append(String.join(", ", confirmed));
        }
        if (!otherwise.isEmpty()) {
            text.append("; ").append(String.join(", ", otherwise)).append(" says otherwise");
        }
        if (thirdPartyOnly) {
            text.append("; SOURCE ONLY (no BDS capture)");
            sourceOnly.add(new SourceOnly(version.version().name(), key, id, what, String.join(", ", deciders.keySet())));
        }
        return text.toString();
    }

    private String format(@Nullable Object value) {
        return value == null ? "unknown" : format.apply(value);
    }
}
