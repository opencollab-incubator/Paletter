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
        JsonObject builds = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("builds");
        JsonElement build = builds == null ? null : builds.get(version);
        JsonObject zips = build != null && build.isJsonObject() ? build.getAsJsonObject().getAsJsonObject("zips") : null;
        JsonElement zip = zips == null ? null : zips.get(platform);
        JsonElement sha256 = zip != null && zip.isJsonObject() ? zip.getAsJsonObject().get("sha256") : null;
        return sha256 == null || sha256.getAsString().equals(UNKNOWN) ? null : sha256.getAsString();
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
