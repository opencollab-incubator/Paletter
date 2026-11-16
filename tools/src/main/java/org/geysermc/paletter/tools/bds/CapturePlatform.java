package org.geysermc.paletter.tools.bds;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The OS whose BDS made a capture, recorded as {@code platform} in its {@code capture.json}. Windows and Linux BDS of
 * the same build number custom blocks, order the creative inventory and encode some component NBT differently, so
 * captures of different platforms are never compared: {@code update} and the conformance suites refuse with a message
 * instead of reporting the platform difference as a data change.
 */
public final class CapturePlatform {
    public static final String KEY = "platform";
    public static final String WINDOWS = "windows";
    public static final String LINUX = "linux";
    private static final String CAPTURE = "capture.json";

    private CapturePlatform() {
    }

    /** The platform of the BDS this machine runs (the tools run the Windows build on Windows, else the Linux one). */
    public static String host() {
        return BdsServer.WINDOWS ? WINDOWS : LINUX;
    }

    /**
     * The one platform of every capture under {@code data/bds} ({@code <build>/<capture>/capture.json}), or null if there
     * are none.
     *
     * @throws IllegalStateException when captures record different platforms, or a capture records none
     */
    public static String single(Path bdsData) throws IOException {
        Map<String, Set<String>> builds = new TreeMap<>();
        if (Files.isDirectory(bdsData)) {
            try (Stream<Path> dirs = Files.list(bdsData)) {
                for (Path build : dirs.filter(Files::isDirectory).sorted().toList()) {
                    try (Stream<Path> captures = Files.list(build)) {
                        for (Path file : captures.map(d -> d.resolve(CAPTURE)).filter(CapturePlatform::isCapture).toList()) {
                            String platform = recorded(file);
                            builds.computeIfAbsent(platform == null ? "none recorded" : platform, k -> new TreeSet<>())
                                .add(bdsData.relativize(file.getParent()).toString().replace('\\', '/'));
                        }
                    }
                }
            }
        }
        if (builds.size() > 1 || builds.containsKey("none recorded")) {
            throw new IllegalStateException("The captures in " + bdsData + " must all come from one BDS platform (`" + KEY
                + "` in capture.json); captures of different platforms are not compared. Found: " + builds);
        }
        return builds.isEmpty() ? null : builds.keySet().iterator().next();
    }

    /**
     * Why a capture of {@code actual} cannot be compared with one of {@code expected}, or null if it can.
     *
     * @param what the committed capture, for the message
     */
    public static String mismatch(String what, String expected, String actual) {
        if (expected != null && expected.equals(actual)) {
            return null;
        }
        return what + " was captured on " + (expected == null ? "an unrecorded platform" : expected + " BDS") + ", this run on "
            + actual + " BDS: captures of different platforms are not compared (custom block numbering, creative order and "
            + "component NBT differ between them). Run on " + (expected == null ? "the platform of the committed captures" : expected) + ".";
    }

    /**
     * Whether a file is the {@code capture.json} of a BDS capture (client, spec or voxel shape capture: it names its
     * {@code bdsVersion}), not the measurements of the behaviour probe, which are never compared.
     */
    private static boolean isCapture(Path file) {
        try {
            JsonElement json = JsonParser.parseString(Files.readString(file));
            return json.isJsonObject() && json.getAsJsonObject().has("bdsVersion");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String recorded(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        JsonElement json = JsonParser.parseString(Files.readString(file));
        JsonElement platform = json.isJsonObject() ? json.getAsJsonObject().get(KEY) : null;
        return platform == null || !platform.isJsonPrimitive() ? null : platform.getAsString();
    }
}
