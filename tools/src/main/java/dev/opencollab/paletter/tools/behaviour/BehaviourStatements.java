package dev.opencollab.paletter.tools.behaviour;

import com.palantir.javapoet.CodeBlock;
import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.tools.behaviour.BehaviourNames.TableKind;
import dev.opencollab.paletter.tools.behaviour.BehaviourOps.Op;
import dev.opencollab.paletter.tools.behaviour.CellFit.Constant;
import dev.opencollab.paletter.tools.behaviour.CellFit.Fit;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The statements of a version: one per call of {@link BehaviourOps}, the values in the version's class (with a comment per
 * change, except in the base) and the captures in its sources class.
 */
final class BehaviourStatements {
    private final BehaviourLiterals literals;
    private final BehaviourComments comments;
    private final BehaviourModel model;

    BehaviourStatements(BehaviourModel model, BehaviourLiterals literals, BehaviourComments comments) {
        this.model = model;
        this.literals = literals;
        this.comments = comments;
    }

    /** @param before the version before, or null for the base (no comments) */
    List<CodeBlock> values(List<Op> ops, BehaviourModel.@Nullable Version before, BehaviourModel.Version version) {
        List<CodeBlock> statements = new ArrayList<>();
        for (Op op : ops) {
            List<String> notes = new ArrayList<>();
            CodeBlock call = switch (op) {
                case BehaviourOps.EveryBlock every -> {
                    if (before != null) {
                        every.values().forEach((k, value) -> notes.add(everyBlock(before, version, k, value)));
                    }
                    yield chain("v.everyBlock()", every.values().entrySet().stream().map(e -> literals.setter(BehaviourModel.BLOCK_KEYS.get(e.getKey()),
                        new Constant(e.getValue()), TableKind.VALUES)).toList());
                }
                case BehaviourOps.DeclareBlock declare -> {
                    BehaviourModel.Block block = version.blocks().get(declare.id());
                    if (before != null) {
                        String schema = before.schemas().get(declare.id());
                        if (schema != null && schema.equals(declare.schema())) {
                            declare.values().keySet().forEach(k -> notes.add(blockChange(version, null, block, k)));
                        } else {
                            notes.add((schema == null ? "new in " + version.version().name() : "other states than in " + before.version().name()
                                + " (" + (schema.isEmpty() ? "none" : schema) + ")") + ": " + deciders(block));
                        }
                    }
                    yield chain(CodeBlock.of("v.block($S, $S)", declare.id(), declare.schema()), blockSetters(declare.values()));
                }
                case BehaviourOps.ChangeBlock change -> {
                    BehaviourModel.Block was = before.blocks().get(change.id());
                    change.values().keySet().forEach(k -> notes.add(blockChange(version, was, version.blocks().get(change.id()), k)));
                    yield chain(CodeBlock.of("v.change($S)", change.id()), blockSetters(change.values()));
                }
                case BehaviourOps.RemoveBlock remove -> {
                    notes.add(version.schemas().containsKey(remove.id()) ? "no capture of " + version.version().name() + " or before has it"
                        : "not in the palette of " + version.version().name());
                    yield CodeBlock.of("v.remove($S)", remove.id());
                }
                case BehaviourOps.EveryItem every -> {
                    if (before != null) {
                        every.values().forEach((k, value) -> notes.add(everyItem(before, version, k, value)));
                    }
                    yield chain("v.everyItem()", every.values().entrySet().stream().map(e -> literals.setter(BehaviourModel.ITEM_KEYS.get(e.getKey()),
                        new Constant(e.getValue()), TableKind.VALUES)).toList());
                }
                case BehaviourOps.DeclareItem declare -> {
                    BehaviourModel.Item item = version.items().get(declare.id());
                    if (before != null) {
                        if (before.paletteItems().contains(declare.id())) {
                            declare.values().keySet().forEach(k -> notes.add(itemChange(version, null, item, k)));
                        } else {
                            notes.add("new in " + version.version().name());
                        }
                    }
                    yield chain(CodeBlock.of("v.item($S)", declare.id()), itemSetters(declare.values()));
                }
                case BehaviourOps.ChangeItem change -> {
                    Object[] was = before.items().get(change.id()).values();
                    change.values().keySet().forEach(k -> notes.add(itemChange(version, was, version.items().get(change.id()), k)));
                    yield chain(CodeBlock.of("v.changeItem($S)", change.id()), itemSetters(change.values()));
                }
                case BehaviourOps.RemoveItem remove -> {
                    notes.add(version.paletteItems().contains(remove.id()) ? "no capture of " + version.version().name() + " or before has it"
                        : "not in the palette of " + version.version().name());
                    yield CodeBlock.of("v.removeItem($S)", remove.id());
                }
                default -> null; // a capture: sources(ops)
            };
            if (call != null) {
                statements.add(statement(notes, call));
            }
        }
        return statements;
    }

