package dev.opencollab.paletter.tools.codegen.component;

import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Field;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Kind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Javadoc of the generated value classes and their accessors, with the evidence behind it: what the BDS captures
 * show of a component (server only, rejected, gated) and what replaced it.
 */
final class ComponentDocs {
    private ComponentDocs() {
    }

    static String lines(String text) {
        return String.join("\n", Docs.wrap(text)) + "\n";
    }

    /** The Javadoc of a value class, before {@link Docs#bedrock}: the component's text and evidence, or an object's text. */
    static String typeDoc(ApiModel model, Comp c, ObjType o, boolean top, String since) {
        StringBuilder doc = new StringBuilder();
        String text = top ? c.doc : o.doc;
        if (top && c.removed) {
            doc.append(lines("Removed: Bedrock no longer has {@code " + c.name + "}. This stand-in keeps code that uses it compiling until the"
                + " next major release; its fields return their defaults, its builder ignores what it is given, and every palette leaves it"
                + " out with a report warning."));
        } else {
            doc.append(lines(text.isEmpty() ? "The {@code " + o.path + "} object." : !top && o.javadoc ? text : Docs.escape(text)));
        }
        if (!top && o.removed) {
            doc.append(lines("Removed from Bedrock with the field that held it: a stand-in until the next major release."));
        }
        if (top && !c.standalone && !c.removed) {
            doc.append("<p>\nAdd-on JSON: {@code \"").append(c.name).append("\"}")
                .append(c.page.isEmpty() || Docs.learnUrl(c.page) != null ? "" : ", documented in {@code " + c.page + "}").append(".\n");
            if (c.bedrockSince != null) {
                doc.append(String.join("\n", Docs.wrap("Bedrock " + c.bedrockSince + " is the first bundled version with it: the schemas of"
                    + " older captured BDS builds do not have it, so their palettes leave it out and add a report warning."))).append('\n');
            }
            if (c.serverOnly != null) {
                doc.append(serverOnlyDoc(model, c));
            }
            if (c.rejected != null) {
                doc.append(String.join("\n", Docs.wrap("Retired: " + Docs.escape(rejectedText(model, c))
                    + ". Its encoder writes nothing and adds a report warning, as BDS would never send it to a client."))).append('\n');
            }
            if (!c.experiments.isEmpty()) {
                doc.append("The creator docs name the experiment").append(c.experiments.size() > 1 ? "s " : " ")
                    .append(String.join(", ", c.experiments.stream().map(Docs::escape).toList()))
                    .append(" for it (in some format versions);")
                    .append(c.gating.isEmpty() ? " unverified against BDS.\n" : "\n" + gatingDoc(c.gating, c.serverOnly != null));
                if (c.experiment == null && !c.toggles.isEmpty()) {
                    doc.append("Not {@code @BedrockExperiment}: every bundled version takes it without the experiment.\n");
                }
                for (String name : c.unmappedExperiments) {
                    doc.append("Unverified: the creator docs name experiment \"").append(Docs.escape(name)).append("\", which is in no capture.\n");
                }
            }
            if (c.transform != null) {
                doc.append("Compiled by the hand transform {@code Transforms.").append(c.transform).append("}.\n");
            }
        }
        doc.append('\n');
        if (top) {
            String url = c.standalone ? null : Docs.learnUrl(c.page);
            if (url != null) {
                doc.append("@see <a href=\"").append(url).append("\">Microsoft Learn: ").append(Docs.escape(c.name)).append("</a>\n");
            }
            doc.append("@since ").append(since).append('\n');
            if (c.removed) {
                doc.append("@deprecated Bedrock no longer has it; it goes in the next major release\n");
            } else if (c.replacement != null) {
                doc.append("@deprecated ").append(replacementText(model, c.kind, c.replacement)).append('\n');
            } else if (c.rejected != null) {
                doc.append("@deprecated ").append(String.join("\n       ", Docs.wrap(Docs.escape(rejectedText(model, c))))).append('\n');
            } else if (c.deprecated()) {
                doc.append("@deprecated marked deprecated by the Minecraft creator documentation\n");
            }
        }
        return doc.toString();
    }

