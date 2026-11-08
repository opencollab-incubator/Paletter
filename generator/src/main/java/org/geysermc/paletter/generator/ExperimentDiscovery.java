package org.geysermc.paletter.generator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the experiment toggle ids of a BDS build. Nothing in BDS lists them, so discovery has two halves:
 * <ol>
 *     <li><b>Candidates</b> (this class, no server needed): every identifier-like string of the server binary, plus
 *     the suffixes after {@code _} of each (the Windows linker merges a string into the tail of a longer one, e.g.
 *     {@code y_2026_drop_4} only exists inside {@code behavior_packs/experimental_y_2026_drop_4}), plus the ASCII of
 *     x86-64 {@code mov r64, imm64} instructions and overlaps of neighbouring ones (MSVC builds short strings such as
 *     {@code gametest} from immediates), plus the names of the {@code experimental_*} packs, plus known ids.</li>
 *     <li><b>Oracle</b> ({@link Update}): BDS reads the {@code experiments} compound of {@code level.dat} on start and
 *     writes back only the keys it knows on stop. Writing every candidate in and reading the file back yields exactly
 *     the toggles of the build (current and retired). Start game then reports which of them are current: it only
 *     streams toggles that still do something.</li>
 * </ol>
 * The binary also holds one UI string per current toggle ({@code createWorldScreen.experimental<Name>}); their count
 * cross-checks the oracle, their English names ({@code resource_packs/vanilla/texts/en_US.lang}) map the creator docs'
 * experiment names to ids.
 */
