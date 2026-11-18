package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The community docs of wiki.bedrock.dev (markdown source in the bedrock-wiki GitHub repository) as a hint for the key
 * probe and the format sweep. The text is untrusted data: nothing of it is executed, only identifier-like tokens and
 * version numbers are read from it, and only to choose what BDS is asked about. A value on the wiki is never used; BDS
 * answers.
 *
 * <ul>
 *     <li>The component pages ({@code block-components}, {@code item-components}) list the keys of each component as
 *     {@code - `key` — Type} bullets, nested for objects.</li>
 *     <li>The format history pages list per version what was added or updated, and mark the changes that need a newer
 *     {@code format_version} with a "Versioned" label.</li>
 * </ul>
 */
final class WikiSource {
    private static final String BASE = "https://raw.githubusercontent.com/Bedrock-OSS/bedrock-wiki/wiki/docs/";
    static final List<String> COMPONENT_PAGES = List.of("blocks/block-components.md", "items/item-components.md");
    static final List<String> HISTORY_PAGES = List.of("blocks/block-format-history.md", "items/item-format-history.md");

    private static final Pattern COMPONENT = Pattern.compile("^\"(minecraft:[a-z0-9_]+)\"");
    private static final Pattern KEY_BULLET = Pattern.compile("^(\\s*)-\\s+`([A-Za-z0-9_*]+)`\\s+—");
    private static final Pattern TOKEN = Pattern.compile("`([a-z][a-z0-9_]*)`");
    private static final Pattern CHANGE = Pattern.compile("^(\\s*)-\\s+(Added|Updated|Released|Removed|Renamed)\\b(.*)$");
    private static final Pattern VERSION_HEADING = Pattern.compile("^##\\s+(\\d+\\.\\d+\\.\\d+)\\s*$");

    private WikiSource() {
    }

    /** A key a component page lists: {@code path} is the path of the key below the component. */
    record Key(String component, String path) {
    }

    /**
     * A line of a format history: {@code component} was added or updated in {@code version}; {@code key} is the member
     * of it that was added (null if the line is about the component itself) and {@code parent} the member it sits in.
     */
    record Change(String version, boolean versioned, String component, String parent, String key) {
    }

    /** The markdown of a page, from the cache or downloaded into it; null if neither works (a warning is printed). */
    static String page(Path cache, String page) {
        Path file = cache.resolve("wiki").resolve(page.replace('/', '_'));
        try {
            if (!Files.exists(file)) {
                String markdown = Http.DEFAULT.get(BASE + page);
                Files.createDirectories(file.getParent());
                Files.writeString(file, markdown, StandardCharsets.UTF_8);
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException e) {
            System.out.println("wiki.bedrock.dev " + page + " is not available (" + e + "), the keys it lists are not probed");
            return null;
        }
    }

    /**
     * The keys of the component pages: per {@code ###} section the component its first json example names, and the key
     * bullets of the section. {@code Keys are axes (`x`, `y` or `z`)} lines list the keys of the member they sit in.
     *
     * @param kind {@code block} or {@code item}: the prefix of the component ids
     */
    static List<Key> keys(String kind, String markdown) {
        List<Key> keys = new ArrayList<>();
        String component = null;
        List<String[]> bullets = new ArrayList<>(); // indent, key or null, text
        for (String line : (markdown + "\n### end").split("\n")) {
            if (line.startsWith("### ")) {
                flush(kind, component, bullets, keys);
                component = null;
                bullets.clear();
            }
            Matcher m = COMPONENT.matcher(line);
            if (component == null && m.find()) {
                component = m.group(1);
            }
            Matcher bullet = KEY_BULLET.matcher(line);
            if (bullet.find()) {
                bullets.add(new String[]{String.valueOf(bullet.group(1).length()), bullet.group(2), line});
            } else if (line.matches("^\\s+-\\s+Keys?\\b.*")) {
                bullets.add(new String[]{String.valueOf(line.indexOf('-')), null, line});
            }
        }
        return keys;
    }

    private static void flush(String kind, String component, List<String[]> bullets, List<Key> into) {
        if (component == null) {
            return;
        }
        List<String[]> stack = new ArrayList<>(); // enclosing key bullets: indent, key
        for (String[] bullet : bullets) {
            int indent = Integer.parseInt(bullet[0]);
            while (!stack.isEmpty() && Integer.parseInt(stack.getLast()[0]) >= indent) {
                stack.removeLast();
            }
            String parent = String.join(".", stack.stream().map(s -> s[1]).toList());
            if (bullet[1] != null) {
                into.add(new Key(kind + ":" + component, parent.isEmpty() ? bullet[1] : parent + "." + bullet[1]));
                stack.add(new String[]{bullet[0], bullet[1]});
            } else if (!parent.isEmpty()) {
                Matcher token = TOKEN.matcher(bullet[2]);
                while (token.find()) {
                    into.add(new Key(kind + ":" + component, parent + "." + token.group(1)));
                }
            }
        }
    }

    /** The changes of a format history page, in page order. */
    static List<Change> history(String kind, String markdown) {
        List<Change> changes = new ArrayList<>();
        String version = null;
        boolean versioned = false;
        boolean afterLabel = false;
        String component = null;
        Map<Integer, String> parents = new LinkedHashMap<>();
        for (String line : markdown.split("\n")) {
            Matcher heading = VERSION_HEADING.matcher(line);
            if (heading.find()) {
                version = heading.group(1);
                versioned = false;
                component = null;
                continue;
            }
            if (line.startsWith("#")) {
                versioned = false;
                component = null;
                continue;
            }
            if (line.startsWith("<Label") || line.startsWith("<Tag")) {
                if (!afterLabel) {
                    versioned = false;
                }
                afterLabel = true;
                versioned |= line.contains("Versioned");
                continue;
            }
            Matcher change = CHANGE.matcher(line);
            if (version == null || !change.find()) {
                if (!line.isBlank() && !line.matches("^\\s*-.*") && !line.startsWith(" ")) {
                    afterLabel = false;
                }
                continue;
            }
            afterLabel = false;
            int depth = change.group(1).length() / 4;
            Matcher token = TOKEN.matcher(change.group(3));
            Matcher named = Pattern.compile("`(minecraft:[a-z0-9_]+)`").matcher(change.group(3));
            if (depth == 0) {
                parents.clear();
                component = named.find() ? named.group(1) : null;
                if (component != null) {
                    changes.add(new Change(version, versioned, kind + ":" + component, null, null));
                }
            } else if (component != null && token.find()) {
                String parent = null;
                for (int d = depth - 1; d >= 1; d--) {
                    if (parents.containsKey(d)) {
                        parent = parents.get(d);
                        break;
                    }
                }
                changes.add(new Change(version, versioned, kind + ":" + component, parent, token.group(1)));
                parents.put(depth, token.group(1));
            }
        }
        return changes;
    }
}
