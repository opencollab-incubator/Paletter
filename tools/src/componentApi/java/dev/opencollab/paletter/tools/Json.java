package dev.opencollab.paletter.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Reading and writing the JSON files of the repository the same way: UTF-8, a {@code .gz} file read through gzip, and written
 * indented, without escaping {@code <}, {@code >}, {@code =} and the like, with a trailing newline.
 */
public final class Json {
    /** Indented, no HTML escaping. */
    public static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    /** One line, no HTML escaping. */
    public static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    public static JsonElement element(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                bytes = in.readAllBytes();
            }
        }
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
    }

    public static JsonObject object(Path file) throws IOException {
        return element(file).getAsJsonObject();
    }

    /** The object of {@code file}, or an empty one if there is no such file. */
    public static JsonObject objectOrEmpty(Path file) throws IOException {
        return Files.exists(file) ? object(file) : new JsonObject();
    }

    /** Writes {@code element} indented, with a trailing newline. */
    public static void write(Path file, JsonElement element) throws IOException {
        Files.writeString(file, PRETTY.toJson(element) + "\n", StandardCharsets.UTF_8);
    }

    /** The strings of the array {@code key} of {@code object}; none if it has no such array. */
    public static List<String> strings(JsonObject object, String key) {
        List<String> strings = new ArrayList<>();
        if (object.has(key) && object.get(key).isJsonArray()) {
            for (JsonElement element : (JsonArray) object.get(key)) {
                strings.add(element.getAsString());
            }
        }
        return strings;
    }
}
