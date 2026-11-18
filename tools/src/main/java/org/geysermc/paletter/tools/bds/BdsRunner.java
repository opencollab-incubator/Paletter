package org.geysermc.paletter.tools.bds;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.client.LevelDat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs a Paletter addon (the dump addon, the behaviour probe) on a Bedrock Dedicated Server and captures its console
 * output.
 * <ol>
 *     <li>installs the addon as a behavior pack on a fresh flat world;</li>
 *     <li>starts BDS headless, copies its output to the log file, waits for the addon's {@code <marker>-DONE} (the dump
 *     addon prints {@code PALETTER-DONE}) and stops it.</li>
 * </ol>
 * Downloading BDS means accepting the Minecraft EULA and privacy policy (https://minecraft.net/eula); BDS itself asks
 * for nothing else. Keep the server directory outside the repository.
 */
public final class BdsRunner {
    public static final String WORLD = "paletter";

    private final Path addon;
    private final String marker;
    private final String packDirectory;
    private List<String> experiments = List.of();
    private String betaModule;
    private String gametestModule;
    private Map<String, String> packFiles = Map.of();
    private Map<String, byte[]> binaryPackFiles = Map.of();

    /** The dump addon: it prints {@code PALETTER-DONE}. */
    public BdsRunner(Path addon) {
        this(addon, "PALETTER", "paletter_dumper");
    }

    /**
     * @param marker the addon prints {@code <marker>-DONE} when it is finished and {@code <marker>-FAILED} if it failed
     * @param packDirectory the name of the pack's directory in the server's {@code behavior_packs}
     */
    public BdsRunner(Path addon, String marker, String packDirectory) {
        this.addon = addon;
        this.marker = marker;
        this.packDirectory = packDirectory;
    }

    /**
     * Runs the add-on with the beta Script API: on a world with the Beta APIs experiment ({@code gametest}; BDS starts
     * once to create the fresh world, stops, and the toggle is set in its {@code level.dat}), and with the add-on's
     * {@code @minecraft/server} dependency set to the beta module of this build ({@link ScriptModules#betaVersion}).
     *
     * @param betaModule the dependency version, e.g. {@code 2.12.0-beta}
     */
    public BdsRunner withBetaApis(String betaModule) {
        this.experiments = List.of("gametest");
        this.betaModule = betaModule;
        return this;
    }

    /** Sets the add-on's {@code @minecraft/server-gametest} dependency, e.g. {@code 1.0.0-beta} ({@link ScriptModules#gametestBetaVersion}). */
    public BdsRunner withGametestModule(String gametestModule) {
        this.gametestModule = gametestModule;
        return this;
    }

    /** Files the run adds to the installed pack (pack path to content), e.g. loot tables the tool derives from BDS. */
    public BdsRunner withPackFiles(Map<String, String> files) {
        this.packFiles = files;
        return this;
    }

    /** Files the run adds to the installed pack that are not text (pack path to bytes), e.g. a structure. */
    public BdsRunner withBinaryPackFiles(Map<String, byte[]> files) {
        this.binaryPackFiles = files;
        return this;
    }

    /**
     * Installs the addon on a fresh world, runs BDS until the addon printed {@code PALETTER-DONE} and stops it.
     */
    public void run(Path server, Path log, int timeoutSeconds, int port, boolean echo) throws IOException, InterruptedException {
        installPack(server);
        configure(server, port);
        if (!experiments.isEmpty()) {
            try (BdsServer bds = new BdsServer(server, echo)) {
                bds.start(log.resolveSibling(log.getFileName() + ".create"));
                bds.awaitStarted(timeoutSeconds);
            }
            LevelDat.enableExperiments(server.resolve("worlds").resolve(WORLD).resolve("level.dat"), experiments);
        }
        System.out.println("Starting BDS in " + server + ", log: " + log);
        dump(server, log, timeoutSeconds, echo);
        System.out.println("Dump complete: " + log);
    }

    /**
     * Runs the dump again on the world of the last {@link #run}, with exactly these experiment toggles in its
     * {@code level.dat} (BDS applies them when it loads the world).
     */
    public void rerun(Path server, Path log, int timeoutSeconds, int port, Collection<String> toggles) throws IOException, InterruptedException {
        Path levelDat = server.resolve("worlds").resolve(WORLD).resolve("level.dat");
        if (!Files.exists(levelDat)) {
            throw new IOException("No dump world to rerun in " + levelDat.getParent());
        }
        LevelDat.setExperiments(levelDat, toggles);
        configure(server, port);
        dump(server, log, timeoutSeconds, false);
    }

    static void configure(Path server, int port) throws IOException {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("level-name", WORLD);
        properties.put("level-type", "FLAT");
        properties.put("level-seed", "0");
        properties.put("server-port", String.valueOf(port));
        properties.put("server-portv6", String.valueOf(port + 1));
        properties.put("enable-lan-visibility", "false");
        properties.put("online-mode", "false");
        properties.put("content-log-console-output-enabled", "true");
        properties.put("content-log-level", "warning");
        properties.put("allow-list", "false");
        BdsServer.configure(server, properties);
    }

    private void dump(Path server, Path log, int timeoutSeconds, boolean echo) throws IOException, InterruptedException {
        String result;
        boolean exited;
        List<String> progress;
        try (BdsServer bds = new BdsServer(server, echo)) {
            bds.start(log);
            result = bds.await(line -> line.contains(marker + "-DONE") || line.contains(marker + "-FAILED"),
                line -> line.contains(marker + "-PROGRESS"), timeoutSeconds);
            exited = !bds.isRunning();
            progress = bds.lines(line -> line.contains(marker + "-PROGRESS"));
        }
        checkResult(result, marker, timeoutSeconds, exited, progress.isEmpty() ? null : progress.getLast(), log);
    }

    /**
     * @param result the line that ended the run ({@code <marker>-DONE} or {@code <marker>-FAILED}, which ends it at once), or null if
     *               BDS exited or printed no {@code <marker>-PROGRESS} line for the timeout
     * @param exited whether BDS had exited when the run ended
     * @param lastProgress the last {@code <marker>-PROGRESS} line, or null: where a run without a result stopped
     */
    static void checkResult(String result, String marker, int timeoutSeconds, boolean exited, String lastProgress, Path log) throws IOException {
        if (result == null) {
            String where = lastProgress == null ? "before any " + marker + "-PROGRESS line"
                : "after " + lastProgress.substring(lastProgress.indexOf(marker + "-PROGRESS")).strip();
            throw new IOException((exited ? "BDS exited" : "BDS was silent for " + timeoutSeconds + "s") + " without " + marker
                + "-DONE, " + where + "; see " + log);
        }
        if (!result.contains(marker + "-DONE")) {
            throw new IOException("the add-on failed: " + result.substring(result.indexOf(marker + "-FAILED")).strip() + "; see " + log);
        }
    }

    private void installPack(Path server) throws IOException {
        Path pack = server.resolve("behavior_packs").resolve(packDirectory);
        BdsDistribution.deleteRecursively(pack);
        try (Stream<Path> files = Files.walk(addon)) {
            for (Path file : files.toList()) {
                Path target = pack.resolve(addon.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        for (Map.Entry<String, String> file : packFiles.entrySet()) {
            Path target = pack.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        for (Map.Entry<String, byte[]> file : binaryPackFiles.entrySet()) {
            Path target = pack.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }
        JsonObject manifest = JsonParser.parseString(Files.readString(addon.resolve("manifest.json"))).getAsJsonObject();
        if (betaModule != null || gametestModule != null) {
            if (betaModule != null) {
                ScriptModules.setServerModule(manifest, betaModule);
            }
            if (gametestModule != null) {
                ScriptModules.setGametestModule(manifest, gametestModule);
            }
            Files.writeString(pack.resolve("manifest.json"), manifest.toString());
        }
        JsonObject header = manifest.getAsJsonObject("header");
        String uuid = header.get("uuid").getAsString();
        String version = header.get("version").toString();

        // A fresh world every time: nothing of a previous run (or a previous BDS version) leaks into the dump
        Path world = server.resolve("worlds").resolve(WORLD);
        BdsDistribution.deleteRecursively(world);
        Files.createDirectories(world);
        Files.writeString(world.resolve("world_behavior_packs.json"),
            "[{\"pack_id\": \"" + uuid + "\", \"version\": " + version + "}]\n");
    }
}