    /** @return the statements of the captures */
    List<CodeBlock> sources(List<Op> ops) {
        List<CodeBlock> statements = new ArrayList<>();
        for (Op op : ops) {
            CodeBlock call = switch (op) {
                case BehaviourOps.BlockSourceDefaults defaults -> chain("v.blockSources()", defaults.values().entrySet().stream()
                    .map(e -> literals.setter(BehaviourModel.BLOCK_KEYS.get(e.getKey()), new Constant(e.getValue()), TableKind.SOURCES)).toList());
                case BehaviourOps.BlockSources sources -> chain(CodeBlock.of("v.blockSources($S)", sources.id()), sources.values().entrySet().stream()
                    .map(e -> literals.setter(BehaviourModel.BLOCK_KEYS.get(e.getKey()), e.getValue(), TableKind.SOURCES)).toList());
                case BehaviourOps.ItemSourceDefaults defaults -> chain("v.itemSources()", defaults.values().entrySet().stream()
                    .map(e -> literals.setter(BehaviourModel.ITEM_KEYS.get(e.getKey()), new Constant(e.getValue()), TableKind.SOURCES)).toList());
                case BehaviourOps.ItemSources sources -> chain(CodeBlock.of("v.itemSources($S)", sources.id()), sources.values().entrySet().stream()
                    .map(e -> literals.setter(BehaviourModel.ITEM_KEYS.get(e.getKey()), new Constant(e.getValue()), TableKind.SOURCES)).toList());
                default -> null;
            };
            if (call != null) {
                statements.add(statement(List.of(), call));
            }
        }
        return statements;
    }

    private String everyBlock(BehaviourModel.Version before, BehaviourModel.Version version, int k, Object value) {
        List<BehaviourComments.Decided> decided = new ArrayList<>();
        int blocks = 0;
        for (BehaviourModel.Block block : version.blocks().values()) {
            BehaviourModel.Block was = before.blocks().get(block.id());
            if (was != null && was.schema().equals(block.schema()) && block.fits()[k].equals(new Constant(value)) && !was.fits()[k].equals(block.fits()[k])) {
                decided.addAll(BehaviourComments.changed(was, block, k));
                blocks++;
            }
        }
        BehaviourKey<?> key = BehaviourModel.BLOCK_KEYS.get(k);
        return comments.comment(version, key, "every block", key.name() + " -> " + (value == null ? "unknown" : literals.comment(value))
            + " for every block (" + blocks + " changed)", decided);
    }

    private String everyItem(BehaviourModel.Version before, BehaviourModel.Version version, int k, Object value) {
        List<BehaviourComments.Decided> decided = new ArrayList<>();
        for (BehaviourModel.Item item : version.items().values()) {
            BehaviourModel.Item was = before.items().get(item.id());
            if (was != null && java.util.Objects.equals(item.values()[k], value) && !java.util.Objects.equals(was.values()[k], value)) {
                decided.add(new BehaviourComments.Decided(item.captures()[k], 0, item.id(), value));
            }
        }
        BehaviourKey<?> key = BehaviourModel.ITEM_KEYS.get(k);
        return comments.comment(version, key, "every item", key.name() + " -> " + (value == null ? "unknown" : literals.comment(value))
            + " for every item (" + decided.size() + " changed)", decided);
    }

    private String blockChange(BehaviourModel.Version version, BehaviourModel.@Nullable Block was, BehaviourModel.Block block, int k) {
        List<BehaviourComments.Decided> changed = BehaviourComments.changed(was, block, k);
        if (changed.isEmpty()) {
            return BehaviourModel.BLOCK_KEYS.get(k).name() + " stays as in the version before (not what every block got)";
        }
        return comments.comment(version, BehaviourModel.BLOCK_KEYS.get(k), block.id(), comments.what(was, block, k, changed.size()), changed);
    }

    private String itemChange(BehaviourModel.Version version, Object @Nullable [] was, BehaviourModel.Item item, int k) {
        if (was != null && java.util.Objects.equals(was[k], item.values()[k])) {
            return BehaviourModel.ITEM_KEYS.get(k).name() + " stays as in the version before (not what every item got)";
        }
        return comments.comment(version, BehaviourModel.ITEM_KEYS.get(k), item.id(), comments.what(was, item, k),
            List.of(new BehaviourComments.Decided(item.captures()[k], 0, item.id(), item.values()[k])));
    }

    /** @return the captures a block's values come from, for the comment of a new block */
    private String deciders(BehaviourModel.Block block) {
        TreeSet<String> labels = new TreeSet<>();
        for (int[] captures : block.captures()) {
            for (int capture : captures) {
                if (capture >= 0) {
                    labels.add(BehaviourComments.label(model.store.captures().get(capture)));
                }
            }
        }
        return "values from " + String.join(", ", labels);
    }

    private List<CodeBlock> blockSetters(Map<Integer, Fit> values) {
        return values.entrySet().stream().map(e -> literals.setter(BehaviourModel.BLOCK_KEYS.get(e.getKey()), e.getValue(), TableKind.VALUES)).toList();
    }

    private List<CodeBlock> itemSetters(Map<Integer, Object> values) {
        return values.entrySet().stream().map(e -> literals.setter(BehaviourModel.ITEM_KEYS.get(e.getKey()), new Constant(e.getValue()),
            TableKind.VALUES)).toList();
    }

    private static CodeBlock chain(String start, List<CodeBlock> setters) {
        return chain(CodeBlock.of("$L", start), setters);
    }

    /** A call and its setters: on one line if they are few and short, else one setter per line. */
    private static CodeBlock chain(CodeBlock start, List<CodeBlock> setters) {
        CodeBlock.Builder code = CodeBlock.builder().add(start);
        boolean lines = setters.size() > 3 || start.toString().length() + setters.stream().mapToInt(s -> s.toString().length()).sum() > 100;
        for (CodeBlock setter : setters) {
            code.add(lines ? "\n$L" : "$L", setter);
        }
        return code.build();
    }

    private static CodeBlock statement(List<String> notes, CodeBlock call) {
        CodeBlock.Builder code = CodeBlock.builder();
        notes.forEach(note -> code.add("// $L\n", note));
        return code.addStatement("$L", call).build();
    }
}
