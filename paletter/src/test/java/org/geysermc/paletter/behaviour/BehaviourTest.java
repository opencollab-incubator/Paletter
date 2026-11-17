package org.geysermc.paletter.behaviour;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.block.component.CollisionBox;
import org.geysermc.paletter.block.component.DestructibleByMining;
import org.geysermc.paletter.block.component.Friction;
import org.geysermc.paletter.block.component.LightEmission;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.behaviour.Behaviour;
import org.geysermc.paletter.internal.behaviour.BehaviourStore;
import org.geysermc.paletter.internal.behaviour.Provenance;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.item.component.MaxStackSize;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviourTest {
    private static final Paletter PALETTER = Paletter.builder().build();
    private static final DataVersion LATEST = PALETTER.latest();

    private static Palette latest() {
        return PALETTER.vanilla(LATEST);
    }

    /** @return the value the handles are built from: the resolution of the provenance path, boxed */
    private static Object resolved(Behaviour behaviour, RegisteredBlockState state, BehaviourKey<?> key) {
        Behaviour.Value value = behaviour.resolve(state, key);
        return value == null ? null : value.value();
    }

    private static Object resolved(Behaviour behaviour, RegisteredItem item, BehaviourKey<?> key) {
        Behaviour.Value value = behaviour.resolve(item, key);
        return value == null ? null : value.value();
    }

    /** @return what {@code getOrElse} says, boxed, null where there is no value */
    private static Object boxed(BlockValues values, BehaviourKey<?> key) {
        return switch (key) {
            case FloatKey k -> Float.isNaN(values.getOrElse(k, Float.NaN)) ? null : values.getOrElse(k, Float.NaN);
            case IntKey k -> values.getOrElse(k, Integer.MIN_VALUE) == Integer.MIN_VALUE ? null : values.getOrElse(k, 0);
            case BooleanKey k -> values.getOrElse(k, false) != values.getOrElse(k, true) ? null : values.getOrElse(k, false);
            case ObjectKey<?> k -> values.getOrElse(k, null);
        };
    }

    private static Object boxed(ItemValues values, BehaviourKey<?> key) {
        return switch (key) {
            case FloatKey k -> Float.isNaN(values.getOrElse(k, Float.NaN)) ? null : values.getOrElse(k, Float.NaN);
            case IntKey k -> values.getOrElse(k, Integer.MIN_VALUE) == Integer.MIN_VALUE ? null : values.getOrElse(k, 0);
            case BooleanKey k -> values.getOrElse(k, false) != values.getOrElse(k, true) ? null : values.getOrElse(k, false);
            case ObjectKey<?> k -> values.getOrElse(k, null);
        };
    }

    @Test
    void vanillaBlockValuesInEveryVersion() {
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = PALETTER.vanilla(version);
            assertEquals(0.98f, palette.blockBehaviour(BedrockBlocks.ICE).get(BlockBehaviour.FRICTION), version.name());
            assertEquals(0.989f, palette.blockBehaviour(BedrockBlocks.BLUE_ICE).get(BlockBehaviour.FRICTION), version.name());
            assertEquals(0.6f, palette.blockBehaviour(BedrockBlocks.STONE).get(BlockBehaviour.FRICTION), version.name());
            assertEquals(1.5f, palette.blockBehaviour(BedrockBlocks.STONE).get(BlockBehaviour.DESTROY_TIME), version.name());
            assertEquals(BlockShape.FULL_CUBE, palette.blockBehaviour(BedrockBlocks.STONE).get(BlockBehaviour.COLLISION_SHAPE));
            assertEquals(BlockShape.EMPTY, palette.blockBehaviour(BedrockBlocks.AIR).get(BlockBehaviour.COLLISION_SHAPE));
        }
    }

    @Test
    void everyWayToNameAStateGivesAnEqualHandle() {
        Palette palette = latest();
        BlockValues byType = palette.blockBehaviour(BedrockBlocks.ICE);
        assertEquals(byType, palette.blockBehaviour(BedrockBlocks.ICE.defaultState()));
        assertEquals(byType, palette.blockBehaviour(palette.runtimeId(BedrockBlocks.ICE.defaultState())));
        assertSame(palette.blockDefinition(BedrockBlocks.ICE.defaultState()), byType.state());
        assertThrows(IllegalArgumentException.class, () -> palette.blockBehaviour(0x7eadbeef), "no such runtime id");
        assertEquals(palette.itemBehaviour(BedrockItems.APPLE), palette.itemBehaviour(BedrockItems.APPLE));
    }

    @Test
    void stairsHaveSeveralBoxes() {
        BlockShape shape = latest().blockBehaviour(BedrockBlocks.OAK_STAIRS).get(BlockBehaviour.COLLISION_SHAPE);
        assertTrue(shape.boxes().size() >= 2, shape.toString());
    }

    /**
     * The newest palette's values from a fixture store instead of the bundled one, so the sources and versions are the
     * test's, not whatever the newest data import brought: stone's friction from a third-party dump of the newest
     * version, the sword's attack damage from an older version only, its stack size from the Script API.
     */
    private static Behaviour fixture() {
        Palette palette = latest();
        List<DataVersion> versions = PALETTER.versions();
        DataVersion older = versions.get(versions.indexOf(LATEST) - 1);
        String sword = BedrockItems.DIAMOND_SWORD.identifier();
        int stone = palette.blockDefinition(BedrockBlocks.STONE.defaultState()).getRuntimeId();
        BehaviourStore.Builder store = new BehaviourStore.Builder(versions);
        int allay = store.capture(older, Provenance.Source.ALLAY_DATA, "AllayMC/Allay@" + "a".repeat(40), List.of(), List.of(sword));
        store.items(ItemBehaviour.ATTACK_DAMAGE, allay, Map.of(sword, 7));
        int data = store.capture(LATEST, Provenance.Source.CLOUDBURST_DATA, "CloudburstMC/Data@" + "0123456789".repeat(4), List.of(stone), List.of());
        store.blocks(BlockBehaviour.FRICTION, data, Map.of(stone, 0.6f));
        int script = store.capture(LATEST, Provenance.Source.SCRIPT_API, "script", List.of(), List.of(sword));
        store.items(ItemBehaviour.MAX_STACK_SIZE, script, Map.of(sword, 1));
        return Behaviour.of(palette, store.build(), null);
    }

    @Test
    void provenanceSaysWhereAndWhetherInherited() {
        Palette palette = latest();
        Behaviour behaviour = fixture();
        RegisteredBlockState stone = palette.blockDefinition(BedrockBlocks.STONE.defaultState());
        Provenance block = behaviour.provenance(stone, BlockBehaviour.FRICTION);
        assertEquals(Provenance.Source.CLOUDBURST_DATA, block.source());
        assertFalse(block.inherited());
        assertTrue(block.reference().startsWith("CloudburstMC/Data@"), block.reference());

        // No capture of the newest version: the value is inherited from an older one
        RegisteredItem sword = Objects.requireNonNull(palette.itemDefinition(BedrockItems.DIAMOND_SWORD));
        Provenance item = behaviour.provenance(sword, ItemBehaviour.ATTACK_DAMAGE);
        assertEquals(Provenance.Source.ALLAY_DATA, item.source());
        assertTrue(item.inherited());
        assertEquals(LATEST, item.version());
        assertTrue(item.captured().compareTo(LATEST) < 0);

        Provenance stack = behaviour.provenance(sword, ItemBehaviour.MAX_STACK_SIZE);
        assertEquals(Provenance.Source.SCRIPT_API, stack.source());
        assertFalse(stack.inherited());
    }

    @Test
    void bundledProvenanceIsConsistent() {
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = PALETTER.vanilla(version);
            RegisteredBlockState stone = palette.blockDefinition(BedrockBlocks.STONE.defaultState());
            RegisteredItem sword = Objects.requireNonNull(palette.itemDefinition(BedrockItems.DIAMOND_SWORD));
            List<Provenance> provenances = List.of(palette.behaviour().provenance(stone, BlockBehaviour.FRICTION),
                palette.behaviour().provenance(sword, ItemBehaviour.ATTACK_DAMAGE), palette.behaviour().provenance(sword, ItemBehaviour.MAX_STACK_SIZE));
            for (Provenance provenance : provenances) {
                assertEquals(version, provenance.version());
                assertTrue(provenance.captured().compareTo(version) <= 0, provenance.toString());
                assertEquals(!provenance.captured().equals(version), provenance.inherited(), provenance.toString());
                assertFalse(provenance.userSet());
                assertFalse(provenance.reference().isBlank());
            }
        }
    }

    @Test
    void explainSaysTheValueAndWhereItComesFrom() {
        Palette palette = latest();
        Behaviour behaviour = fixture();
        String stone = behaviour.explain(palette.blockDefinition(BedrockBlocks.STONE.defaultState()), BlockBehaviour.FRICTION);
        assertEquals("0.6 from CloudburstMC/Data@0123456 (CLOUDBURST_DATA, third party), " + LATEST.name(), stone);

        RegisteredItem diamondSword = Objects.requireNonNull(palette.itemDefinition(BedrockItems.DIAMOND_SWORD));
        String sword = behaviour.explain(diamondSword, ItemBehaviour.ATTACK_DAMAGE);
        assertTrue(sword.contains("(ALLAY_DATA, third party)") && sword.endsWith("inherited by " + LATEST.name()), sword);

        String door = PALETTER.vanilla(PALETTER.versions().getFirst()).blockBehaviour(BedrockBlocks.IRON_DOOR).explain(BlockBehaviour.COLLISION_SHAPE);
        assertTrue(door.contains("; post-processed: ") && door.contains("; disputed: ") && door.contains("(ALLAY_DATA)"), door);

        CustomBlock plain = CustomBlock.builder("test:plain").build();
        Palette custom = palette.extend(PaletteLayer.builder("test").block(plain).build());
        String destroy = custom.blockBehaviour(custom.blocks().block("test:plain").defaultState().getRuntimeId()).explain(BlockBehaviour.DESTROY_TIME);
        assertTrue(destroy.startsWith("no value: "), destroy);
    }

    @Test
    void vanillaItemValues() {
        Palette palette = latest();
        assertEquals(16, palette.itemBehaviour(BedrockItems.ENDER_PEARL).get(ItemBehaviour.MAX_STACK_SIZE));
        assertEquals(1561, palette.itemBehaviour(BedrockItems.DIAMOND_SWORD).get(ItemBehaviour.MAX_DAMAGE));
        assertEquals(32, palette.itemBehaviour(BedrockItems.APPLE).get(ItemBehaviour.USE_DURATION), "from the component data");
        RegisteredItem apple = Objects.requireNonNull(palette.itemDefinition(BedrockItems.APPLE));
        assertEquals(Provenance.Source.WIRE, palette.behaviour().provenance(apple, ItemBehaviour.USE_DURATION).source());
        ItemValues bow = palette.itemBehaviour(BedrockItems.BOW);
        assertThrows(IllegalStateException.class, () -> bow.get(ItemBehaviour.USE_DURATION), "native item: needs the native capture");
        assertEquals(-1, bow.getOrElse(ItemBehaviour.USE_DURATION, -1));
    }

    @Test
    void customContentUsesItsComponents() {
        CustomBlock slab = CustomBlock.builder("test:slab")
            .component(Friction.of(0.1f))
            .component(DestructibleByMining.builder().secondsToDestroy(3).build())
            .component(LightEmission.of(10))
            .component(CollisionBox.of(CollisionBox.Entry.builder().origin(-8, 0, -8).size(16, 8, 16).build()))
            .build();
        CustomItem ruby = CustomItem.builder("test:ruby").component(MaxStackSize.of(16)).build();
        Palette palette = Paletter.builder().layer(PaletteLayer.builder("test").block(slab).item(ruby).build()).build()
            .palette(LATEST.protocolVersion());
        RegisteredBlockState state = palette.blocks().block("test:slab").defaultState();
        BlockValues values = palette.blockBehaviour(state.getRuntimeId());

        assertEquals(0.9f, values.get(BlockBehaviour.FRICTION), 1e-6f);
        assertEquals(3f, values.get(BlockBehaviour.DESTROY_TIME));
        assertEquals(10, values.get(BlockBehaviour.LIGHT_EMISSION));
        assertEquals(BlockShape.of(0, 0, 0, 1, 0.5f, 1), values.get(BlockBehaviour.COLLISION_SHAPE));
        assertEquals(0f, values.get(BlockBehaviour.EXPLOSION_RESISTANCE), "not on the wire: the BDS default");
        assertTrue(palette.behaviour().provenance(state, BlockBehaviour.EXPLOSION_RESISTANCE).bdsDefault());
        assertEquals(Provenance.Source.USER, palette.behaviour().provenance(state, BlockBehaviour.FRICTION).source());
        assertTrue(values.explain(BlockBehaviour.FRICTION).contains("(USER)"), values.explain(BlockBehaviour.FRICTION));

        assertEquals(16, palette.itemBehaviour(ruby.type()).get(ItemBehaviour.MAX_STACK_SIZE));
        assertEquals(0.98f, palette.blockBehaviour(BedrockBlocks.ICE).get(BlockBehaviour.FRICTION), "vanilla next to it");
    }

    @Test
    void keysAreChecked() {
        BlockValues stone = latest().blockBehaviour(BedrockBlocks.STONE);
        assertThrows(IllegalArgumentException.class, () -> stone.get(ItemBehaviour.MAX_STACK_SIZE));
        assertThrows(IllegalArgumentException.class, () -> stone.getOrElse(ItemBehaviour.MAX_STACK_SIZE, 1));
        assertThrows(IllegalArgumentException.class, () -> latest().itemBehaviour(BedrockItems.APPLE).get(BlockBehaviour.FRICTION));
        assertSame(BlockBehaviour.FRICTION, BlockBehaviour.key("friction"));
        assertEquals(null, BlockBehaviour.key("max_stack_size"));
        // A custom block that sets nothing has no value for a key whose BDS default was never captured
        Palette custom = latest().extend(PaletteLayer.builder("test").block(CustomBlock.builder("test:plain").build()).build());
        BlockValues plain = custom.blockBehaviour(custom.blocks().block("test:plain").defaultState().getRuntimeId());
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> plain.get(BlockBehaviour.DESTROY_TIME), "no default captured");
        assertTrue(missing.getMessage().contains("block:destroy_time") && missing.getMessage().contains("has no value"), missing.getMessage());
        assertEquals(2f, plain.getOrElse(BlockBehaviour.DESTROY_TIME, 2f));
        assertEquals(3f, plain.getOrElse(BlockBehaviour.DESTROY_TIME, 3f));
    }

    @Test
    void ordinalsAreDenseOverEveryKey() {
        List<BehaviourKey<?>> keys = new java.util.ArrayList<>(BlockBehaviour.keys());
        keys.addAll(ItemBehaviour.keys());
        for (int i = 0; i < keys.size(); i++) {
            assertEquals(i, keys.get(i).ordinal(), keys.get(i).toString());
        }
    }

    @Test
    void thirdPartyValuesAreMarkedAndBdsOnesAreNot() {
        Palette palette = latest();
        RegisteredBlockState stone = palette.blockDefinition(BedrockBlocks.STONE.defaultState());
        assertTrue(palette.behaviour().provenance(stone, BlockBehaviour.FRICTION).thirdParty());
        assertFalse(palette.behaviour().provenance(stone, BlockBehaviour.FRICTION).userSet());
        RegisteredItem sword = Objects.requireNonNull(palette.itemDefinition(BedrockItems.DIAMOND_SWORD));
        assertFalse(palette.behaviour().provenance(sword, ItemBehaviour.MAX_STACK_SIZE).thirdParty(), "own Script API capture");
        assertTrue(palette.behaviour().provenance(sword, ItemBehaviour.ATTACK_DAMAGE).thirdParty());
        assertFalse(Provenance.Source.WIRE.thirdParty());
        assertEquals(0, Provenance.Source.USER.ordinal(), "what the user set beats everything");
        assertTrue(Provenance.Source.ALLAY_DATA.ordinal() < Provenance.Source.BDS_DEFAULT.ordinal());
        assertTrue(Provenance.Source.CLOUDBURST_DATA.ordinal() < Provenance.Source.ALLAY_DATA.ordinal(), "Data ranks above Allay");
        assertTrue(Provenance.Source.WIRE.ordinal() < Provenance.Source.CLOUDBURST_DATA.ordinal(), "BDS evidence ranks above third parties");
        assertFalse(Provenance.Source.BDS_PROBE.thirdParty());
        assertTrue(Provenance.Source.BDS_PROBE.ordinal() < Provenance.Source.CLOUDBURST_DATA.ordinal(), "the probe is direct BDS evidence");
    }

    @Test
    void doorsAreDisputedAndPostProcessedInEveryVersionTheProbeDidNotMeasure() {
        for (DataVersion version : PALETTER.versions()) {
            if (version.equals(LATEST)) {
                continue; // measured: doorsAreWhereTheProbeMeasuredThem
            }
            Palette palette = PALETTER.vanilla(version);
            boolean disputed = false;
            for (RegisteredBlockState state : palette.blocks().states("minecraft:iron_door")) {
                Provenance provenance = palette.behaviour().provenance(state, BlockBehaviour.COLLISION_SHAPE);
                assertEquals(Provenance.Source.CLOUDBURST_DATA, provenance.source(), version.name());
                assertTrue(provenance.postProcessed(), version.name());
                assertTrue(provenance.postProcessing().contains("door_state_correction"), provenance.postProcessing());
                if (provenance.disputed()) {
                    disputed = true;
                    Provenance.Alternative alternative = provenance.alternatives().getFirst();
                    assertEquals(Provenance.Source.ALLAY_DATA, alternative.source());
                    assertTrue(alternative.value() instanceof BlockShape);
                    assertTrue(!alternative.value().equals(palette.blockBehaviour(state.getRuntimeId()).get(BlockBehaviour.COLLISION_SHAPE)));
                }
            }
            assertTrue(disputed, "doors are disputed in " + version.name());
            Provenance stone = palette.behaviour().provenance(palette.blockDefinition(BedrockBlocks.STONE.defaultState()), BlockBehaviour.COLLISION_SHAPE);
            assertFalse(stone.disputed());
            assertFalse(stone.postProcessed());
        }
    }

    /** Where a door's panel is, by what the probe measured on BDS 1.26.63.1: the 0.1825 thick panel in every state. */
    private static BlockShape probedPanel(String direction, boolean open, boolean hinge) {
        float high = 1 - 0.1825f;
        float low = 0.1825f;
        // closed: the panel is on the side the door faces; open: a quarter turn towards the hinge
        boolean xAxis = open ? direction.equals("east") || direction.equals("west") : direction.equals("north") || direction.equals("south");
        boolean highSide = !open ? direction.equals("north") || direction.equals("east")
            : hinge ? direction.equals("south") || direction.equals("east") : direction.equals("north") || direction.equals("west");
        float min = highSide ? high : 0;
        float max = highSide ? 1 : low;
        return xAxis ? BlockShape.of(min, 0, 0, max, 1, 1) : BlockShape.of(0, 0, min, 1, 1, max);
    }

    @Test
    void doorsAreWhereTheProbeMeasuredThem() {
        Palette palette = latest();
        int checked = 0;
        for (String door : List.of("minecraft:wooden_door", "minecraft:iron_door", "minecraft:copper_door", "minecraft:crimson_door")) {
            for (RegisteredBlockState state : palette.blocks().states(door)) {
                String direction = state.states().getString("minecraft:cardinal_direction");
                BlockShape expected = probedPanel(direction, state.states().getByte("open_bit") != 0, state.states().getByte("door_hinge_bit") != 0);
                Provenance provenance = palette.behaviour().provenance(state, BlockBehaviour.COLLISION_SHAPE);
                assertEquals(expected, palette.blockBehaviour(state.getRuntimeId()).get(BlockBehaviour.COLLISION_SHAPE), state.toString());
                assertEquals(Provenance.Source.BDS_PROBE, provenance.source(), state.toString());
                assertFalse(provenance.thirdParty());
                assertFalse(provenance.disputed(), "BDS settled the dispute of Data (0.1875) and Allay (0.1825)");
                assertTrue(provenance.postProcessing().startsWith("composed:"), provenance.postProcessing());
                checked++;
            }
        }
        assertEquals(4 * 32, checked);
        // The probe measured the collision, not the outline: that stays what the dumps say, disputed
        RegisteredBlockState door = palette.blocks().states("minecraft:iron_door").getFirst();
        Provenance outline = palette.behaviour().provenance(door, BlockBehaviour.OUTLINE_SHAPE);
        assertEquals(Provenance.Source.CLOUDBURST_DATA, outline.source());
        assertTrue(outline.disputed());
    }

    @Test
    void theSameStateHasTheValuesOfEachVersion() {
        // Vanilla states are shared between versions (same instance), their values are not: they are per palette
        Palette newest = latest();
        Palette oldest = PALETTER.vanilla(PALETTER.versions().getFirst());
        RegisteredBlockState door = newest.blocks().states("minecraft:iron_door").getFirst();
        assertSame(door, oldest.blockBehaviour(door.getRuntimeId()).state());
        assertNotEquals(newest.blockBehaviour(door.getRuntimeId()), oldest.blockBehaviour(door.getRuntimeId()));
        assertFalse(newest.blockBehaviour(door.getRuntimeId()).get(BlockBehaviour.COLLISION_SHAPE)
            .equals(oldest.blockBehaviour(door.getRuntimeId()).get(BlockBehaviour.COLLISION_SHAPE)), "measured in the newest version only");
    }

    /** @return {@code data/bds/<build>/behaviour-probe} of the newest build that has one */
    private static Path newestProbe() throws IOException {
        Path builds = Path.of(System.getProperty("paletter.root", "..")).resolve("data/bds");
        try (Stream<Path> stream = Files.list(builds)) {
            return stream.map(build -> build.resolve("behaviour-probe")).filter(Files::isDirectory)
                .max(Comparator.comparing(probe -> probe.getParent().getFileName().toString(), BehaviourTest::compareBuilds))
                .orElseThrow(() -> new AssertionError("no behaviour-probe capture in " + builds));
        }
    }

    /**
     * Orders {@code 1.26.63.1} before {@code 1.26.70.24}: number by number, as {@code Versions.ORDER} of the tools, which
     * this module's tests cannot see (the tools depend on this module).
     */
    private static int compareBuilds(String a, String b) {
        int[] x = Arrays.stream(a.split("\\.")).mapToInt(Integer::parseInt).toArray();
        int[] y = Arrays.stream(b.split("\\.")).mapToInt(Integer::parseInt).toArray();
        return Arrays.compare(x, y);
    }

    /** @return a value of the probe's capture as the key's type */
    private static Object probed(BehaviourKey<?> key, JsonElement value) {
        return switch (key) {
            case FloatKey k -> value.getAsFloat();
            case IntKey k -> value.getAsInt();
            case BooleanKey k -> value.getAsBoolean();
            case ObjectKey<?> k -> {
                // a shape: one [minX, minY, minZ, maxX, maxY, maxZ] per box
                List<Float> coordinates = new ArrayList<>();
                value.getAsJsonArray().forEach(box -> box.getAsJsonArray().forEach(c -> coordinates.add(c.getAsFloat())));
                float[] array = new float[coordinates.size()];
                for (int i = 0; i < array.length; i++) {
                    array[i] = coordinates.get(i);
                }
                yield BlockShape.of(array);
            }
        };
    }

    @Test
    void customBlocksGetTheDefaultsTheProbeMeasured() throws IOException {
        Path probe = newestProbe();
        JsonObject capture = JsonParser.parseString(Files.readString(probe.resolve("capture.json"))).getAsJsonObject();
        String build = probe.getParent().getFileName().toString();
        JsonObject bds = capture.getAsJsonObject("bds");

        CustomBlock block = CustomBlock.builder("test:plain").build();
        Palette palette = latest().extend(PaletteLayer.builder("test").block(block).build());
        Behaviour behaviour = palette.behaviour();
        RegisteredBlockState state = palette.blocks().block("test:plain").defaultState();
        BlockValues values = palette.blockBehaviour(state.getRuntimeId());

        List<BehaviourKey<?>> measured = new ArrayList<>();
        for (JsonElement element : capture.getAsJsonArray("defaults")) {
            JsonObject entry = element.getAsJsonObject();
            BehaviourKey<?> key = BlockBehaviour.keys().stream().filter(k -> k.toString().equals(entry.get("key").getAsString()))
                .findFirst().orElseThrow(() -> new AssertionError("no block key " + entry.get("key")));
            measured.add(key);
            assertEquals(probed(key, entry.get("value")), boxed(values, key), key.toString());
            Provenance provenance = behaviour.provenance(state, key);
            assertTrue(provenance.bdsDefault(), key.toString());
            assertFalse(provenance.userSet());
            assertTrue(provenance.reference().contains("BDS_PROBE data/bds/" + build + "/behaviour-probe/capture.json (BDS "
                + bds.get("version").getAsString() + ", Build ID " + bds.get("build").getAsString() + ")"), provenance.reference());
        }
        assertFalse(measured.isEmpty());
        // What a custom block that sets neither has is not known while the probe does not measure it
        for (BehaviourKey<?> key : List.of(BlockBehaviour.DESTROY_TIME, BlockBehaviour.OUTLINE_SHAPE)) {
            if (!measured.contains(key)) {
                assertTrue(behaviour.provenance(state, key).unknownDefault(), key.toString());
                // get has no value to give; getOrElse gives the fallback
                assertThrows(IllegalStateException.class, () -> strict(values, key), key.toString());
                assertNull(boxed(values, key), key.toString());
                assertTrue(values.explain(key).contains("(UNKNOWN_DEFAULT)"), values.explain(key));
            }
        }
        if (!measured.contains(BlockBehaviour.OUTLINE_SHAPE)) {
            assertSame(BlockShape.EMPTY, values.getOrElse(BlockBehaviour.OUTLINE_SHAPE, BlockShape.EMPTY));
        }
        if (!measured.contains(BlockBehaviour.DESTROY_TIME)) {
            assertEquals(-1f, values.getOrElse(BlockBehaviour.DESTROY_TIME, -1f));
        }
    }

    /** @return what {@code get} says, boxed; it throws where there is no value */
    private static Object strict(BlockValues values, BehaviourKey<?> key) {
        return switch (key) {
            case FloatKey k -> values.get(k);
            case IntKey k -> values.get(k);
            case BooleanKey k -> values.get(k);
            case ObjectKey<?> k -> values.get(k);
        };
    }

    @Test
    void userSetValuesAreToldFromDefaultsAndUnknownOnes() {
        CustomBlock block = CustomBlock.builder("test:block").component(Friction.of(0.1f)).build();
        CustomItem item = CustomItem.builder("test:thing").component(MaxStackSize.of(16)).build();
        Palette palette = latest().extend(PaletteLayer.builder("test").block(block).item(item).build());
        Behaviour behaviour = palette.behaviour();
        RegisteredBlockState state = palette.blocks().block("test:block").defaultState();

        Provenance friction = behaviour.provenance(state, BlockBehaviour.FRICTION);
        assertTrue(friction.userSet());
        assertEquals(0.9f, palette.blockBehaviour(state.getRuntimeId()).get(BlockBehaviour.FRICTION), 1e-6f, "component 0.1: engine friction 1 - 0.1");
        assertFalse(friction.thirdParty());

        // No component and no known BDS default: no value, and the provenance says why
        assertThrows(IllegalStateException.class, () -> palette.blockBehaviour(state.getRuntimeId()).get(BlockBehaviour.DESTROY_TIME));
        Provenance destroy = behaviour.provenance(state, BlockBehaviour.DESTROY_TIME);
        assertTrue(destroy.unknownDefault());
        assertEquals(Provenance.Source.UNKNOWN_DEFAULT, destroy.source());
        assertFalse(destroy.userSet());

        RegisteredItem registered = Objects.requireNonNull(palette.itemDefinition(item.type()));
        assertTrue(behaviour.provenance(registered, ItemBehaviour.MAX_STACK_SIZE).userSet());
        assertTrue(behaviour.provenance(registered, ItemBehaviour.ARMOR_VALUE).unknownDefault());

        // Vanilla values are never user-set
        RegisteredBlockState stone = palette.blockDefinition(BedrockBlocks.STONE.defaultState());
        assertFalse(behaviour.provenance(stone, BlockBehaviour.FRICTION).userSet());
    }

    @Test
    void aKnownBdsDefaultIsAMatterOfDataOnly() {
        CustomBlock block = CustomBlock.builder("test:block").component(Friction.of(0.1f)).build();
        Palette palette = latest().extend(PaletteLayer.builder("test").block(block).build());
        BehaviourStore.Builder builder = new BehaviourStore.Builder(List.of(LATEST));
        builder.defaultOf(BlockBehaviour.DESTROY_TIME, 1f, Provenance.Source.NATIVE, "test capture");
        Behaviour behaviour = Behaviour.of(palette, builder.build(), null);
        BlockValues values = behaviour.block(palette.blocks().indexOf(palette.blocks().block("test:block").defaultState()));

        assertEquals(1f, values.get(BlockBehaviour.DESTROY_TIME));
        Provenance provenance = behaviour.provenance(values.state(), BlockBehaviour.DESTROY_TIME);
        assertEquals(Provenance.Source.BDS_DEFAULT, provenance.source());
        assertTrue(provenance.bdsDefault());
        assertFalse(provenance.userSet());
        assertTrue(provenance.reference().contains("NATIVE") && provenance.reference().contains("test capture"), provenance.reference());
        assertEquals(0.9f, values.get(BlockBehaviour.FRICTION), 1e-6f, "what the user set wins");
        assertEquals(-1f, values.getOrElse(BlockBehaviour.EXPLOSION_RESISTANCE, -1f), "another key without a default stays unknown");
    }

    @Test
    void sessionPalettesShareTheVanillaValues() {
        Palette base = latest();
        BlockValues stone = base.blockBehaviour(BedrockBlocks.STONE);
        CustomBlock block = CustomBlock.builder("test:block").component(Friction.of(0.1f)).build();
        Palette session = base.extend(PaletteLayer.builder("session").block(block).build());

        assertEquals(stone, session.blockBehaviour(BedrockBlocks.STONE), "a shared state has the values of the parent palette");
        int custom = session.blocks().block("test:block").defaultState().getRuntimeId();
        assertEquals(0.9f, session.blockBehaviour(custom).get(BlockBehaviour.FRICTION), 1e-6f);
        assertThrows(IllegalArgumentException.class, () -> base.blockBehaviour(custom), "not a state of the parent");

        // Another layer on top of the session still shares them, and the first session's block
        CustomBlock other = CustomBlock.builder("test:other").component(Friction.of(0.2f)).build();
        Palette second = session.extend(PaletteLayer.builder("second").block(other).build());
        assertEquals(stone, second.blockBehaviour(BedrockBlocks.STONE));
        assertEquals(session.blockBehaviour(custom), second.blockBehaviour(custom));
        assertEquals(0.8f, second.blockBehaviour(second.blocks().block("test:other").defaultState().getRuntimeId()).get(BlockBehaviour.FRICTION), 1e-6f);

        // A layer without blocks adds no states: every value is the parent's
        Palette items = base.extend(PaletteLayer.builder("items").item(CustomItem.builder("test:i").build()).build());
        assertEquals(stone, items.blockBehaviour(BedrockBlocks.STONE));
    }

    /** A palette with every kind of content: vanilla, a custom block and item, and an override of a vanilla item. */
    private static Palette mixed() {
        CustomBlock block = CustomBlock.builder("test:block").component(Friction.of(0.1f)).build();
        CustomItem item = CustomItem.builder("test:thing").component(MaxStackSize.of(16)).build();
        return latest().extend(PaletteLayer.builder("test").block(block).item(item)
            .overrideItem(BedrockItems.APPLE, apple -> apple.replaceComponents(MaxStackSize.of(3))).build());
    }

    @Test
    void theHandlesHoldTheValuesOfTheProvenancePath() {
        for (Palette palette : List.of(PALETTER.vanilla(PALETTER.versions().getFirst()), latest(), mixed())) {
            Behaviour behaviour = palette.behaviour();
            for (BehaviourKey<?> key : BlockBehaviour.keys()) {
                for (int i = 0; i < palette.blocks().size(); i += 7) {
                    RegisteredBlockState state = palette.blocks().byIndex(i);
                    assertEquals(resolved(behaviour, state, key), boxed(palette.blockBehaviour(state.getRuntimeId()), key), state + " " + key);
                }
            }
            for (BehaviourKey<?> key : ItemBehaviour.keys()) {
                for (RegisteredItem item : palette.items().definitions()) {
                    assertEquals(resolved(behaviour, item, key), boxed(Objects.requireNonNull(behaviour.item(item)), key), item + " " + key);
                }
            }
        }
    }

    @Test
    void overriddenItemsAreUserSetForTheComponentsOfTheLayer() {
        Palette palette = mixed();
        RegisteredItem apple = Objects.requireNonNull(palette.itemDefinition(BedrockItems.APPLE));
        assertEquals(3, palette.itemBehaviour(BedrockItems.APPLE).get(ItemBehaviour.MAX_STACK_SIZE));
        assertTrue(palette.behaviour().provenance(apple, ItemBehaviour.MAX_STACK_SIZE).userSet());
        assertFalse(palette.behaviour().provenance(apple, ItemBehaviour.USE_DURATION).userSet());
        RegisteredItem pearl = Objects.requireNonNull(palette.itemDefinition(BedrockItems.ENDER_PEARL));
        assertFalse(palette.behaviour().provenance(pearl, ItemBehaviour.MAX_STACK_SIZE).userSet());
    }

    @Test
    void itemHandlesOnlyKnowTheItemsOfThePalette() {
        Palette palette = mixed();
        RegisteredItem foreign = Objects.requireNonNull(PALETTER.vanilla(PALETTER.versions().getFirst()).itemDefinition(BedrockItems.APPLE));
        assertEquals(null, palette.behaviour().item(foreign), "an item of another palette is not an item of this one");
        assertThrows(IllegalArgumentException.class, () -> latest().itemBehaviour(CustomItem.builder("test:nowhere").build().type()));
    }

    @Test
    void aColumnIsBuiltOnceWhenSeveralThreadsAskAtTheSameTime() throws Exception {
        Behaviour behaviour = latest().extend(PaletteLayer.builder("racing").build()).behaviour();
        try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<Object>> columns = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                boolean blocks = i % 2 == 0;
                columns.add(pool.submit(() -> blocks ? behaviour.blockColumn(BlockBehaviour.COLLISION_SHAPE) : behaviour.itemColumn(ItemBehaviour.MAX_STACK_SIZE)));
            }
            for (int i = 2; i < 8; i++) {
                assertSame(columns.get(i % 2).get(), columns.get(i).get());
            }
        }
    }
}