    /** "replaced by {@link A}, {@code b}: evidence": generated successors of the same kind are linked, others named. */
    static String replacementText(ApiModel model, Kind kind, Replacements.Replacement r) {
        List<String> by = new ArrayList<>();
        for (String id : r.by()) {
            Comp successor = model.component(id);
            by.add(successor != null && successor.kind == kind ? "{@link " + successor.className + "}"
                : "{@code " + id.substring(id.indexOf(':') + 1) + "}");
        }
        return "replaced by " + String.join(", ", by) + (r.hand() ? " (stated in fixtures.json: " : " (") + Docs.escape(r.evidence()) + ")";
    }

    /** The paragraph that says in which protocols BDS accepts the component without sending it. */
    private static String serverOnlyDoc(ApiModel model, Comp c) {
        ApiModel.Window w = c.serverOnly;
        if (!w.bounded()) {
            String versions = Docs.releases(model, List.copyOf(model.captureVersions.values()));
            return String.join("\n", Docs.wrap("Server only: BDS accepts it in packs but sends nothing to clients"
                + (versions.isEmpty() ? "" : " (" + versions + ")") + ", so it has no client effect. Palettes leave it out without a warning.")) + "\n";
        }
        String range = w.since() == Integer.MIN_VALUE ? "below protocol " + w.until()
            : w.until() == Integer.MAX_VALUE ? "from protocol " + w.since() : "from protocol " + w.since() + " below " + w.until();
        List<String> captured = new ArrayList<>();
        model.captureVersions.forEach((protocol, bds) -> {
            if (protocol >= w.since() && protocol < w.until()) {
                captured.add(bds);
            }
        });
        StringBuilder text = new StringBuilder("Server only ").append(range).append(": BDS accepts it in packs but sends nothing to clients there");
        String versions = Docs.releases(model, captured);
        if (!versions.isEmpty()) {
            text.append(" (").append(versions).append(")");
        }
        text.append(", so those palettes leave it out and add a report warning.");
        Map.Entry<Integer, String> sent = w.until() == Integer.MAX_VALUE ? null : model.captureVersions.ceilingEntry(w.until());
        if (sent != null) {
            String version = Docs.releases(model, List.of(sent.getValue()));
            text.append(" Sent from protocol ").append(w.until()).append(version.isEmpty() ? "" : " (" + version + ")").append(".");
        }
        return String.join("\n", Docs.wrap(text.toString())) + "\n";
    }

    /** The sentence that says since when BDS rejects the component (from the captures), with BDS's own message. */
    private static String rejectedText(ApiModel model, Comp c) {
        ApiModel.Window w = c.rejected;
        String since;
        if (model.captureVersions.isEmpty()) {
            since = "rejected by BDS";
        } else if (w.since() == Integer.MIN_VALUE) {
            Map.Entry<Integer, String> oldest = model.captureVersions.firstEntry();
            String version = Docs.releases(model, List.of(oldest.getValue()));
            since = "rejected by every captured BDS (oldest: " + (version.isEmpty() ? "" : version + ", ") + "protocol " + oldest.getKey() + ")";
        } else {
            Map.Entry<Integer, String> first = model.captureVersions.floorEntry(w.since());
            since = "rejected by BDS since " + (first == null ? "protocol " + w.since() : Docs.release(model, first));
        }
        return since + (w.mapping().has("note") ? ": " + w.mapping().get("note").getAsString() : "");
    }

