package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.data.Diff;
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
 * The markdown report of an {@code update} run, also the body of the CI issue and pull request. Checks have a status;
 * {@link Status#FAIL} (an assertion or a step failed) and {@link Status#DIFF} (something differs from the committed data)
 * decide the exit code.
 * <p>
 * Only what failed, differs or needs a decision is shown ("Needs attention"), then what the build changed; everything
 * else, run metadata included, is collapsed, so the visible part (and the CI report hash) does not change from run to run.
 */
public final class Report {
    enum Status {
        PASS("Pass"), DIFF("Changed"), FAIL("Failed"), SKIP("Skipped"), INFO("Info");

        /** How the report shows it. */
        final String label;

        Status(String label) {
            this.label = label;
        }
    }

    private record Check(Status status, String name, String detail, List<Evidence> evidence) {
    }

    /** Collapsed lines that back a check or a decision. */
    private record Evidence(String summary, List<String> lines) {
    }

    /** A decision; {@code owner} is the check it acts on, shown on the same line, or null. */
    private record Todo(String text, List<Evidence> evidence, Check owner) {
    }

    private static final int SECTION_LIMIT = 200;
    /** Longer decisions show their start and collapse the full text. */
    private static final int TODO_LIMIT = 200;

    final Map<String, String> header = new LinkedHashMap<>();
    private final List<Check> checks = new ArrayList<>();
    private final Map<String, List<String>> sections = new LinkedHashMap<>();
    private final Map<String, Map<String, List<String>>> changes = new LinkedHashMap<>();
    final List<String> wireChanges = new ArrayList<>();
    private final List<Todo> todos = new ArrayList<>();
    final List<String> repoChanges = new ArrayList<>();
    /** A history check ({@code update --against}): the build compared with, and what differs from it. */
    String against;
    final List<String> againstChanges = new ArrayList<>();
    private final String title;
    /** The command that wrote the report: its generated marker. */
    String generatedBy;

    Report(String title) {
        this.title = title;
    }

    void check(Status status, String name, String detail) {
        checks.add(new Check(status, name, detail, new ArrayList<>()));
        System.out.println("[" + status + "] " + name + (detail.isEmpty() ? "" : ": " + detail));
    }

    /** Collapsed evidence of the last check: under it when it needs attention, else with the other details. */
    void evidence(String summary, Collection<String> lines) {
        if (checks.isEmpty()) {
            section(summary, lines);
            return;
        }
        checks.getLast().evidence.add(new Evidence(summary, List.copyOf(lines)));
    }

    void fail(String name, Throwable error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        check(Status.FAIL, name, error.toString());
        evidence("Stack trace", List.of("```", trace.toString().strip(), "```"));
    }

    /** A decision for a human. */
    void todo(String text) {
        todo(text, null, List.of());
    }

    /** A decision for a human, with a collapsed list that backs it. */
    void todo(String text, String summary, Collection<String> lines) {
        todos.add(new Todo(text, backing(summary, lines), null));
    }

    /** What to do about the last check: on its line when it needs attention, so the two are one entry. */
    void action(String text) {
        action(text, null, List.of());
    }

    /** What to do about the last check, with a collapsed list that backs it. */
    void action(String text, String summary, Collection<String> lines) {
        todos.add(new Todo(text, backing(summary, lines), checks.isEmpty() ? null : checks.getLast()));
    }

    private static List<Evidence> backing(String summary, Collection<String> lines) {
        return lines.isEmpty() ? List.of() : List.of(new Evidence(summary, List.copyOf(lines)));
    }

    boolean hasTodos() {
        return !todos.isEmpty();
    }

    /** A collapsed section with the details. */
    void section(String title, Collection<String> lines) {
        sections.computeIfAbsent(title, t -> new ArrayList<>()).addAll(lines);
    }

    /** What changed: counts per part of the diff, each part collapsed. Identical diffs are left out. */
    void diff(String title, Diff diff) {
        diff(title, diff.lines());
    }

    /** What changed, as lists by part (e.g. {@code new blocks}). */
    void diff(String title, Map<String, List<String>> parts) {
        if (parts.values().stream().anyMatch(entries -> !entries.isEmpty())) {
            changes.put(title, parts);
        }
    }

    boolean failed() {
        return checks.stream().anyMatch(check -> check.status == Status.FAIL);
    }

    boolean differs() {
        return checks.stream().anyMatch(check -> check.status == Status.DIFF) || !wireChanges.isEmpty() || !repoChanges.isEmpty();
    }

    /** The one line under the title, e.g. {@code Failed: 2 failures, 3 changes, 1 decision needed} or {@code OK: no changes}. */
    String verdict() {
        long failures = checks.stream().filter(check -> check.status == Status.FAIL).count();
        long diffs = checks.stream().filter(check -> check.status == Status.DIFF).count();
        List<String> parts = new ArrayList<>();
        if (failures > 0) {
            parts.add(count(failures, "failure", "failures"));
        }
        if (diffs > 0) {
            parts.add(count(diffs, "change", "changes"));
        }
        if (!todos.isEmpty()) {
            parts.add(count(todos.size(), "decision", "decisions") + " needed");
        }
        if (parts.isEmpty() && !repoChanges.isEmpty()) {
            parts.add(count(repoChanges.size(), "repository file", "repository files") + " changed");
        }
        if (parts.isEmpty()) {
            return "OK: no changes";
        }
        return (failures > 0 ? "Failed: " : "Changed: ") + String.join(", ", parts);
    }

    String render() {
        StringBuilder out = new StringBuilder();
        if (generatedBy != null) {
            out.append("<!-- Generated by `").append(generatedBy).append("` (tools Update). Do not edit by hand. -->\n\n");
        }
        out.append("# ").append(title).append("\n\n");
        out.append(verdict()).append("\n\n");
        if (against != null) {
            // A history check: whether this build's captures are the same as the earlier build's, and what differs
            out.append("## Changes against ").append(against).append("\n\n");
            if (againstChanges.isEmpty()) {
                out.append(failed() ? "Not decided: the run failed.\n\n" : "None: every compared capture is the same, version stamps aside.\n\n");
            }
            againstChanges.forEach(change -> out.append("- ").append(oneLine(change)).append('\n'));
            out.append('\n');
        }

        List<Check> attention = checks.stream().filter(check -> check.status == Status.FAIL || check.status == Status.DIFF).toList();
        if (!attention.isEmpty() || !todos.isEmpty()) {
            out.append("## Needs attention\n\n");
            for (Check check : attention) {
                List<Todo> actions = todos.stream().filter(todo -> todo.owner == check).toList();
                out.append("- ").append(check.status.label).append(": **").append(check.name).append("**")
                    .append(check.detail.isEmpty() ? "" : ": " + oneLine(check.detail));
                if (!actions.isEmpty()) {
                    out.append("; to do: ").append(String.join("; ", actions.stream().map(todo -> oneLine(todo.text)).toList()));
                }
                out.append('\n');
                check.evidence.forEach(evidence -> details(out, evidence.summary, evidence.lines, "  "));
                actions.forEach(todo -> todo.evidence.forEach(evidence -> details(out, evidence.summary, evidence.lines, "  ")));
            }
            for (Todo todo : todos) {
                if (attention.contains(todo.owner)) {
                    continue;
                }
                String text = oneLine(todo.text);
                List<Evidence> evidence = new ArrayList<>(todo.evidence);
                if (text.length() > TODO_LIMIT) {
                    int cut = text.lastIndexOf(' ', TODO_LIMIT);
                    evidence.addFirst(new Evidence("Full text", List.of(text)));
                    text = text.substring(0, cut > 0 ? cut : TODO_LIMIT) + " ...";
                }
                out.append("- To do: ").append(text).append('\n');
                evidence.forEach(e -> details(out, e.summary, e.lines, "  "));
            }
            out.append('\n');
        }

        if (!changes.isEmpty()) {
            out.append("## What changed\n\n");
            changes.forEach((name, parts) -> {
                List<String> counts = new ArrayList<>();
                parts.forEach((part, entries) -> {
                    if (!entries.isEmpty()) {
                        counts.add(entries.size() + " " + part);
                    }
                });
                out.append("- **").append(name).append("**: ").append(String.join(", ", counts)).append('\n');
                parts.forEach((part, entries) -> {
                    if (!entries.isEmpty()) {
                        List<String> lines = new ArrayList<>();
                        entries.stream().limit(SECTION_LIMIT).forEach(entry -> lines.add("- `" + entry.replace("`", "'") + "`"));
                        if (entries.size() > SECTION_LIMIT) {
                            lines.add("- ... " + (entries.size() - SECTION_LIMIT) + " more");
                        }
                        details(out, part + " (" + entries.size() + ")", lines, "  ");
                    }
                });
            });
            out.append('\n');
        }

        out.append("## Details\n\n");
        long passed = checks.stream().filter(check -> check.status == Status.PASS).count();
        List<String> table = new ArrayList<>(List.of("| Status | Check | Detail |", "|---|---|---|"));
        for (Check check : checks) {
            table.add("| " + check.status.label + " | " + check.name + " | " + oneLine(check.detail).replace("|", "\\|") + " |");
        }
        details(out, "All checks (" + passed + " passed)", table, "");
        List<String> run = new ArrayList<>();
        header.forEach((key, value) -> run.add("- **" + key + "**: " + value));
        details(out, "Run", run, "");
        if (!repoChanges.isEmpty()) {
            details(out, "Repository files (" + repoChanges.size() + ")", repoChanges.stream().map(change -> "- " + change).toList(), "");
        }
        for (Check check : checks) {
            if (check.status != Status.FAIL && check.status != Status.DIFF) {
                check.evidence.forEach(evidence -> details(out, check.name + ": " + evidence.summary, evidence.lines, ""));
            }
        }
        sections.forEach((name, lines) -> details(out, name, lines, ""));
        return out.toString().replaceAll("\n{3,}", "\n\n");
    }

    /** A collapsed block; {@code indent} keeps it inside a list item. */
    private static void details(StringBuilder out, String summary, List<String> lines, String indent) {
        out.append(indent).append("<details><summary>").append(summary.replace("<", "&lt;")).append("</summary>\n\n");
        lines.forEach(line -> out.append(line.isEmpty() ? "" : indent + line).append('\n'));
        out.append('\n').append(indent).append("</details>\n\n");
    }

    private static String oneLine(String text) {
        return text.replace("\r", "").replace("\n", " ");
    }

    private static String count(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    void write(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, render(), StandardCharsets.UTF_8);
    }
}
