package org.geysermc.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.block.component.OnPlayerPlacing;
import org.geysermc.paletter.block.component.WireOnlyBlockComponent;
import org.geysermc.paletter.item.component.FrameCount;
import org.geysermc.paletter.item.component.MiningSpeed;
import org.geysermc.paletter.item.component.RenderOffsets;
import org.geysermc.paletter.item.component.WireOnlyItemComponent;

import java.util.List;

/**
 * Encoders of the hand-written {@link WireOnlyItemComponent}s and {@link WireOnlyBlockComponent}s, dispatched from the generated {@code ItemWire}. Each has a
 * protocol window ({@code *_FROM}: the first protocol that carries the field). {@code mining_speed} and {@code frame_count} are
 * leaves of the {@code item_properties} BDS sends for every custom item, in every spec capture (the oldest is protocol
 * 1001, 26.30): their window starts there. {@code render_offsets} and {@code on_player_placing} are not sent by BDS at all
 * (it rejects them: only clients of Geyser's own packs read them), so no capture says from when a client takes them; they
 * are assumed for every bundled version. Not API.
 */
@SuppressWarnings("deprecation")
public final class WireOnlyWire {
    /** Not sent by BDS: no capture to date it from, assumed. */
    static final int RENDER_OFFSETS_FROM = 0;
    /** The oldest spec capture (BDS 1.26.36.1) already has it in {@code item_properties}. */
    public static final int MINING_SPEED_FROM = 1001;
    /** The oldest spec capture (BDS 1.26.36.1) already has it in {@code item_properties}. */
    public static final int FRAME_COUNT_FROM = 1001;
    /** Not sent by BDS: no capture to date it from, assumed. */
    static final int ON_PLAYER_PLACING_FROM = 0;

    private WireOnlyWire() {
    }

    public static void write(@NonNull WireOnlyItemComponent component, @NonNull WireContext ctx, @NonNull WireNode out) {
        switch (component) {
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
        }
    }

    /**
     * Dispatched from the generated {@code BlockWire}.
     */
    public static void write(@NonNull WireOnlyBlockComponent component, @NonNull WireContext ctx, @NonNull WireNode out) {
        switch (component) {
            case OnPlayerPlacing c -> {
                if (check(ctx, ON_PLAYER_PLACING_FROM, c.name())) {
                    out.remove(OnPlayerPlacing.NAME);
                    out.child(OnPlayerPlacing.NAME).put("triggerType", c.triggerType());
                }
            }
        }
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
