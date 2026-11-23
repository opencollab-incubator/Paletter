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
 * version that confirm it (or say otherwise), and what a human should look at, which the report lists too
 * ({@link Flag}): a change only a third-party dump decided ({@code SOURCE ONLY}, also for some of the values only), a
 * value a dump gives where nothing did before (first coverage, not a change), and a change one source makes and takes back
 * in the next version (a suspected artefact of the dump).
 */
final class BehaviourComments {
    private static final Pattern BDS = Pattern.compile("data/bds/([^/ ]+)/");
    private static final Pattern COMMIT = Pattern.compile("^(.*)@([0-9a-f]{7})[0-9a-f]*$");
    private static final Pattern PART = Pattern.compile("\\(([^()]*)\\)$");

    /** What a flagged change is. */
    enum Kind {
        SOURCE_ONLY("Changes only a third-party dump decided"),
        PARTLY_SOURCE_ONLY("Changes a third-party dump decided for some of the values"),
        FIRST_COVERAGE("Values only a third-party dump gives, where nothing did before (first coverage, no change)"),
        SUSPECTED_ARTEFACT("Changes one dump makes and takes back in the next version (suspected artefacts of the dump)");

        final String title;

        Kind(String title) {
            this.title = title;
        }
    }

    /** A change for the report. {@code change} is in the report's words (shapes as coordinates). */
    record Flag(Kind kind, String version, BehaviourKey<?> key, String id, String change, String deciders) {
    }

    /** One changed value: the capture that decided it (-1: none), the state hash or item, and the value. */
    record Decided(int capture, int hash, @Nullable String item, @Nullable Object value) {
    }

    private final BehaviourModel model;
    private final Function<Object, String> format;
    private final Function<Object, String> reportFormat;
    final List<Flag> flags = new ArrayList<>();

    /** @param format how a value reads in a comment; {@code reportFormat} in the report */
    BehaviourComments(BehaviourModel model, Function<Object, String> format, Function<Object, String> reportFormat) {
        this.model = model;
        this.format = format;
        this.reportFormat = reportFormat;
    }

