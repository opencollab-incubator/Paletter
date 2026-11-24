package dev.opencollab.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import dev.opencollab.paletter.block.component.OnPlayerPlacing;
import dev.opencollab.paletter.block.component.RawBlockComponent;
import dev.opencollab.paletter.block.component.ScriptHooks;
import dev.opencollab.paletter.block.component.WireOnlyBlockComponent;
import dev.opencollab.paletter.item.component.FrameCount;
import dev.opencollab.paletter.item.component.MiningSpeed;
import dev.opencollab.paletter.item.component.RawItemComponent;
import dev.opencollab.paletter.item.component.RenderOffsets;
import dev.opencollab.paletter.item.component.UseOnHook;
import dev.opencollab.paletter.item.component.WireOnlyItemComponent;

import java.util.List;

/**
 * Encoders of the hand-written {@link WireOnlyItemComponent}s and {@link WireOnlyBlockComponent}s, dispatched from the generated {@code ItemWire}. Each has a
 * protocol window ({@code *_FROM}: the first protocol that carries the field). {@code mining_speed} and {@code frame_count} are
 * leaves of the {@code item_properties} BDS sends for every custom item, in every spec capture: their window starts at the
 * oldest ({@link ComponentIndex#OLDEST_CAPTURED_PROTOCOL}), which like every oldest window also holds below it.
 * {@code render_offsets} and {@code on_player_placing} are not sent by BDS at all
 * (it rejects them: only clients of Geyser's own packs read them), so no capture says from when a client takes them; they
 * are assumed for every bundled version. Not API.
 */
@SuppressWarnings("deprecation")
public final class WireOnlyWire {
    /** Not sent by BDS: no capture to date it from, assumed. */
    static final int RENDER_OFFSETS_FROM = 0;
    /** The oldest spec capture already has it in {@code item_properties} ({@code WireOnlyWindowsTest} checks every envelope window). */
    public static final int MINING_SPEED_FROM = ComponentIndex.OLDEST_CAPTURED_PROTOCOL;
    /** The oldest spec capture already has it in {@code item_properties} ({@code WireOnlyWindowsTest} checks every envelope window). */
    public static final int FRAME_COUNT_FROM = ComponentIndex.OLDEST_CAPTURED_PROTOCOL;
    /** Not sent by BDS: no capture to date it from, assumed. */
    static final int ON_PLAYER_PLACING_FROM = 0;
    /**
     * BDS sends it for script components, but no capture has one yet: assumed for every bundled version, unverified.
     * TODO: move after the recapture, from the first capture that has the {@code minecraft:custom_components} compound.
     */
    static final int SCRIPT_HOOKS_FROM = 0;
    /**
     * Every spec capture already has the {@code minecraft:publisher_on_use_on.autoSucceedOnClient} leaf, through the placers
     * ({@code WireOnlyWindowsTest} checks it), so this starts at the oldest.
     */
    public static final int USE_ON_HOOK_FROM = ComponentIndex.OLDEST_CAPTURED_PROTOCOL;

    private WireOnlyWire() {
    }

    public static void write(@NonNull WireOnlyItemComponent component, @NonNull WireContext ctx, @NonNull WireNode out) {
        switch (component) {
            case RawItemComponent c -> out.merge(c.key(), c.value());
            case RenderOffsets c -> {
                if (check(ctx, RENDER_OFFSETS_FROM, c.name())) {
                    renderOffsets(c, out);
                }
            }
            case MiningSpeed c -> {
                if (check(ctx, MINING_SPEED_FROM, c.name())) {
                    out.put("item_properties.mining_speed", c.value());
                }
            }
            case FrameCount c -> {
                if (check(ctx, FRAME_COUNT_FROM, c.name())) {
                    out.put("item_properties.frame_count", c.value());
                }
            }
            case UseOnHook c -> {
                if (check(ctx, USE_ON_HOOK_FROM, c.name())) {
                    out.put("minecraft:publisher_on_use_on.autoSucceedOnClient", Wire.bool(c.autoSucceedOnClient()));
                }
            }
            default -> throw new IllegalArgumentException(component.toString());
        }
    }

