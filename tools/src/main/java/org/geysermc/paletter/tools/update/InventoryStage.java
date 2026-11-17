package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.bds.BdsDistribution;
import org.geysermc.paletter.tools.bds.BdsInventory;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The inventory of the build's Linux binary, and the Script API getters the behaviour dump has to know. */
final class InventoryStage {
    private final UpdateContext ctx;

    InventoryStage(UpdateContext ctx) {
        this.ctx = ctx;
    }

    /**
     * The inventory of the build's Linux binary ({@link BdsInventory}: block and item components, Script API members) and what
     * changed against the inventory of the previous build: new BDS properties show up here before anything reads them.
     */
    void inventory(Path cache, BdsDistribution distribution) {
        try {
            JsonObject inventory = BdsInventory.read(ctx.root, distribution.version(), distribution.preview(), cache);
            if (inventory.getAsJsonObject("blockComponents").isEmpty()) {
                // The inventory reads RTTI type names; the Linux binaries of the 1.26.70 previews have none
                ctx.report.check(Report.Status.SKIP, "BDS inventory", "the Linux bedrock_server of this build has no RTTI type names "
                    + "(no Block*Description, no Scripting::Reflection bindings): nothing to inventory");
                return;
            }
            // A history check compares with the inventory of the build it is checked against
            String previousBuild = ctx.against != null ? ctx.against : UpdateContext.baseline(distribution.version(), ctx.committedBuilds(BdsInventory.FILE));
            Path previous = previousBuild == null ? null : ctx.bdsData.resolve(previousBuild).resolve(BdsInventory.FILE);
            if (previous == null || !Files.exists(previous)) {
                ctx.report.check(Report.Status.INFO, "BDS inventory", ctx.against != null ? "not compared: " + ctx.against + " has no " + BdsInventory.FILE
                    : "no inventory of an older build to diff against");
                if (ctx.against != null) {
                    ctx.report.notCompared.add("BDS inventory: " + ctx.against + " has no " + BdsInventory.FILE);
                }
            } else {
                String before = previousBuild;
                List<String> diff = BdsInventory.diff(BdsInventory.parse(previous), inventory);
                // Only getters the behaviour dump reads need a decision; the rest of the inventory is for reference. In a
                // history check any difference is one between the two builds
                String detail = diff.isEmpty() ? "no new or removed properties" : diff.size() + " new or removed properties";
                if (ctx.against != null) {
                    ctx.compared(diff.isEmpty(), "BDS inventory vs " + before, detail);
                } else {
                    ctx.report.check(diff.isEmpty() ? Report.Status.PASS : Report.Status.INFO, "BDS inventory vs " + before, detail);
                }
                if (!diff.isEmpty()) {
                    ctx.report.evidence("New or removed properties", diff.stream().map(line -> "- `" + line + "`").toList());
                }
                List<String> added = new ArrayList<>();
                List<String> removed = new ArrayList<>();
                for (String line : diff) {
                    String getter = behaviourGetter(line);
                    if (getter != null) {
                        (line.startsWith("+") ? added : removed).add(getter);
                    }
                }
                if (!added.isEmpty() || !removed.isEmpty()) {
                    ctx.compared(false, "Script API getters", (added.isEmpty() ? "" : added.size() + " new")
                        + (added.isEmpty() || removed.isEmpty() ? "" : ", ") + (removed.isEmpty() ? "" : removed.size() + " removed")
                        + " value getters of blocks, items or their components (the behaviour dump reads them)");
                    List<String> lines = new ArrayList<>();
                    added.forEach(getter -> lines.add("- new `" + getter + "`"));
                    removed.forEach(getter -> lines.add("- removed `" + getter + "`"));
                    ctx.report.action("give each a behaviour key or an ignored entry", "Getters", lines);
                }
            }
            ctx.stage(ctx.bdsData.resolve(distribution.version()).resolve(BdsInventory.FILE),
                BdsInventory.json(inventory).getBytes(StandardCharsets.UTF_8), "BDS inventory " + distribution.version());
        } catch (org.geysermc.paletter.tools.Http.NetworkException e) {
            if (e.status() != 0 && !org.geysermc.paletter.tools.Http.retried(e.status())) {
                // An answer a retry would not change (404 above all): the Linux build is not where it should be, which is a failure
                ctx.report.fail("BDS inventory", e);
                return;
            }
            // The Linux zip is only read for the inventory: without the network (or with a server error or rate limit that
            // outlasted the retries) there is nothing to inventory, nothing failed
            ctx.report.check(Report.Status.SKIP, "BDS inventory", "the Linux build could not be downloaded: " + e.getMessage());
        } catch (Exception e) {
            ctx.report.fail("BDS inventory", e);
        }
    }

    /** Script API classes whose getters the behaviour dump reads: the block, its permutation and type, the item stack. */
    private static final Set<String> BEHAVIOUR_TYPES = Set.of("Block", "BlockPermutation", "BlockType", "ItemStack");
    /** Component classes of runtime state, not of the block or item type or state: containers, recipes, stored entities. */
    private static final java.util.regex.Pattern RUNTIME_TYPES = java.util.regex.Pattern.compile("Recipe|Crafting|Inventory|Container"
        + "|EntityStorage|DynamicProperties|ItemActor");
    /**
     * Members that are no value of the type or state: native fields and ids, legacy {@code _010} bindings, live objects
     * (dimension, location, entities, players, items, blocks, permutations, the type object) and the getters that take an
     * argument (the inventory has no signatures, so these are named).
     */
    private static final java.util.regex.Pattern NOT_A_VALUE = java.util.regex.Pattern.compile("^m[A-Z]|^_?isValid|Ids?(\\(\\))?$"
        + "|_?V?010|Dimension|Location|Entit|Player|Container|ItemStack|Item\\(\\)$|Record|Context|Blocks|BlockParts|Permutation"
        + "|DynamicProperty|^(get)?Type$|^getState\\(|^getProperty|^getEnchantment\\(|^getDamageChance\\(|^getText\\(|^getRawText\\("
        + "|^getTextDyeColor\\(|^getInputSlotEnabled\\(");

    /**
     * The getter of a {@link BdsInventory#diff} line that the behaviour dump ({@code data/addons/behaviour-dump}) reads or
     * would read, e.g. {@code BlockPermutation.getAllStates()} for {@code + Script API ScriptModuleMinecraft
     * ScriptBlockPermutation getAllStates()}: a getter of {@link #BEHAVIOUR_TYPES} or of a block or item component that
     * returns a value of the type or state ({@link #RUNTIME_TYPES} and {@link #NOT_A_VALUE} are left out). A new one needs
     * a behaviour key or an ignored entry, or the key table generation fails.
     *
     * @return {@code Type.member}, or null for any other line
     */
    static String behaviourGetter(String line) {
        String[] parts = line.split(" ", 6);
        if (parts.length < 6 || !parts[1].equals("Script") || !parts[2].equals("API") || !parts[4].startsWith("Script")) {
            return null;
        }
        String type = parts[4].substring("Script".length());
        String member = parts[5];
        boolean component = (type.startsWith("Block") || type.startsWith("Item") || type.endsWith("ItemComponent"))
            && type.contains("Component") && !type.contains("CustomComponent") && !type.endsWith("Registry");
        String name = member.startsWith("get ") ? member.substring(4) : member;
        if (!(BEHAVIOUR_TYPES.contains(type) || component) || !member.startsWith("get") || RUNTIME_TYPES.matcher(type).find()
            || NOT_A_VALUE.matcher(name).find()) {
            return null;
        }
        return type + "." + name;
    }
}
