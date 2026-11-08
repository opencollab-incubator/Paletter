package org.geysermc.paletter.tools.model.docs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Just enough Markdown for the creator docs: front matter, headings (as a flat list of sections), pipe tables, notes
 * ({@code >} blocks), bullets and paragraphs. The reference pages are generated from Mojang's schemas, so their
 * structure is regular: a property table per type, {@code [Title](#anchor)} links to the section of a nested type.
 */
public final class Markdown {
    public final String file;
    public final List<Section> sections = new ArrayList<>();

    /** A heading and the lines up to the next heading of any level. */
    public static final class Section {
        public final int level;
        public final String title;
        public final String slug;
        public final List<String> lines = new ArrayList<>();
        public final int index;

        Section(int level, String title, int index) {
            this.level = level;
            this.title = title;
            this.slug = slug(title);
            this.index = index;
        }

        /** Tables of this section only (not of subsections), each as a list of rows keyed by lower case header. */
        public List<List<Map<String, String>>> tables() {
            List<List<Map<String, String>>> tables = new ArrayList<>();
            List<String> header = null;
            List<Map<String, String>> rows = null;
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("|")) {
                    header = null;
                    continue;
                }
                List<String> cells = cells(trimmed);
                if (header == null) {
                    header = cells.stream().map(c -> c.toLowerCase(Locale.ROOT)).toList();
                    rows = new ArrayList<>();
                    tables.add(rows);
                    continue;
                }
                if (cells.stream().allMatch(c -> c.matches(":?-*:?"))) {
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    row.put(header.get(i), i < cells.size() ? cells.get(i) : "");
                }
                rows.add(row);
            }
            return tables;
        }

        /** Text of the notes ({@code > [!Note]} blocks), one string per note. */
        public List<String> notes() {
            List<String> notes = new ArrayList<>();
            StringBuilder current = null;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith(">")) {
                    String text = trimmed.substring(1).trim();
                    if (text.matches("(?i)\\[!(note|important|tip|warning|caution)]")) {
                        if (current != null && !current.isEmpty()) {
                            notes.add(current.toString().trim());
                        }
                        current = new StringBuilder();
                        continue;
                    }
                    if (current == null) {
                        current = new StringBuilder();
                    }
                    current.append(text).append(' ');
                } else if (current != null) {
                    if (!current.isEmpty()) {
                        notes.add(current.toString().trim());
                    }
                    current = null;
                }
            }
            if (current != null && !current.isEmpty()) {
                notes.add(current.toString().trim());
            }
            return notes;
        }

        /** Paragraph text (no tables, notes, code blocks or bullets), joined with spaces. */
        public String text() {
            StringBuilder text = new StringBuilder();
            boolean code = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("```")) {
                    code = !code;
                    continue;
                }
                if (code || trimmed.startsWith("|") || trimmed.startsWith(">") || trimmed.startsWith("- ")
                    || trimmed.startsWith("* ") || trimmed.isEmpty() || trimmed.startsWith("**JSON path:**")) {
                    continue;
                }
                text.append(trimmed).append(' ');
            }
            return text.toString().trim();
        }

        /** Bullet lines ({@code - x}), with their indentation depth (0 = top level). */
        public List<Bullet> bullets() {
            List<Bullet> bullets = new ArrayList<>();
            boolean code = false;
            for (String line : lines) {
                if (line.trim().startsWith("```")) {
                    code = !code;
                    continue;
                }
                if (code) {
                    continue;
                }
                String stripped = line.stripLeading();
                if (stripped.startsWith("- ") || stripped.startsWith("* ")) {
                    bullets.add(new Bullet((line.length() - stripped.length()) / 2, stripped.substring(2).trim()));
                }
            }
            return bullets;
        }

        /** The {@code **JSON path:** `a > b`} line of a property section, or null. */
        public String jsonPath() {
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("**JSON path:**")) {
                    return trimmed.substring("**JSON path:**".length()).replace("`", "").trim();
                }
            }
            return null;
        }
    }

    public record Bullet(int depth, String text) {
    }

    public Markdown(String file, String content) {
        this.file = file;
        List<String> lines = content.replace("\r\n", "\n").lines().toList();
        int start = 0;
        if (!lines.isEmpty() && lines.get(0).trim().equals("---")) {
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).trim().equals("---")) {
                    start = i + 1;
                    break;
                }
            }
        }
        Section current = new Section(0, "", 0);
        sections.add(current);
        boolean code = false;
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().startsWith("```")) {
                code = !code;
            }
            if (!code && line.startsWith("#")) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') {
                    level++;
                }
                if (level <= 6 && level < line.length() && line.charAt(level) == ' ') {
                    current = new Section(level, line.substring(level).trim(), sections.size());
                    sections.add(current);
                    continue;
                }
            }
            current.lines.add(line);
        }
    }

    /** The section with this anchor, or null. */
    public Section anchor(String anchor) {
        String slug = anchor.startsWith("#") ? anchor.substring(1) : anchor;
        for (Section section : sections) {
            if (section.slug.equals(slug)) {
                return section;
            }
        }
        return null;
    }

    /** The section and its subsections (until the next heading of the same or a higher level). */
    public List<Section> subtree(Section section) {
        List<Section> subtree = new ArrayList<>();
        subtree.add(section);
        for (int i = section.index + 1; i < sections.size(); i++) {
            Section next = sections.get(i);
            if (next.level <= section.level) {
                break;
            }
            subtree.add(next);
        }
        return subtree;
    }

    /** The first heading of a level, or null. */
    public Section first(int level) {
        return sections.stream().filter(s -> s.level == level).findFirst().orElse(null);
    }

    /** Anchors as the docs site generates them: lower case, punctuation dropped, spaces to dashes. */
    public static String slug(String title) {
        String lower = title.toLowerCase(Locale.ROOT).replace("\\", "").trim();
        StringBuilder slug = new StringBuilder();
        for (char c : lower.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                slug.append(c);
            } else if (c == ' ') {
                slug.append('-');
            }
        }
        return slug.toString();
    }

    static List<String> cells(String row) {
        String body = row.trim();
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean code = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length() && body.charAt(i + 1) == '|') {
                cell.append('|');
                i++;
            } else if (c == '`') {
                code = !code;
                cell.append(c);
            } else if (c == '|' && !code) {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString().trim());
        return cells;
    }
}