    /**
     * Dispatched from the generated {@code BlockWire}.
     */
    public static void write(@NonNull WireOnlyBlockComponent component, @NonNull WireContext ctx, @NonNull WireNode out) {
        switch (component) {
            case RawBlockComponent c -> out.merge(c.key(), c.value());
            case OnPlayerPlacing c -> {
                if (check(ctx, ON_PLAYER_PLACING_FROM, c.name())) {
                    out.remove(OnPlayerPlacing.NAME);
                    out.child(OnPlayerPlacing.NAME).put("triggerType", c.triggerType());
                }
            }
            case ScriptHooks c -> {
                if (check(ctx, SCRIPT_HOOKS_FROM, c.name())) {
                    WireNode hooks = out.child(ScriptHooks.NAME);
                    hooks.put("hasPlayerInteract", Wire.bool(c.playerInteract()));
                    hooks.put("hasPlayerPlacing", Wire.bool(c.playerPlacing()));
                    hooks.put("isV1Component", (byte) 0);
                }
            }
            default -> throw new IllegalArgumentException(component.toString());
        }
    }

    /**
     * The components in the order they are written: the raw ones last, so that they merge over what the others wrote
     * ({@link RawItemComponent}, {@link RawBlockComponent}).
     */
    public static <C> @NonNull List<C> rawLast(@NonNull List<C> components) {
        if (components.stream().noneMatch(WireOnlyWire::raw)) {
            return components;
        }
        List<C> ordered = new java.util.ArrayList<>(components.stream().filter(c -> !raw(c)).toList());
        components.stream().filter(WireOnlyWire::raw).forEach(ordered::add);
        return ordered;
    }

    private static boolean raw(Object component) {
        return component instanceof RawItemComponent || component instanceof RawBlockComponent;
    }

    private static boolean check(WireContext ctx, int from, String name) {
        if (ctx.protocol() < from) {
            ctx.drop(name, "is not sent");
            return false;
        }
        return true;
    }

    // The shape Geyser writes: main_hand/off_hand -> first_person/third_person -> position/rotation/scale (float lists);
    // empty parts are left out.
    private static void renderOffsets(RenderOffsets c, WireNode out) {
        out.remove("minecraft:render_offsets");
        out.remove("render_offsets");
        if (c.preset() != null) {
            // A bare string, not a minecraft: component; only Geyser sends it
            out.put("render_offsets", c.preset().value());
            if (c.mainHand() == null && c.offHand() == null) {
                return;
            }
        }
        WireNode node = out.child("minecraft:render_offsets");
        hand(node, "main_hand", c.mainHand());
        hand(node, "off_hand", c.offHand());
    }

    private static void hand(WireNode node, String key, RenderOffsets.@Nullable Hand hand) {
        if (hand == null) {
            return;
        }
        WireNode first = offset(hand.firstPerson());
        WireNode third = offset(hand.thirdPerson());
        if (first == null && third == null) {
            return;
        }
        WireNode h = node.child(key);
        if (first != null) {
            h.put("first_person", first);
        }
        if (third != null) {
            h.put("third_person", third);
        }
    }

    private static @Nullable WireNode offset(RenderOffsets.@Nullable Offset offset) {
        if (offset == null || offset.position() == null && offset.rotation() == null && offset.scale() == null) {
            return null;
        }
        WireNode n = new WireNode();
        vec(n, "position", offset.position());
        vec(n, "rotation", offset.rotation());
        vec(n, "scale", offset.scale());
        return n;
    }

    private static void vec(WireNode n, String key, RenderOffsets.@Nullable Vec3 v) {
        if (v != null) {
            n.put(key, Wire.floats(List.of(v.x(), v.y(), v.z())));
        }
    }
}
