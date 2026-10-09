package org.geysermc.paletter.runtime;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.api.BedrockVersion;
import org.geysermc.paletter.api.block.BedrockBlock;
import org.geysermc.paletter.api.block.BedrockBlockAttributes;
import org.geysermc.paletter.api.block.BedrockBlockState;
import org.geysermc.paletter.api.block.CustomBedrockBlock;
import org.geysermc.paletter.api.block.VersionedBedrockBlock;
import org.geysermc.paletter.api.component.BedrockComponents;
import org.geysermc.paletter.api.data.BedrockBlockData;
import org.geysermc.paletter.api.data.BlockDefinition;
import org.geysermc.paletter.api.palette.BlockPalette;
import org.geysermc.paletter.palette.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

final class RuntimeBlockPalette implements BlockPalette {
    private final BedrockVersion version;
    private final Map<BedrockBlockState, Entry> byState;
    private final Map<Integer, BedrockBlockState> byRuntimeId;
    private final Map<BedrockBlock, BedrockBlockState> defaultStates;
    private final Map<BedrockBlock, List<BedrockBlockState>> statesByBlock;

    RuntimeBlockPalette(BedrockVersion version, org.geysermc.paletter.palette.block.BlockPalette raw) {
        this.version = version;

        Map<BedrockBlockState, Entry> byState = new LinkedHashMap<>();
        Map<Integer, BedrockBlockState> byRuntimeId = new HashMap<>();
        Map<BedrockBlock, BedrockBlockState> defaultStates = new HashMap<>();
        Map<BedrockBlock, List<BedrockBlockState>> statesByBlock = new HashMap<>();

        for (Block block : raw.blocks()) {
            BedrockBlock identity = BedrockBlock.of(block.name());
            BedrockBlockState state = new BedrockBlockState(identity, new LinkedHashMap<>(block.states()));
            Optional<BedrockBlockAttributes> attributes = raw.attributesFor(block).map(RuntimeBlockPalette::toAttributes);

            byState.put(state, new Entry(block.networkId(), block.version(), BedrockComponents.builder().build(), attributes));
            byRuntimeId.put(block.networkId(), state);
            statesByBlock.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(state);
            defaultStates.putIfAbsent(identity, state);
        }

        this.byState = Map.copyOf(byState);
        this.byRuntimeId = Map.copyOf(byRuntimeId);
        this.defaultStates = Map.copyOf(defaultStates);
        this.statesByBlock = statesByBlock.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    private RuntimeBlockPalette(
            BedrockVersion version,
            Map<BedrockBlockState, Entry> byState,
            Map<Integer, BedrockBlockState> byRuntimeId,
            Map<BedrockBlock, BedrockBlockState> defaultStates,
            Map<BedrockBlock, List<BedrockBlockState>> statesByBlock
    ) {
        this.version = version;
        this.byState = byState;
        this.byRuntimeId = byRuntimeId;
        this.defaultStates = defaultStates;
        this.statesByBlock = statesByBlock;
    }

    // Copy-on-write, matching the design doc's "layer on top of a cached vanilla palette without
    // mutating it" - custom blocks get a single state (their explicitStates) and a synthetic
    // runtime id continuing past the highest vanilla one.
    RuntimeBlockPalette withCustoms(List<CustomBedrockBlock> customs) {
        if (customs.isEmpty()) {
            return this;
        }

        Map<BedrockBlockState, Entry> byState = new LinkedHashMap<>(this.byState);
        Map<Integer, BedrockBlockState> byRuntimeId = new HashMap<>(this.byRuntimeId);
        Map<BedrockBlock, BedrockBlockState> defaultStates = new HashMap<>(this.defaultStates);
        Map<BedrockBlock, List<BedrockBlockState>> statesByBlock = new HashMap<>(this.statesByBlock);

        int nextRuntimeId = byRuntimeId.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;

        for (CustomBedrockBlock custom : customs) {
            BedrockBlock identity = BedrockBlock.of(custom.identifier());
            BedrockBlockState state = new BedrockBlockState(identity, Map.copyOf(custom.explicitStates()));
            int runtimeId = nextRuntimeId++;

            byState.put(state, new Entry(runtimeId, 0, custom.components(), Optional.empty()));
            byRuntimeId.put(runtimeId, state);
            statesByBlock.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(state);
            defaultStates.putIfAbsent(identity, state);
        }

        return new RuntimeBlockPalette(
                version,
                Map.copyOf(byState),
                Map.copyOf(byRuntimeId),
                Map.copyOf(defaultStates),
                statesByBlock.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())))
        );
    }

    private BedrockBlockData dataFor(BedrockBlockState state, Entry entry, boolean isFallback) {
        BlockDefinition definition = new BlockDefinition(entry.runtimeId(), state.block().identifier());
        NbtMap blockStateTag = NbtMap.builder()
                .putString("name", state.block().identifier())
                .putCompound("states", NbtMap.fromMap(state.states()))
                .putInt("version", entry.version())
                .build();
        return new SimpleBedrockBlockData(state, definition, entry.components(), blockStateTag, entry.attributes(), isFallback);
    }

    private static BedrockBlockAttributes toAttributes(org.geysermc.paletter.palette.block.BlockAttributes raw) {
        return new BedrockBlockAttributes(
                raw.translationKey(),
                raw.isSolid(),
                raw.hardness(),
                raw.explosionResistance(),
                raw.friction(),
                raw.thickness(),
                raw.requiresCorrectToolForDrops(),
                raw.translucency(),
                raw.mapColor(),
                raw.tintMethod(),
                raw.lightEmission(),
                raw.lightDampening(),
                raw.burnOdds(),
                raw.flameOdds(),
                raw.canContainLiquidSource(),
                raw.liquidReactionOnTouch(),
                raw.collisionShape(),
                raw.outlineShape(),
                raw.visualShape(),
                raw.uiShape(),
                raw.liquidClipShape()
        );
    }

    @Override
    public Optional<BedrockBlockState> byRuntimeId(int runtimeId) {
        return Optional.ofNullable(byRuntimeId.get(runtimeId));
    }

    @Override
    public Optional<VersionedBedrockBlock> byIdentifier(String identifier) {
        return statesByBlock.keySet().stream()
                .filter(b -> b.identifier().equals(identifier))
                .findFirst()
                .map(this::get);
    }

    @Override
    public Collection<VersionedBedrockBlock> blocks() {
        return statesByBlock.keySet().stream().map(this::get).toList();
    }

    @Override
    public Collection<BedrockBlockState> allStates() {
        List<BedrockBlockState> states = new ArrayList<>();
        for (List<BedrockBlockState> blockStates : statesByBlock.values()) {
            states.addAll(blockStates);
        }
        return states;
    }

    @Override
    public BedrockBlockData dataFromState(BedrockBlockState state) {
        Entry entry = byState.get(state);
        if (entry != null) {
            return dataFor(state, entry, false);
        }

        BedrockBlockState fallback = defaultStates.get(state.block());
        if (fallback == null) {
            throw new IllegalArgumentException("Unknown block: " + state.block().identifier());
        }
        return dataFor(fallback, byState.get(fallback), true);
    }

    @Override
    public BedrockVersion version() {
        return version;
    }

    @Override
    public VersionedBedrockBlock get(BedrockBlock block) {
        BedrockBlockState defaultState = defaultStates.get(block);
        if (defaultState == null) {
            throw new IllegalArgumentException("Unknown block: " + block.identifier());
        }
        Optional<BedrockBlockAttributes> attributes = dataFromState(defaultState).attributes();
        return new SimpleVersionedBedrockBlock(version, block, defaultState, statesByBlock.getOrDefault(block, List.of()), attributes);
    }

    @Override
    public Builder toBuilder() {
        return new RuntimeBlockPaletteBuilder(this);
    }

    private record Entry(int runtimeId, int version, BedrockComponents components, Optional<BedrockBlockAttributes> attributes) {
    }
}
