package org.geysermc.paletter;

import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.palette.FullPalette;
import org.geysermc.paletter.palette.PaletteVersion;
import org.geysermc.paletter.palette.block.BlockAttributes;
import org.geysermc.paletter.palette.block.BlockPalette;
import org.geysermc.paletter.palette.block.BlockPropertiesPalette;
import org.geysermc.paletter.palette.item.CreativeItemPalette;
import org.geysermc.paletter.palette.item.ItemComponentPalette;
import org.geysermc.paletter.palette.item.ItemPalette;
import org.geysermc.paletter.utils.FileUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class PaletterPalettes {
    private PaletterPalettes() {
        throw new IllegalArgumentException("no.");
    }

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .build();

    private static final Map<PaletteVersion, FullPalette> LOADED_PALETTES = new HashMap<>();

    private static LogListener logListener = new LogListener.Default();
    private static Path cachePath = Path.of("cache", "paletter");
    private static boolean cachingEnabled = !Boolean.getBoolean("Paletter.CachingDisabled");

    public static void setLogListener(LogListener logListener) {
        PaletterPalettes.logListener = logListener;
    }

    public static void setCachePath(Path cachePath) {
        PaletterPalettes.cachePath = cachePath;
    }

    public static void setCachingEnabled(boolean cachingEnabled) {
        PaletterPalettes.cachingEnabled = cachingEnabled;
    }

    public static FullPalette getOrLoad(PaletteVersion version) throws IOException {
        if (!LOADED_PALETTES.containsKey(version)) {
            load(version);
        }
        return LOADED_PALETTES.get(version);
    }

    public static void init() throws IOException {
        logListener.info("Initialising Paletter...");
        if (cachingEnabled) {
            Path metadataCache = cachePath.resolve("metadata.json");
            if (Files.notExists(cachePath) || Files.notExists(metadataCache)) {
                prepareDirectory();
            } else {
                JsonNode node = JSON_MAPPER.readTree(metadataCache);
                int formatVersion = node.get("formatVersion").intValue();
                if (formatVersion < PaletterConstants.FORMAT_VERSION) {
                    logListener.info("Old metadata found! Invaliding palette cache...");
                    prepareDirectory();
                } else if (formatVersion > PaletterConstants.FORMAT_VERSION) {
                    logListener.warn("New metadata found! Invaliding palette cache...");
                    prepareDirectory();
                }
            }
        }
        logListener.info("Initialised Paletter!");
    }

    private static void prepareDirectory() throws IOException {
        Path metadataCache = cachePath.resolve("metadata.json");
        FileUtils.delete(cachePath);
        Files.createDirectories(cachePath);

        JSON_MAPPER.writeValue(metadataCache, new CacheMetadata(PaletterConstants.FORMAT_VERSION));
    }

    public static void load(PaletteVersion version) throws IOException {
        if (LOADED_PALETTES.containsKey(version)) {
            logListener.info("Palette is already loaded. (v%s [%d])".formatted(version.defaultVersion(), version.protocolVersion()));
            return;
        }

        Path cacheStoreDirectory = cachePath.resolve("v%d".formatted(version.protocolVersion()));
        // Written only after every source file in extractData() is resolved and cached - its absence
        // means a prior extraction was interrupted partway through, leaving a partial directory that
        // would otherwise be mistaken for a complete, valid cache.
        Path completeMarker = cacheStoreDirectory.resolve(".complete");

        if (cachingEnabled) {
            if (Files.exists(cacheStoreDirectory)) {
                if (Files.isRegularFile(cacheStoreDirectory)) {
                    // what
                    logListener.warn("Invalid file found in Paletter storage directory, deleting...");
                    FileUtils.delete(cacheStoreDirectory);
                } else if (Files.notExists(completeMarker)) {
                    logListener.warn("Incomplete palette cache found, redownloading... (v%s [%d])".formatted(version.defaultVersion(), version.protocolVersion()));
                    FileUtils.delete(cacheStoreDirectory);
                } else {
                    logListener.info("Loading cached palette... (v%s [%d])".formatted(version.defaultVersion(), version.protocolVersion()));

                    LOADED_PALETTES.put(version, extractData(version, cacheStoreDirectory, null));

                    logListener.info("Loaded palette! (v%s [%d])".formatted(version.defaultVersion(), version.protocolVersion()));

                    return;
                }
            }
        }

        logListener.info("Downloading palette... (v%s [%d])".formatted(version.defaultVersion(), version.protocolVersion()));

        String downloadUrl = "https://codeload.github.com/%s/zip/%s".formatted(version.dataRepo(), version.dataCommitHash());

        logListener.debug("Download URL: %s".formatted(downloadUrl));
        Path storageDirectory = Files.createTempDirectory("paletter_");

        Path zip = storageDirectory.resolve("%s.zip".formatted(version.protocolVersion()));

        try (HttpClient client = HttpClient.newHttpClient()) {
            client.send(HttpRequest.newBuilder()
                            .header("User-Agent", "Paletter-vDEV")
                    .uri(URI.create(downloadUrl))
                    .build(), HttpResponse.BodyHandlers.ofFile(zip));
        } catch (InterruptedException e) {
            throw new IOException(e);
        }

        if (Files.notExists(zip)) {
            throw new IOException("Failed to download palette.");
        }

        try (FileSystem fileSystem = FileSystems.newFileSystem(zip)) {
            LOADED_PALETTES.put(version, extractData(version, Files.list(fileSystem.getPath("/")).findFirst().get(), cacheStoreDirectory));
        }

        if (cachingEnabled) {
            Files.createFile(completeMarker);
        }

        logListener.info("Loaded palette! (v%s [%d])".formatted(version.defaultVersion(), version.protocolVersion()));
    }

    private static FullPalette extractData(PaletteVersion version, Path root, Path cacheDirectory) throws IOException {
        if (cacheDirectory != null) Files.createDirectories(cacheDirectory);

        Path blockAttributesPath = resolveAndCache(root, cacheDirectory, "blocks.json");
        List<BlockAttributes> blockAttributes = BlockAttributes.listFromJson(JSON_MAPPER.readTree(blockAttributesPath));

        Path blockPalettePath = resolveAndCache(root, cacheDirectory, "block_palette.nbt");
        BlockPalette blockPalette;
        try (InputStream stream = Files.newInputStream(blockPalettePath);
             NBTInputStream nbtInputStream = new NBTInputStream(new DataInputStream(new GZIPInputStream(stream)), true, true)) {
            NbtMap blockPaletteMap = (NbtMap) nbtInputStream.readTag();
            blockPalette = BlockPalette.fromNbt(blockPaletteMap.getList("blocks", NbtType.COMPOUND), blockAttributes);
        }

        Path itemStatesPath = resolveAndCache(root, cacheDirectory, "runtime_item_states.json");
        ItemPalette itemPalette = ItemPalette.fromJson(JSON_MAPPER.readTree(itemStatesPath));

        Path itemComponentsPath = resolveAndCache(root, cacheDirectory, "item_components.nbt");
        ItemComponentPalette itemComponentPalette;
        try (InputStream stream = Files.newInputStream(itemComponentsPath);
             NBTInputStream nbtInputStream = new NBTInputStream(new DataInputStream(new GZIPInputStream(stream)), true, true)) {
            NbtMap itemComponentsMap = (NbtMap) nbtInputStream.readTag();
            itemComponentPalette = ItemComponentPalette.fromNbt(itemComponentsMap);
        }

        Path creativeItemsPath = resolveAndCache(root, cacheDirectory, "creative_items.json");
        CreativeItemPalette creativeItemPalette = CreativeItemPalette.fromJson(JSON_MAPPER.readTree(creativeItemsPath));

        Path blockPropertiesPath = resolveAndCache(root, cacheDirectory, "block_properties.json");
        BlockPropertiesPalette blockPropertiesPalette = BlockPropertiesPalette.fromJson(JSON_MAPPER.readTree(blockPropertiesPath));

        return new FullPalette(version, blockPalette, blockPropertiesPalette, itemPalette, itemComponentPalette, creativeItemPalette);
    }

    private static Path resolveAndCache(Path root, Path cacheDirectory, String fileName) throws IOException {
        Path path = root.resolve(fileName);
        if (Files.notExists(path)) {
            throw new IOException("Unable to locate " + fileName + ".");
        }

        if (cachingEnabled && cacheDirectory != null) {
            FileUtils.copy(path, cacheDirectory.resolve(fileName));
        }

        return path;
    }
}