    /** What the gating captures verify of a component: where BDS does not take it without experiments, where it does, and where it never does. */
    static String gatingDoc(List<Gating.Result> results, boolean serverOnly) {
        List<String> gated = results.stream().filter(r -> r.gated(serverOnly)).map(Gating.Result::build).toList();
        List<String> open = results.stream().filter(r -> r.without().equals(Gating.ACCEPTED)).map(Gating.Result::build).toList();
        List<String> never = results.stream().filter(Gating.Result::rejectedAnyway).map(Gating.Result::build).toList();
        boolean ignored = results.stream().anyMatch(r -> r.gated(serverOnly) && r.without().equals(Gating.IGNORED));
        StringBuilder doc = new StringBuilder();
        if (!gated.isEmpty()) {
            doc.append("BDS-verified: in a world without experiments BDS ").append(ignored ? "rejects or ignores" : "rejects")
                .append(" it in ").append(String.join(", ", gated)).append(".\n");
        }
        if (!open.isEmpty()) {
            doc.append("BDS takes it without any experiment in ").append(String.join(", ", open)).append(": the docs' gating does not hold there.\n");
        }
        if (!never.isEmpty()) {
            doc.append("BDS rejects it even with the experiments on in ").append(String.join(", ", never)).append(".\n");
        }
        return doc.toString();
    }

    /** The Javadoc of a field's accessor, before {@link Docs#bedrock}: its text, its default and every limit with its source. */
    static String fieldDoc(Field f) {
        StringBuilder s = new StringBuilder();
        String doc = f.javadoc ? f.doc : Docs.escape(f.doc);
        s.append(doc.isEmpty() ? (f.json == null ? "The value." : "Add-on JSON {@code " + f.json + "}.") : doc);
        if (f.json != null && !doc.isEmpty() && f.keyedExtraOf == null) {
            s.append(" Add-on JSON {@code ").append(f.json).append("}.");
        }
        if (f.required) {
            s.append(" Required.");
        } else if (f.defaultValue != null) {
            s.append(" Default: ").append(Docs.literal(f.defaultValue)).append('.');
        } else if (f.nullAs != null) {
            s.append(" Optional (null: {@code ").append(Docs.escape(f.nullAs)).append("}).");
        } else {
            s.append(" Optional (null: not set).");
        }
        if (!f.reservedKeys.isEmpty()) {
            s.append(" A key that has a field of its own (").append(String.join(", ", f.reservedKeys.stream().map(k -> "{@code " + k + "}").toList()))
                .append(") is refused.");
        }
        if (f.defaultNote != null) {
            s.append(' ').append(f.defaultNote);
        }
        // Each limit with where it comes from; a limit nothing states is not written (BDS may still refuse values there)
        if (f.min != null && f.max != null && f.minSource.equals(f.maxSource)) {
            s.append(" Range [").append(Docs.number(f.min)).append(", ").append(Docs.number(f.max)).append("] (").append(f.minSource).append(").");
        } else {
            if (f.min != null) {
                s.append(" At least ").append(Docs.number(f.min)).append(" (").append(f.minSource).append(").");
            }
            if (f.max != null) {
                s.append(" At most ").append(Docs.number(f.max)).append(" (").append(f.maxSource).append(").");
            }
        }
        if (f.allowed != null) {
            s.append(" One of ").append(f.allowed.stream().map(Docs::number).toList()).append('.');
        }
        if (f.notBelow != null) {
            s.append(" Not below {@code ").append(f.notBelow.json).append("} (").append(f.notBelowSource).append(").");
        }
        if (f.conflictsWith != null) {
            s.append(" Empty while {@code ").append(f.conflictsWith.json).append("} is {@code ").append(f.conflictValue).append("} (")
                .append(f.conflictSource).append(").");
        }
        if (f.multipleOf != null) {
            s.append(" Multiples of ").append(Docs.number(f.multipleOf)).append(" only (BDS refuses the others).");
        }
        if (f.documentedValues != null && f.documentedValues.size() <= 10 && !f.documentedValues.stream().allMatch(f.doc::contains)) {
            s.append(" Documented values: ").append(String.join(", ", f.documentedValues.stream().map(v -> "{@code " + Docs.escape(v) + "}").toList()))
                .append("; BDS takes other strings too.");
        }
        if (f.transform != null) {
            s.append(" Compiled by the hand transform {@code Transforms.").append(f.transform).append("}.");
        }
        return s.toString();
    }
}
