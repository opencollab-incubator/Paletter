package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.bds.BdsDump;
import dev.opencollab.paletter.tools.bds.BdsServer;
import dev.opencollab.paletter.tools.bds.CapturePlatform;
import dev.opencollab.paletter.tools.client.GiveCapture;
import dev.opencollab.paletter.tools.client.NetherNet;
import dev.opencollab.paletter.tools.data.OrderingCheck;
import dev.opencollab.paletter.tools.model.addon.FixedAddons;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.client.Capture;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The server runs: the vanilla, experiment and creative order probe worlds, each captured over the network (hashed and
 * sequential block ids). The add-on world of the spec capture is {@link SpecCapture}'s.
 */
final class CaptureStage {
    private static final String VANILLA_WORLD = "paletter_vanilla";
    private static final String CAPTURE_PACK = "paletter_capture";
    private static final String GIVE_WORLD = "paletter_give";
    private static final String CREATIVE_WORLD = "paletter_creative";

    private final UpdateContext ctx;
    private final ReferencePalette palette;

    CaptureStage(UpdateContext ctx) {
        this.ctx = ctx;
        this.palette = new ReferencePalette(ctx);
    }

    void captures(Path server, Path work, BdsDump dump, Runs runs) throws Exception {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", ctx.port);
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("level-type", "FLAT");
        properties.put("level-seed", "0");
        properties.put("gamemode", "creative");
        properties.put("server-port", String.valueOf(ctx.port));
        properties.put("server-portv6", String.valueOf(ctx.port + 1));
        properties.put("transport", "raknet");
        properties.put("enable-lan-visibility", "false");
        properties.put("online-mode", "false");
        properties.put("allow-list", "false");
        properties.put("view-distance", "4");
        properties.put("content-log-console-output-enabled", "true");
        properties.put("content-log-level", "warning");

        // Vanilla world
        freshWorld(server, VANILLA_WORLD, null);
        properties.put("level-name", VANILLA_WORLD);
        properties.put("block-network-ids-are-hashes", "true");
        properties.put("enable-lan-visibility", "true"); // otherwise the RakNet pong does not carry the protocol
        BdsServer.configure(server, properties);
        properties.put("enable-lan-visibility", "false");
        List<String> network = null;
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-vanilla.log"));
            bds.awaitStarted(ctx.timeout);
            Capture.Reachable reachable = Capture.reachable(bds, address, 20);
            boolean raknet = reachable != null && !reachable.netherNet();
            if (reachable == null) {
                network = bds.lines(line -> line.contains("Accepting clients") || line.contains("supported, port")
                    || line.contains("NetherNet"));
            } else {
                runs.netherNet = reachable.netherNet();
                runs.protocol = reachable.requireProtocol();
                ctx.report.header.put("Protocol", runs.protocol + (raknet ? " (RakNet pong: " : " (NetherNet LAN discovery: ")
                    + reachable.status() + ")");
                ctx.report.header.put("Transport", raknet ? "RakNet" : "NetherNet (HTTP signaling on server-port, WebRTC; "
                    + "self signed identity bound to the login chain)");
                runs.existing = ctx.against != null ? ctx.containingVersion(runs.protocol) : ctx.bundledName(runs.protocol);
                runs.reference = palette.reference(runs.existing, dump, runs);
                Capture.Summary summary = capture(address, runs, work.resolve("vanilla"), null, "vanilla capture");
                runs.vanilla = work.resolve("vanilla");
                // Toggles start game reports for a world without any are part of vanilla (1.26.70 previews:
                // data_driven_vanilla_blocks_and_items), not toggles to attribute
                runs.experiments.alwaysOn = summary.enabledExperiments();
                if (!runs.experiments.alwaysOn.isEmpty()) {
                    ctx.report.check(Report.Status.INFO, "Experiments: always on", "start game of a world without experiments reports "
                        + runs.experiments.alwaysOn + ": treated as vanilla");
                }
                hashCheck("vanilla", summary);
            }
        }
        if (network != null) {
            ctx.report.check(Report.Status.FAIL, "Network capture", Capture.NO_PROTOCOL + " (port " + ctx.port + ")");
            ctx.report.evidence("Network lines of the server", List.of("```", String.join("\n", network), "```"));
            return;
        }
        properties.put("block-network-ids-are-hashes", "false");
        BdsServer.configure(server, properties);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-vanilla-sequential.log"));
            bds.awaitStarted(ctx.timeout);
            capture(address, runs, work.resolve("vanilla-sequential"), null, "vanilla capture (sequential ids)");
        }
        gives(server, work, address, runs, properties);

        // Experiments: discover the toggles, verify them, capture what each one adds
        runs.experiments.current = ExperimentDiscovery.recordedCurrent(ctx.bdsData, ctx.buildDirectory.getFileName().toString(),
            ExperimentDiscovery.previewProtocols(ctx.resources, ctx.preview));
        if (ctx.options.experiments() != null) {
            runs.experiments.source = "--experiments";
            runs.experiments.current = ctx.options.experiments().stream().sorted().toList();
        }
        try {
            new ExperimentStage(ctx, this).experiments(server, work, address, dump, runs, properties);
        } catch (Exception e) {
            ctx.report.fail("Experiments", e);
            ctx.report.action("check the toggles: the spec add-on world uses " + runs.experiments.current + " (" + runs.experiments.source + ")");
        }

        // Creative order probe: every kind of menu category placement, no experiments; hashed and sequential ids
        Path creativePack = work.resolve("creative-pack");
        FixedAddons.writeCreative(creativePack, runs.formatVersion);
        freshWorld(server, CREATIVE_WORLD, creativePack);
        properties.put("level-name", CREATIVE_WORLD);
        properties.put("block-network-ids-are-hashes", "true");
        BdsServer.configure(server, properties);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-creative.log"));
            bds.awaitStarted(ctx.timeout);
            Capture.Summary summary = capture(address, runs, work.resolve("creative"), work.resolve("creative/custom"),
                "creative order probe capture");
            runs.creative = work.resolve("creative");
            hashCheck("creative order probe", summary);
        }
        properties.put("block-network-ids-are-hashes", "false");
        BdsServer.configure(server, properties);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-creative-sequential.log"));
            bds.awaitStarted(ctx.timeout);
            capture(address, runs, work.resolve("creative-sequential"), null, "creative order probe capture (sequential ids)");
        }

        OrderingCheck ordering = new OrderingCheck();
        ordering.bundledOrder("reference palette " + runs.reference.version().name(), runs.reference.blockStates());
        ordering.sequential("vanilla", work.resolve("vanilla"), work.resolve("vanilla-sequential"));
        ordering.sequential("creative order probe", work.resolve("creative"), work.resolve("creative-sequential"));
        ordering.customBlockIds("creative order probe", work.resolve("creative"));
        ordering.passes.forEach(pass -> ctx.report.check(Report.Status.PASS, "Block numbering", pass));
        // A history check numbers against the bundled data version of its window, which may lack states of this build and
        // of the build it is checked against alike: the states themselves are compared through the Script API dump
        ordering.failures.forEach(failure -> {
            if (ctx.against != null) {
                ctx.report.check(Report.Status.INFO, "Block numbering", failure + " (against bundled " + runs.reference.version().name()
                    + "; block states are compared through the Script API dump)");
                ctx.report.notCompared.add("Block numbering: " + failure + " (against bundled " + runs.reference.version().name()
                    + "); the block states themselves are compared through the Script API dump");
            } else {
                ctx.report.check(Report.Status.FAIL, "Block numbering", failure);
            }
        });
        if (!ordering.withinBlockOrder.isEmpty()) {
            ctx.report.decision("Block palette: " + ordering.withinBlockOrder.size() + " creative states of generated blocks sit elsewhere "
                + "within their block than generated; fix the state order of those blocks before importing", "States",
                ordering.withinBlockOrder.stream().map(state -> "- " + state).toList());
        }
    }

    /**
     * A start of its own for {@code /give} ({@link GiveCapture}): the player has to run commands there, which the vanilla start, the
     * reference of the palette and of start game, does not allow. The settings stay local to this start; a failure is only INFO.
     */
    private void gives(Path server, Path work, InetSocketAddress address, Runs runs, Map<String, String> properties) {
        Map<String, String> own = new LinkedHashMap<>(properties);
        own.put("level-name", GIVE_WORLD);
        own.put("default-player-permission-level", "operator");
        own.put("allow-cheats", "true");
        own.put("block-network-ids-are-hashes", "true");
        try {
            freshWorld(server, GIVE_WORLD, null);
            BdsServer.configure(server, own);
            try (BdsServer bds = new BdsServer(server, false)) {
                bds.start(work.resolve("bds-give.log"));
                bds.awaitStarted(ctx.timeout);
                capture(address, runs, work.resolve("give"), null, "block item capture (/give)", true);
                runs.give = work.resolve("give");
            }
        } catch (Exception e) {
            ctx.report.check(Report.Status.INFO, "Block items (/give)", "not captured: " + e);
        } finally {
            // server.properties keeps what this start wrote: the next starts write the defaults again
            properties.put("default-player-permission-level", "member");
            properties.put("allow-cheats", "false");
        }
    }

    Capture.Summary capture(InetSocketAddress address, Runs runs, Path out, Path customOut, String what) throws Exception {
        return capture(address, runs, out, customOut, what, false);
    }

    Capture.Summary capture(InetSocketAddress address, Runs runs, Path out, Path customOut, String what, boolean give) throws Exception {
        Capture.Summary summary = Capture.capture(address, runs.protocol, runs.reference, runs.bdsVersion, CapturePlatform.host(), out, customOut,
            give ? 300 : 90, runs.netherNet, give);
        ctx.report.check(Report.Status.PASS, what, (summary.blockNetworkIdsHashed() ? "hashed" : "sequential") + " block ids; protocol "
            + summary.protocol() + (summary.nativeCodec() ? "" : ", decoded with the " + summary.codecProtocol() + " codec"));
        if (!summary.nativeCodec() && !codecReported) {
            // Once: every capture of the run decodes with the same codec
            codecReported = true;
            runs.codecProtocol = summary.codecProtocol();
            ctx.report.header.put("Protocol codec", codecNote(summary.protocol(), summary.codecProtocol()));
            ctx.report.check(Report.Status.DIFF, "Protocol codec", "no codec for " + summary.protocol() + " in the protocol library, "
                + "captures decoded with the " + summary.codecProtocol() + " codec: update it and rerun");
        }
        return summary;
    }

    /** The report header for a capture decoded with another protocol's codec, null for the native one. */
    static String codecNote(int protocol, int codecProtocol) {
        return protocol == codecProtocol ? null : "captures of protocol " + protocol + " decoded with the " + codecProtocol + " codec (codecProtocol in capture.json)";
    }

    /** Whether the missing protocol codec is reported already. */
    private boolean codecReported;

    void hashCheck(String label, Capture.Summary summary) {
        if (!summary.traitStatesUnknown().isEmpty()) {
            // BDS accepts enabled_states values the docs name no state for; the inference derives their states from BDS's
            // block states and checks them against the hashes BDS sent for these blocks
            ctx.report.check(Report.Status.INFO, "Trait states (" + label + ")", "BDS accepts enabled_states the trait state table "
                + "has no states for yet, " + summary.traitStatesUnknown() + ": their blocks' creative block ids are checked by the mapping "
                + "inference (TraitStateTable), not here");
            ctx.report.action("infer the mappings and run :tools:generateComponentApi (the trait state table gains "
                + summary.traitStatesUnknown() + ")");
        }
        if (!summary.experimentBlocksUnverified().isEmpty()) {
            // The bundled data version marks these blocks as experiment content but has no states for them
            ctx.report.check(Report.Status.INFO, "Experiment block hashes (" + label + ")", "the creative block ids of "
                + summary.experimentBlocksUnverified().size() + " experiment blocks are unverified: the reference palette marks them "
                + "as experiment content without states: " + summary.experimentBlocksUnverified());
            ctx.report.notCompared.add("Block hashes (" + label + "): the creative block ids of " + summary.experimentBlocksUnverified().size()
                + " experiment blocks the reference palette has no states for");
        }
        if (summary.unresolvedHashes() == 0) {
            ctx.report.check(Report.Status.PASS, "Block hashes (" + label + ")", "all " + summary.resolvedHashes()
                + " creative block ids are FNV-1a 32 hashes of palette states");
        } else if (ctx.against != null) {
            // A history check: the bundled data version of the window lacks states this build sends (and maybe the build it
            // is checked against too); the states themselves are compared through the Script API dump
            ctx.report.check(Report.Status.INFO, "Block hashes (" + label + ")", summary.unresolvedHashes() + " creative block ids match no "
                + "state of the reference palette (" + summary.resolvedHashes() + " do): this build has states the bundled data version "
                + "lacks; block states are compared through the Script API dump");
            ctx.report.notCompared.add("Block hashes (" + label + "): " + summary.unresolvedHashes() + " creative block ids match no state "
                + "of the bundled data version; the block states themselves are compared through the Script API dump");
        } else {
            ctx.report.check(Report.Status.FAIL, "Block hashes (" + label + ")", summary.unresolvedHashes() + " creative block ids match no "
                + "palette state (" + summary.resolvedHashes() + " do): the palette lacks states this version has");
            ctx.report.action("add those states to the palette (`creativeBlockIdsUnresolved` in capture.json)");
        }
    }

    void freshWorld(Path server, String world, Path addon) throws IOException {
        Path directory = server.resolve("worlds").resolve(world);
        BdsDistribution.deleteRecursively(directory);
        Files.createDirectories(directory);
        if (addon == null) {
            return;
        }
        String[] packs = {"behavior", "resource"};
        for (String kind : packs) {
            Path source = addon.resolve(kind + "_pack");
            Path target = server.resolve(kind + "_packs").resolve(CAPTURE_PACK);
            BdsDistribution.deleteRecursively(target);
            if (!Files.isDirectory(source)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(source)) {
                for (Path file : files.toList()) {
                    Path copy = target.resolve(source.relativize(file).toString());
                    if (Files.isDirectory(file)) {
                        Files.createDirectories(copy);
                    } else {
                        Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            JsonObject header = JsonParser.parseString(Files.readString(source.resolve("manifest.json")))
                .getAsJsonObject().getAsJsonObject("header");
            Files.writeString(directory.resolve("world_" + kind + "_packs.json"), "[{\"pack_id\": \""
                + header.get("uuid").getAsString() + "\", \"version\": " + header.get("version") + "}]\n");
        }
    }

    /** Whether start game reported exactly the requested toggles; retired ones are kept but never streamed. */
    void appliedCheck(ExperimentRun run, String what, List<String> requested, Capture.Summary summary) {
        List<String> echoed = summary.enabledExperiments().stream().filter(t -> !run.alwaysOn.contains(t)).toList();
        List<String> retired = run.retired;
        List<String> expected = requested.stream().filter(t -> !retired.contains(t)).sorted().toList();
        boolean ok = echoed.equals(expected);
        ctx.report.check(ok ? Report.Status.PASS : Report.Status.FAIL, "Experiments applied (" + what + ")",
            ok ? "start game reports " + echoed : "requested " + expected + ", start game reports " + echoed
                + (requested.stream().anyMatch(t -> !echoed.contains(t)) ? " (BDS dropped or retired toggles)" : ""));
    }
}