    /**
     * @return text that is safe in a {@code //} comment or a Javadoc: a backslash doubled (so no Unicode escape starts),
     * a character outside printable ASCII as {@code <U+000A>}, and no {@code *}{@code /}
     */
    static String safe(String text) {
        return dev.opencollab.paletter.tools.codegen.Comments.safe(text);
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

    /** @return the properties of a state as {@code [growth=1]}, empty for none */
    static String describe(Map<String, Object> properties) {
        if (properties.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("[");
        properties.forEach((name, value) -> out.append(out.length() == 1 ? "" : ",").append(name.replace("minecraft:", "")).append('=').append(value));
        return out.append(']').toString();
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

    /** @return what a block's key changed from and to, in the code's words or the report's */
    String what(BehaviourModel.@Nullable Block before, BehaviourModel.Block after, int k, int changed, boolean report) {
        String name = BehaviourModel.BLOCK_KEYS.get(k).name();
        Object was = before == null ? new Constant(null) : before.fits()[k];
        if (was instanceof Constant old && after.fits()[k] instanceof Constant now) {
            return name + " " + format(old.value(), k, false, report) + " -> " + format(now.value(), k, false, report);
        }
        return name + " per state (" + changed + " of " + after.hashes().length + " states)";
    }

    /** @return what an item's key changed from and to */
    String what(Object @Nullable [] before, BehaviourModel.Item after, int k, boolean report) {
        return BehaviourModel.ITEM_KEYS.get(k).name() + " " + format(before == null ? null : before[k], k, true, report) + " -> "
            + format(after.values()[k], k, true, report);
    }

    /**
     * @param id the block or item, or what the change is of ("every block")
     * @param firstCoverage whether nothing was known of the changed values before: a first coverage, not a change
     * @param flip what {@link #flip} found, or null
     * @return the comment: the change, who decided it, who confirms it or says otherwise, and its flags
     */
    String comment(BehaviourModel.Version version, BehaviourKey<?> key, String id, String what, String reportWhat, List<Decided> decided,
                   boolean firstCoverage, @Nullable String flip) {
        Map<String, Integer> deciders = new LinkedHashMap<>();
        int thirdParty = 0;
        int known = 0;
        for (Decided one : decided) {
            if (one.capture() >= 0) {
                BehaviourStore.Capture capture = model.store.captures().get(one.capture());
                known++;
                thirdParty += capture.source().thirdParty() ? 1 : 0;
                deciders.merge(label(capture), 1, Integer::sum);
            }
        }
        if (deciders.isEmpty()) {
            return what + ": no capture of " + version.version().name() + " or before has it";
        }
        String deciding = String.join(", ", deciders.size() == 1 ? deciders.keySet()
            : deciders.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")").toList());
        StringBuilder text = new StringBuilder(firstCoverage ? "first coverage, " + what.replace(" unknown -> ", " = ") : what)
            .append(": ").append(deciding);
        Map<String, Boolean> others = new TreeMap<>();
        for (BehaviourStore.Capture other : model.store.captures()) {
            if (!other.version().name().equals(version.version().name()) || deciders.containsKey(label(other))) {
                continue;
            }
            for (Decided one : decided) {
                Object said = one.item() != null ? model.store.captured(other.index(), key, one.item())
                    : model.store.captured(other.index(), key, one.hash());
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
        String flagged = firstCoverage ? reportWhat.replace(" unknown -> ", " = ") : reportWhat;
        if (thirdParty == known) {
            text.append(firstCoverage ? "; only a third-party dump has it" : "; SOURCE ONLY (no BDS capture)");
            flags.add(new Flag(firstCoverage ? Kind.FIRST_COVERAGE : Kind.SOURCE_ONLY, version.version().name(), key, id, flagged, deciding));
        } else if (thirdParty > 0 && !firstCoverage) {
            text.append("; SOURCE ONLY for ").append(thirdParty).append(" of ").append(known).append(" values");
            flags.add(new Flag(Kind.PARTLY_SOURCE_ONLY, version.version().name(), key, id, flagged + " (" + thirdParty + " of " + known
                + " values)", deciding));
        }
        if (flip != null) {
            text.append("; ").append(flip);
            flags.add(new Flag(Kind.SUSPECTED_ARTEFACT, version.version().name(), key, id, flagged + " (" + flip + ")", deciding));
        }
        return text.toString();
    }

    /**
     * @return what a block's change looks like over the neighbouring versions: undone in the next one, or undoing the one
     * before, each time by the same third-party source (a suspected artefact of its dump); null if neither
     */
    @Nullable String flip(BehaviourModel.Version version, BehaviourModel.Block was, BehaviourModel.Block now, int k) {
        int at = model.versions.indexOf(version);
        List<Decided> changed = changed(was, now, k);
        if (changed.isEmpty()) {
            return null;
        }
        BehaviourModel.Block next = at + 1 < model.versions.size() ? same(model.versions.get(at + 1), now) : null;
        if (next != null && revert(changed, now, next, was, k)) {
            return "undone in " + model.versions.get(at + 1).version().name() + " by the same source: suspected dump artefact";
        }
        BehaviourModel.Block before = at >= 2 ? same(model.versions.get(at - 2), now) : null;
        if (before != null && revert(changed(before, was, k), was, now, before, k)) {
            return "undoes the change of " + model.versions.get(at - 1).version().name() + " by the same source: suspected dump artefact";
        }
        return null;
    }

    private static BehaviourModel.@Nullable Block same(BehaviourModel.Version version, BehaviourModel.Block block) {
        BehaviourModel.Block found = version.blocks().get(block.id());
        return found != null && found.schema().equals(block.schema()) ? found : null;
    }

    /** @return true if every state changed from {@code origin} to {@code middle} is back in {@code last}, by one third-party source */
    private boolean revert(List<Decided> changed, BehaviourModel.Block middle, BehaviourModel.Block last, BehaviourModel.Block origin, int k) {
        if (changed.isEmpty()) {
            return false;
        }
        for (int s = 0; s < middle.hashes().length; s++) {
            if (Objects.equals(origin.values()[k][s], middle.values()[k][s])) {
                continue;
            }
            if (!Objects.equals(origin.values()[k][s], last.values()[k][s]) || middle.captures()[k][s] < 0 || last.captures()[k][s] < 0) {
                return false;
            }
            BehaviourStore.Capture a = model.store.captures().get(middle.captures()[k][s]);
            BehaviourStore.Capture b = model.store.captures().get(last.captures()[k][s]);
            if (a.source() != b.source() || !a.source().thirdParty()) {
                return false;
            }
        }
        return true;
    }

    private String format(@Nullable Object value, int k, boolean item, boolean report) {
        if (value == null) {
            return "unknown";
        }
        return (report ? reportFormat : format).apply(!item && BehaviourModel.BLOCK_KEYS.get(k).name().equals("map_color")
            ? new BehaviourLiterals.Hex((Integer) value) : value);
    }
}
