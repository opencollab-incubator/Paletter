package dev.opencollab.paletter.tools.bds;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The sha256 of the BDS zips of the kept builds, as {@code data/bds/builds.json} records them per build and platform
 * ({@code builds.<build>.zips.<platform>.sha256}; written by {@code BuildHistory} from zips at hand, {@link #UNKNOWN} when none was).
 * {@link BdsDistribution#zip} checks a zip against them, so a zip minecraft.net serves differently under the same name is
 * never captured unnoticed.
 */
public final class ZipChecksums {
    /** What the record says of a zip no one had at hand when the build was recorded: its checksum is not known. */
    public static final String UNKNOWN = "unknown";
    /** The record, relative to the repository root. */
    static final String RECORD = "data/bds/builds.json";

    private ZipChecksums() {
    }

    /** The lowercase hex sha256 of a file. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * The recorded sha256 of a build's zip on a platform ({@code win}, {@code linux}), or null when the repository has no record,
     * the record no zip of the build, or the checksum is {@link #UNKNOWN}.
     *
     * @param root the repository root: the tools run in it (every Gradle task of the tools sets it as the working directory)
     */
    public static String recorded(Path root, String platform, String version) throws IOException {
        Path file = root.resolve(RECORD);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        JsonElement builds = object(JsonParser.parseString(Files.readString(file)), "the file").get("builds");
        if (builds == null) {
            return null;
        }
        JsonElement build = object(builds, "builds").get(version);
        if (build == null) {
            return null;
        }
        JsonElement zips = object(build, "builds." + version).get("zips");
        if (zips == null) {
            return null;
        }
        JsonElement zip = object(zips, "builds." + version + ".zips").get(platform);
        if (zip == null) {
            return null;
        }
        String where = "builds." + version + ".zips." + platform;
        JsonElement sha256 = object(zip, where).get("sha256");
        if (sha256 == null || !sha256.isJsonPrimitive() || !sha256.getAsJsonPrimitive().isString()) {
            throw new IOException(RECORD + ": " + where + " has no \"sha256\" string");
        }
        return sha256.getAsString().equals(UNKNOWN) ? null : sha256.getAsString();
    }

    private static JsonObject object(JsonElement element, String where) throws IOException {
        if (!element.isJsonObject()) {
            throw new IOException(RECORD + ": " + where + " must be a JSON object, not " + element);
        }
        return element.getAsJsonObject();
    }

    /** Throws unless the zip has the expected sha256 (no check when {@code expected} is null). */
    static void verify(Path zip, String expected, String name) throws IOException {
        if (expected == null) {
            return;
        }
        String actual = sha256(zip);
        if (!actual.equals(expected)) {
            throw new IOException(name + " has sha256 " + actual + ", not the " + expected + " that " + RECORD
                + " records: minecraft.net serves another zip under this name, or the download is corrupt");
        }
    }
}
