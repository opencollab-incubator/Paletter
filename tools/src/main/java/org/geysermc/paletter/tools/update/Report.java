package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.data.DataDiff;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The markdown report of an {@code update} run. Checks have a status; {@link Status#FAIL} (an assertion or a step
 * failed) and {@link Status#DIFF} (something differs from the committed data) decide the exit code.
 */
public final class Report {
    enum Status { PASS, DIFF, FAIL, SKIP, INFO }

    private record Check(Status status, String name, String detail) {
    }

    private static final int SECTION_LIMIT = 200;

    final Map<String, String> header = new LinkedHashMap<>();
    private final List<Check> checks = new ArrayList<>();
    private final Map<String, List<String>> sections = new LinkedHashMap<>();
    final List<String> wireChanges = new ArrayList<>();
    final List<String> todos = new ArrayList<>();
    final List<String> repoChanges = new ArrayList<>();
    private final String title;

    Report(String title) {
        this.title = title;
    }

    void check(Status status, String name, String detail) {
        checks.add(new Check(status, name, detail));
        System.out.println("[" + status + "] " + name + (detail.isEmpty() ? "" : ": " + detail));
    }

    void fail(String name, Throwable error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        check(Status.FAIL, name, error.toString());
        section("Failure: " + name, List.of("```", trace.toString().strip(), "```"));
    }

    void section(String title, Collection<String> lines) {
        sections.computeIfAbsent(title, t -> new ArrayList<>()).addAll(lines);
    }

    /** One subsection per non empty part of a diff. */
    void diff(String title, DataDiff diff) {
        List<String> lines = new ArrayList<>();
        diff.sections.forEach((name, entries) -> {
            if (entries.isEmpty()) {
                return;
            }
            lines.add("**" + name + "** (" + entries.size() + ")");
            lines.add("");
            entries.stream().limit(SECTION_LIMIT).forEach(entry -> lines.add("- `" + entry.replace("`", "'") + "`"));
            if (entries.size() > SECTION_LIMIT) {
                lines.add("- ... " + (entries.size() - SECTION_LIMIT) + " more");
            }
            lines.add("");
        });
        if (!lines.isEmpty()) {
            section(title, lines);
        }
    }

    boolean failed() {
        return checks.stream().anyMatch(check -> check.status == Status.FAIL);
    }

    boolean differs() {
        return checks.stream().anyMatch(check -> check.status == Status.DIFF) || !wireChanges.isEmpty() || !repoChanges.isEmpty();
    }

    String render() {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(title).append("\n\n");
        header.forEach((key, value) -> out.append("- **").append(key).append("**: ").append(value).append('\n'));
        out.append("- **Result**: ").append(failed() ? "FAILED" : differs() ? "DIFFERENCES" : "IDENTICAL").append("\n\n");

        if (!wireChanges.isEmpty()) {
            out.append("## WIRE FORMAT CHANGES\n\n");
            out.append("> **Component NBT that BDS sends changed shape.** Generated component types encode the old shape; ")
                .append("review the component model (data/model) and the generated types before shipping this version.\n\n");
            wireChanges.forEach(change -> out.append("- **").append(change.replace("*", "\\*")).append("**\n"));
            out.append('\n');
        }

        out.append("## Checks\n\n| Status | Check | Detail |\n|---|---|---|\n");
        for (Check check : checks) {
            out.append("| ").append(check.status).append(" | ").append(check.name).append(" | ")
                .append(check.detail.replace("|", "\\|").replace("\n", " ")).append(" |\n");
        }
        out.append('\n');

        if (!todos.isEmpty()) {
            out.append("## TODO (needs a decision)\n\n");
            todos.forEach(todo -> out.append("- [ ] ").append(todo).append('\n'));
            out.append('\n');
        }
        if (!repoChanges.isEmpty()) {
            out.append("## Repository files\n\n");
            repoChanges.forEach(change -> out.append("- ").append(change).append('\n'));
            out.append('\n');
        }
        sections.forEach((name, lines) -> {
            out.append("## ").append(name).append("\n\n");
            lines.forEach(line -> out.append(line).append('\n'));
            out.append('\n');
        });
        return out.toString().replaceAll("\n{3,}", "\n\n");
    }

    void write(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, render(), StandardCharsets.UTF_8);
    }
}