final class ExperimentDiscovery {
    static final String UI_PREFIX = "createWorldScreen.experimental";
    private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,63}");
    private static final Pattern UI_KEY = Pattern.compile(Pattern.quote(UI_PREFIX) + "[A-Z0-9][A-Za-z0-9]*");
    private static final Pattern ACTIVE = Pattern.compile("Experiment\\(s\\) active: (.*)$");
    /** Bytes between two {@code mov r64, imm64} whose immediates may belong to one string. */
    private static final int IMMEDIATE_WINDOW = 48;

    private ExperimentDiscovery() {
    }

    /**
     * @param ids identifier-like strings that can be tried as toggles
     * @param uiKeys {@code createWorldScreen.experimental*} keys of the binary (one per current toggle)
     */
    record Candidates(Set<String> ids, Set<String> uiKeys) {
    }

    static Candidates scan(Path binary) throws IOException {
        try (FileChannel channel = FileChannel.open(binary, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size == 0 || size > Integer.MAX_VALUE) {
                throw new IOException("Cannot scan " + binary + " (" + size + " bytes)");
            }
            MappedByteBuffer map = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            return scan(map);
        }
    }

    static Candidates scan(byte[] bytes) {
        return scan(ByteBuffer.wrap(bytes));
    }

    private static Candidates scan(ByteBuffer data) {
        Set<String> ids = new TreeSet<>();
        Set<String> uiKeys = new TreeSet<>();
        int limit = data.limit();
        // NUL terminated strings: the identifier run in front of every NUL, and its suffixes after '_'
        int runStart = 0;
        int printableStart = 0;
        String previousImmediate = null;
        int previousImmediateAt = Integer.MIN_VALUE;
        for (int i = 0; i < limit; i++) {
            int b = data.get(i) & 0xff;
            if (b == 0) {
                if (i - runStart >= 3) {
                    suffixes(ascii(data, runStart, i), ids);
                }
                if (i - printableStart > UI_PREFIX.length()) {
                    String printable = ascii(data, printableStart, i);
                    Matcher matcher = UI_KEY.matcher(printable);
                    while (matcher.find()) {
                        if (!matcher.group().endsWith("Description")) {
                            uiKeys.add(matcher.group());
                        }
                    }
                }
            }
            if (!identifierChar(b)) {
                runStart = i + 1;
            }
            if (b < 0x20 || b >= 0x7f) {
                printableStart = i + 1;
            }
            // mov r64, imm64: REX.W (0x48) or REX.WB (0x49), opcode 0xb8 + register, 8 bytes immediate
            if ((b == 0x48 || b == 0x49) && i + 10 <= limit) {
                int opcode = data.get(i + 1) & 0xff;
                if (opcode >= 0xb8 && opcode <= 0xbf && identifierBytes(data, i + 2, 8)) {
                    String immediate = ascii(data, i + 2, i + 10);
                    if (ID.matcher(immediate).matches()) {
                        ids.add(immediate);
                    }
                    if (previousImmediate != null && i - previousImmediateAt <= IMMEDIATE_WINDOW) {
                        for (String joined : overlaps(previousImmediate, immediate)) {
                            if (ID.matcher(joined).matches()) {
                                ids.add(joined);
                            }
                        }
                    }
                    previousImmediate = immediate;
                    previousImmediateAt = i;
                }
            }
        }
        ids.remove(org.geysermc.paletter.capture.LevelDat.EVER_USED);
        ids.remove(org.geysermc.paletter.capture.LevelDat.SAVED_WITH_TOGGLED);
        return new Candidates(ids, uiKeys);
    }

    /** {@code run} and every suffix that starts after an underscore, if it looks like an id. */
    static void suffixes(String run, Set<String> into) {
        for (int i = 0; i < run.length(); i++) {
            if (i == 0 || run.charAt(i - 1) == '_') {
                String suffix = run.substring(i);
                if (ID.matcher(suffix).matches()) {
                    into.add(suffix);
                }
            }
        }
    }

    /**
     * Strings two immediates can spell when the second one is stored right after, or overlapping the end of, the
     * first: {@code y_2026_d} + {@code 6_drop_4} gives {@code y_2026_drop_4} (overlap {@code 6_d}).
     */
    static List<String> overlaps(String first, String second) {
        List<String> joined = new ArrayList<>();
        joined.add(first + second);
        for (int k = 1; k < second.length(); k++) {
            if (first.endsWith(second.substring(0, k))) {
                joined.add(first + second.substring(k));
            }
        }
        return joined;
    }

    /** Names of the {@code experimental_*} packs BDS ships, with and without the prefix. */
    static Set<String> packCandidates(Path server) throws IOException {
        Set<String> ids = new TreeSet<>();
        for (String kind : List.of("behavior_packs", "resource_packs")) {
            Path directory = server.resolve(kind);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> packs = Files.list(directory)) {
                packs.map(pack -> pack.getFileName().toString()).filter(name -> name.startsWith("experimental_")).forEach(name -> {
                    ids.add(name);
                    ids.add(name.substring("experimental_".length()));
                });
            }
        }
        return ids;
    }

    /** English names of the UI keys, from the vanilla resource pack's {@code en_US.lang}; empty if it is missing. */
    static Map<String, String> displayNames(Path server, Collection<String> uiKeys) throws IOException {
        Path lang = server.resolve("resource_packs/vanilla/texts/en_US.lang");
        Map<String, String> names = new TreeMap<>();
        if (!Files.exists(lang)) {
            return names;
        }
        for (String line : Files.readAllLines(lang, StandardCharsets.UTF_8)) {
            int equals = line.indexOf('=');
            if (equals > 0 && uiKeys.contains(line.substring(0, equals).strip())) {
                String value = line.substring(equals + 1);
                int comment = value.indexOf('\t');
                names.put(line.substring(0, equals).strip(), (comment >= 0 ? value.substring(0, comment) : value).strip());
            }
        }
        return names;
    }

    /**
     * The four letter codes of {@code Experiment(s) active: ucft, gtst, ...}, the line BDS logs while loading a world;
     * null if no line has it.
     */
    static List<String> activeCodes(List<String> lines) {
        for (String line : lines) {
            Matcher matcher = ACTIVE.matcher(line);
            if (matcher.find()) {
                String list = matcher.group(1).strip();
                return list.isEmpty() ? List.of() : List.of(list.split(",\\s*"));
            }
        }
        return null;
    }

    /**
     * Pairs current toggle ids with UI keys by shared words ({@code experimentalVoxelShapeFeatures} and
     * {@code voxel_shapes} share {@code voxel} and {@code shape}), greedily best score first. Only used to label toggles
     * with their display names; unmatched ids stay without.
     */
    static Map<String, String> matchUiKeys(Collection<String> ids, Collection<String> uiKeys) {
        record Pair(String id, String key, int score) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (String id : ids) {
            Set<String> idWords = words(id);
            for (String key : uiKeys) {
                Set<String> keyWords = words(key.substring(UI_PREFIX.length()));
                int score = 0;
                for (String word : idWords) {
                    if (keyWords.contains(word)) {
                        score += word.length();
                    }
                }
                if (score > 0) {
                    pairs.add(new Pair(id, key, score));
                }
            }
        }
        pairs.sort((a, b) -> b.score != a.score ? Integer.compare(b.score, a.score) : (a.id + a.key).compareTo(b.id + b.key));
        Map<String, String> matched = new LinkedHashMap<>();
        Set<String> usedKeys = new TreeSet<>();
        for (Pair pair : pairs) {
            if (!matched.containsKey(pair.id) && usedKeys.add(pair.key)) {
                matched.put(pair.id, pair.key);
            }
        }
        return new TreeMap<>(matched);
    }

    /** Lower case words of an id or a camel case key, digits split off, a plural {@code s} dropped, and all joined. */
    static Set<String> words(String text) {
        Set<String> words = new TreeSet<>();
        String spaced = text.replaceAll("([a-z])([A-Z0-9])", "$1 $2").replaceAll("([0-9])([A-Za-z])", "$1 $2").replace('_', ' ');
        StringBuilder joined = new StringBuilder();
        for (String word : spaced.toLowerCase().split("\\s+")) {
            if (word.isEmpty() || word.equals("experimental") || word.equals("features")) {
                continue;
            }
            words.add(word.length() > 3 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word);
            joined.append(word);
        }
        // "GameTest" against "gametest"
        if (!joined.isEmpty()) {
            words.add(joined.toString());
        }
        return words;
    }

    /**
     * Normalized form of a docs experiment name ("Upcoming Creator Features") or an id ({@code upcoming_creator_features}),
     * for matching the creator docs to toggles.
     */
    static String normalizeName(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "");
    }

    private static boolean identifierChar(int b) {
        return b == '_' || (b >= '0' && b <= '9') || (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z');
    }

    private static boolean identifierBytes(ByteBuffer data, int from, int length) {
        for (int i = from; i < from + length; i++) {
            if (!identifierChar(data.get(i) & 0xff)) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(ByteBuffer data, int from, int to) {
        byte[] bytes = new byte[to - from];
        data.get(from, bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}
