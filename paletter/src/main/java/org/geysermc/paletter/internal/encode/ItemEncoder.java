package org.geysermc.paletter.internal.encode;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.internal.wire.item.AllowOffHand;
import org.geysermc.paletter.internal.wire.item.BlockPlacer;
import org.geysermc.paletter.internal.wire.item.CanDestroyInCreative;
import org.geysermc.paletter.internal.wire.item.Compostable;
import org.geysermc.paletter.internal.wire.item.Damage;
import org.geysermc.paletter.internal.wire.item.DisplayName;
import org.geysermc.paletter.internal.wire.item.Dyeable;
import org.geysermc.paletter.internal.wire.item.FireResistant;
import org.geysermc.paletter.internal.wire.item.Fuel;
import org.geysermc.paletter.internal.wire.item.Glint;
import org.geysermc.paletter.internal.wire.item.HandEquipped;
import org.geysermc.paletter.internal.wire.item.ItemProperties;
import org.geysermc.paletter.internal.wire.item.LiquidClipped;
import org.geysermc.paletter.internal.wire.item.MaxStackSize;
import org.geysermc.paletter.internal.wire.item.Projectile;
import org.geysermc.paletter.internal.wire.item.Rarity;
import org.geysermc.paletter.internal.wire.item.ShouldDespawn;
import org.geysermc.paletter.internal.wire.item.StackedByData;
import org.geysermc.paletter.internal.wire.item.SwingDuration;
import org.geysermc.paletter.internal.wire.item.Tags;
import org.geysermc.paletter.internal.wire.item.ThrowableComponent;
import org.geysermc.paletter.item.ItemComponents;
import org.geysermc.paletter.item.component.BlockDescriptor;
import org.geysermc.paletter.item.component.Color;
import org.geysermc.paletter.item.component.Cooldown;
import org.geysermc.paletter.item.component.CooldownType;
import org.geysermc.paletter.item.component.Digger;
import org.geysermc.paletter.item.component.Durability;
import org.geysermc.paletter.item.component.Enchantable;
import org.geysermc.paletter.item.component.EntityPlacer;
import org.geysermc.paletter.item.component.EquipmentSlot;
import org.geysermc.paletter.item.component.Food;
import org.geysermc.paletter.item.component.KineticWeapon;
import org.geysermc.paletter.item.component.PiercingWeapon;
import org.geysermc.paletter.item.component.Reach;
import org.geysermc.paletter.item.component.RenderOffsets;
import org.geysermc.paletter.item.component.Repairable;
import org.geysermc.paletter.item.component.Shooter;
import org.geysermc.paletter.item.component.Throwing;
import org.geysermc.paletter.item.component.UseAnimation;
import org.geysermc.paletter.item.component.UseModifiers;
import org.geysermc.paletter.item.component.Wearable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Encodes semantic {@link ItemComponents} into the item component data of one Bedrock version. The only place that knows
 * the item component wire format; see {@code codegen/DEVIATIONS.md} for where it differs from what Geyser used to send.
 */
public final class ItemEncoder {
    private static final String ITEM_TAGS = "item_tags";
    private static final String ITEM_PROPERTIES = "item_properties";

    private ItemEncoder() {
    }

    /**
     * @param identifier the item identifier
     * @param runtimeId the item runtime id
     * @param creative the creative placement, written into the item properties; null for none
     * @return the full component data of a data driven item definition, as sent in the item component packet
     */
    public static @NonNull NbtMap componentData(@NonNull ItemComponents components, @NonNull String identifier, int runtimeId,
                                                @Nullable CreativePlacement creative, @NonNull EncodeContext context) {
        NbtMapBuilder out = NbtMap.builder();
        NbtMapBuilder properties = NbtMap.builder();
        // Raw values first: semantic values encoding to the same names replace them
        out.putAll(components.raw());
        properties.putAll(components.rawProperties());

        ItemProperties.Builder typedProperties = ItemProperties.builder();
        new Encoding(components, identifier, context, out, typedProperties).encode();
        if (creative != null) {
            typedProperties.creativeCategory(creative.category().itemPropertyId());
            if (creative.group() != null) {
                // Like BDS: the group as add-on JSON names it, always namespaced (vanilla groups are minecraft:)
                String group = creative.group();
                typedProperties.creativeGroup(group.indexOf(':') < 0 ? "minecraft:" + group : group);
            }
        }

        if (!components.tags().isEmpty()) {
            // Vanilla and BDS send tags twice: as the bare item_tags list and as the minecraft:tags component
            List<String> tags = new ArrayList<>(components.tags());
            out.putList(ITEM_TAGS, NbtType.STRING, tags);
            context.put(out, identifier, Tags.TYPE, () -> Tags.of(tags));
        }
        if (context.supports(identifier, ItemProperties.TYPE)) {
            properties.putAll(typedProperties.build().toNbt());
        }
        out.putCompound(ITEM_PROPERTIES, properties.build());

        return NbtMap.builder()
            .putString("name", identifier)
            .putInt("id", runtimeId)
            .putCompound("components", out.build())
            .build();
    }

    /**
     * @return the {@code item_properties.use_animation} id of an animation
     */
    static int useAnimationId(UseAnimation animation) {
        // Eat (1) and spear (6) captured from BDS 1.26.63.1; the others as Geyser has sent them since 1.21.94
        return switch (animation) {
            case NONE -> 0;
            case EAT -> 1;
            case DRINK -> 2;
            case BLOCK -> 3;
            case BOW -> 4;
            case SPEAR -> 6;
            case CROSSBOW -> 9;
            case SPYGLASS -> 10;
            case BRUSH -> 12;
        };
    }

    static String slot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "slot.armor.head";
            case CHEST -> "slot.armor.chest";
            case LEGS -> "slot.armor.legs";
            case FEET -> "slot.armor.feet";
            case BODY -> "slot.armor.body";
            case OFFHAND -> "slot.weapon.offhand";
        };
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    // minecraft:render_offsets is deprecated: no longer an add-on component, only Geyser sends it
    @SuppressWarnings("deprecation")
    private record Encoding(ItemComponents c, String id, EncodeContext context, NbtMapBuilder out, ItemProperties.Builder properties) {

        void encode() {
            if (c.icon() != null) {
                properties.icon(new ItemProperties.Icon(c.icon()));
            }
            if (c.displayName() != null) {
                context.put(out, id, DisplayName.TYPE, () -> DisplayName.of(c.displayName()));
            }
            if (c.maxStackSize() != null) {
                int size = c.maxStackSize();
                // Also sent as a component: without it minecraft:wearable resets the stack size to one
                properties.maxStackSize(size);
                context.put(out, id, MaxStackSize.TYPE, () -> MaxStackSize.of((byte) size));
            }
            // Flags BDS sends both as an item property and as a component
            if (c.allowOffHand() != null) {
                properties.allowOffHand(c.allowOffHand());
                context.put(out, id, AllowOffHand.TYPE, () -> AllowOffHand.of(c.allowOffHand()));
            }
            if (c.handEquipped() != null) {
                properties.handEquipped(c.handEquipped());
                context.put(out, id, HandEquipped.TYPE, () -> HandEquipped.of(c.handEquipped()));
            }
            if (c.glint() != null) {
                properties.foil(c.glint());
                context.put(out, id, Glint.TYPE, () -> Glint.of(c.glint()));
            }
            if (c.canDestroyInCreative() != null) {
                properties.canDestroyInCreative(c.canDestroyInCreative());
                context.put(out, id, CanDestroyInCreative.TYPE, () -> CanDestroyInCreative.of(c.canDestroyInCreative()));
            }
            if (c.stackedByData() != null) {
                properties.stackedByData(c.stackedByData());
                context.put(out, id, StackedByData.TYPE, () -> StackedByData.of(c.stackedByData()));
            }
            if (c.liquidClipped() != null) {
                properties.liquidClipped(c.liquidClipped());
                context.put(out, id, LiquidClipped.TYPE, () -> LiquidClipped.of(c.liquidClipped()));
            }
            if (c.shouldDespawn() != null) {
                properties.shouldDespawn(c.shouldDespawn());
                context.put(out, id, ShouldDespawn.TYPE, () -> ShouldDespawn.of(c.shouldDespawn()));
            }
            if (c.fireResistant() != null) {
                context.put(out, id, FireResistant.TYPE, () -> FireResistant.of(c.fireResistant()));
            }
            if (c.durability() != null) {
                Durability durability = c.durability();
                // Percent; 100/100 (always damaged) is the BDS default. Geyser sent 1/1, a 1 % chance
                context.put(out, id, org.geysermc.paletter.internal.wire.item.Durability.TYPE,
                    () -> org.geysermc.paletter.internal.wire.item.Durability.builder()
                        .damageChance(new org.geysermc.paletter.internal.wire.item.Durability.DamageChance(
                            durability.maxDamageChance(), durability.minDamageChance()))
                        .maxDurability(durability.maxDurability())
                        .build());
            }
            if (c.attackDamage() != null) {
                int damage = c.attackDamage();
                properties.damage(damage);
                // A short: Geyser's byte overflowed above 127
                context.put(out, id, Damage.TYPE, () -> Damage.of((short) damage));
            }
            if (c.enchantable() != null) {
                Enchantable enchantable = c.enchantable();
                properties.enchantableSlot(enchantable.slot()).enchantableValue(enchantable.value());
                context.put(out, id, org.geysermc.paletter.internal.wire.item.Enchantable.TYPE,
                    () -> new org.geysermc.paletter.internal.wire.item.Enchantable(enchantable.slot(), (byte) enchantable.value()));
            }
            if (c.dyeable() != null) {
                Color color = c.dyeable();
                // A list of three ints, not an int array
                context.put(out, id, Dyeable.TYPE, () -> Dyeable.of(List.of(color.red(), color.green(), color.blue())));
            }
            if (c.kineticWeapon() != null) {
                kineticWeapon(c.kineticWeapon());
            }
            if (c.piercingWeapon() != null) {
                PiercingWeapon weapon = c.piercingWeapon();
                context.put(out, id, org.geysermc.paletter.internal.wire.item.PiercingWeapon.TYPE,
                    () -> new org.geysermc.paletter.internal.wire.item.PiercingWeapon(
                        weapon.creativeReach() == null ? null : piercingRange(weapon.creativeReach()),
                        weapon.hitboxMargin(), piercingRange(weapon.reach())));
            }
            if (c.miningSpeed() != null) {
                properties.miningSpeed(c.miningSpeed());
            }
            if (c.frameCount() != null) {
                properties.frameCount(c.frameCount());
            }
            if (c.armorRenderOffsets() != null) {
                String preset = switch (c.armorRenderOffsets()) {
                    case HEAD -> "helmets";
                    case CHEST -> "chestplates";
                    case LEGS -> "leggings";
                    case FEET -> "boots";
                    default -> null;
                };
                if (preset != null) {
                    // A bare string, not a minecraft: component; only Geyser sends it
                    out.putString("render_offsets", preset);
                }
            }
            if (c.swingDuration() != null) {
                context.put(out, id, SwingDuration.TYPE, () -> SwingDuration.of(c.swingDuration()));
            }
            if (c.food() != null) {
                Food food = c.food();
                context.put(out, id, org.geysermc.paletter.internal.wire.item.Food.TYPE,
                    () -> new org.geysermc.paletter.internal.wire.item.Food(food.canAlwaysEat(), food.nutrition(), food.saturationModifier(),
                        org.geysermc.paletter.internal.wire.item.Food.ItemDescriptor.of(food.usingConvertsTo())));
            }
            if (c.useAnimation() != null) {
                UseAnimation animation = c.useAnimation();
                // The component names the animation, the item property numbers it
                properties.useAnimation(useAnimationId(animation));
                context.put(out, id, org.geysermc.paletter.internal.wire.item.UseAnimation.TYPE,
                    () -> org.geysermc.paletter.internal.wire.item.UseAnimation.of(lower(animation)));
            }
            if (c.useModifiers() != null) {
                UseModifiers modifiers = c.useModifiers();
                // Seconds in the component, ticks in the item property
                properties.useDuration((int) (modifiers.useDuration() * 20));
                context.put(out, id, org.geysermc.paletter.internal.wire.item.UseModifiers.TYPE,
                    () -> org.geysermc.paletter.internal.wire.item.UseModifiers.builder()
                        .useDuration(modifiers.useDuration())
                        .movementModifier(modifiers.movementModifier())
                        .build());
            }
            if (c.cooldown() != null) {
                Cooldown cooldown = c.cooldown();
                context.put(out, id, org.geysermc.paletter.internal.wire.item.Cooldown.TYPE,
                    () -> new org.geysermc.paletter.internal.wire.item.Cooldown(cooldown.category(), cooldown.seconds(),
                        cooldown.type() == CooldownType.USE ? null : lower(cooldown.type())));
            }
            if (c.blockPlacer() != null) {
                String block = c.blockPlacer();
                boolean icon = Boolean.TRUE.equals(c.blockPlacerUsesBlockAsIcon());
                context.put(out, id, BlockPlacer.TYPE, () -> BlockPlacer.builder().block(block).canUseBlockAsIcon(icon).useOn(List.of()).build());
            }
            if (c.shooter() != null) {
                shooter(c.shooter());
            }
            String projectile = c.projectile();
            if (c.throwable() != null) {
                Throwing throwable = c.throwable();
                context.put(out, id, ThrowableComponent.TYPE, () -> ThrowableComponent.builder()
                    .doSwingAnimation(throwable.swingAnimation())
                    .launchPowerScale(throwable.launchPowerScale())
                    .maxLaunchPower(throwable.maxLaunchPower())
                    .minDrawDuration(throwable.minDrawDuration())
                    .maxDrawDuration(throwable.maxDrawDuration())
                    .scalePowerByDrawDuration(throwable.scalePowerByDrawDuration())
                    .build());
                if (projectile == null) {
                    // Without a projectile the client does not play the swing animation
                    projectile = "minecraft:snowball";
                }
            }
            if (projectile != null) {
                String entity = projectile;
                context.put(out, id, Projectile.TYPE, () -> Projectile.builder().projectileEntity(entity).build());
            }
            if (c.entityPlacer() != null) {
                EntityPlacer placer = c.entityPlacer();
                context.put(out, id, org.geysermc.paletter.internal.wire.item.EntityPlacer.TYPE,
                    () -> new org.geysermc.paletter.internal.wire.item.EntityPlacer(
                        placer.dispenseOn().stream().map(Encoding::entityPlacerBlock).toList(), placer.entity(),
                        placer.useOn().stream().map(Encoding::entityPlacerBlock).toList()));
            }
            if (c.compostable() != null) {
                int chance = c.compostable();
                // A byte, not an int
                context.put(out, id, Compostable.TYPE, () -> Compostable.of((byte) chance));
            }
            if (c.fuel() != null) {
                context.put(out, id, Fuel.TYPE, () -> Fuel.of(c.fuel()));
            }
            if (c.repairable() != null) {
                repairable(c.repairable());
            }
            if (c.digger() != null) {
                digger(c.digger());
            }
            if (c.rarity() != null) {
                context.put(out, id, Rarity.TYPE, () -> Rarity.of(lower(c.rarity())));
            }
            if (c.wearable() != null) {
                Wearable wearable = c.wearable();
                context.put(out, id, org.geysermc.paletter.internal.wire.item.Wearable.TYPE,
                    () -> org.geysermc.paletter.internal.wire.item.Wearable.builder().protection(wearable.protection()).slot(slot(wearable.slot())).build());
            }
            if (c.renderOffsets() != null) {
                renderOffsets(c.renderOffsets());
            }
        }

        private void kineticWeapon(KineticWeapon weapon) {
            // Nested in itself, like vanilla spears and BDS send it; delay is a short inside
            context.put(out, id, org.geysermc.paletter.internal.wire.item.KineticWeapon.TYPE,
                () -> org.geysermc.paletter.internal.wire.item.KineticWeapon.of(
                    org.geysermc.paletter.internal.wire.item.KineticWeapon.Weapon.builder()
                        .delay((short) weapon.delayTicks())
                        .reach(kineticRange(weapon.reach()))
                        .creativeReach(weapon.creativeReach() == null ? null : kineticRange(weapon.creativeReach()))
                        .hitboxMargin(weapon.hitboxMargin())
                        .damageMultiplier(weapon.damageMultiplier())
                        .damageModifier(weapon.damageModifier())
                        .damageConditions(conditions(weapon.damageConditions()))
                        .knockbackConditions(conditions(weapon.knockbackConditions()))
                        .dismountConditions(conditions(weapon.dismountConditions()))
                        .build()));
        }

        private static org.geysermc.paletter.internal.wire.item.KineticWeapon.Range kineticRange(Reach reach) {
            return new org.geysermc.paletter.internal.wire.item.KineticWeapon.Range(reach.max(), reach.min());
        }

        private static org.geysermc.paletter.internal.wire.item.PiercingWeapon.Range piercingRange(Reach reach) {
            return new org.geysermc.paletter.internal.wire.item.PiercingWeapon.Range(reach.max(), reach.min());
        }

        private static org.geysermc.paletter.internal.wire.item.KineticWeapon.@Nullable Conditions conditions(
            KineticWeapon.@Nullable Conditions conditions) {
            if (conditions == null) {
                return null;
            }
            return new org.geysermc.paletter.internal.wire.item.KineticWeapon.Conditions((short) conditions.maxDurationTicks(),
                conditions.minRelativeSpeed(), conditions.minSpeed());
        }

        private void shooter(Shooter shooter) {
            List<org.geysermc.paletter.internal.wire.item.Shooter.Ammunition> ammunition = new ArrayList<>();
            for (Shooter.Ammunition entry : shooter.ammunition()) {
                ammunition.add(new org.geysermc.paletter.internal.wire.item.Shooter.Ammunition(
                    org.geysermc.paletter.internal.wire.item.Shooter.ItemDescriptor.of(entry.item()),
                    entry.searchInventory(), entry.useInCreative(), entry.useOffhand()));
            }
            context.put(out, id, org.geysermc.paletter.internal.wire.item.Shooter.TYPE,
                () -> new org.geysermc.paletter.internal.wire.item.Shooter(ammunition, shooter.chargeOnDraw(), shooter.maxDrawDuration(),
                    shooter.scalePowerByDrawDuration()));
        }

        private void repairable(Repairable repairable) {
            List<org.geysermc.paletter.internal.wire.item.Repairable.RepairItem> items = new ArrayList<>();
            for (Repairable.Entry entry : repairable.entries()) {
                List<org.geysermc.paletter.internal.wire.item.Repairable.ItemDescriptor> descriptors = new ArrayList<>();
                for (String item : entry.items()) {
                    descriptors.add(new org.geysermc.paletter.internal.wire.item.Repairable.ItemDescriptor(item, null));
                }
                if (entry.tags() != null) {
                    descriptors.add(new org.geysermc.paletter.internal.wire.item.Repairable.ItemDescriptor(null, entry.tags()));
                }
                // A float for numbers, a string for Molang: both are on the wire
                Object amount = entry.amount() != null ? entry.amount() : entry.amountExpression();
                items.add(new org.geysermc.paletter.internal.wire.item.Repairable.RepairItem(descriptors, amount));
            }
            context.put(out, id, org.geysermc.paletter.internal.wire.item.Repairable.TYPE,
                () -> org.geysermc.paletter.internal.wire.item.Repairable.of(items));
        }

        private void digger(Digger digger) {
            List<org.geysermc.paletter.internal.wire.item.Digger.DestroySpeed> speeds = new ArrayList<>();
            for (Digger.DestroySpeed speed : digger.destroySpeeds()) {
                BlockDescriptor blocks = speed.blocks();
                // Always the full {name, states, tags} triple, name "" for tag queries
                speeds.add(new org.geysermc.paletter.internal.wire.item.Digger.DestroySpeed(
                    new org.geysermc.paletter.internal.wire.item.Digger.BlockDescriptor(
                        blocks.block() == null ? "" : blocks.block(), NbtMap.EMPTY, blocks.tags() == null ? "" : blocks.tags()),
                    speed.speed()));
            }
            context.put(out, id, org.geysermc.paletter.internal.wire.item.Digger.TYPE,
                () -> new org.geysermc.paletter.internal.wire.item.Digger(speeds, digger.useEfficiency()));
        }

        private static org.geysermc.paletter.internal.wire.item.EntityPlacer.BlockDescriptor entityPlacerBlock(BlockDescriptor blocks) {
            return new org.geysermc.paletter.internal.wire.item.EntityPlacer.BlockDescriptor(
                blocks.block() == null ? "" : blocks.block(), NbtMap.EMPTY, blocks.tags() == null ? "" : blocks.tags());
        }

        private void renderOffsets(RenderOffsets offsets) {
            context.put(out, id, org.geysermc.paletter.internal.wire.item.RenderOffsets.TYPE,
                () -> new org.geysermc.paletter.internal.wire.item.RenderOffsets(hand(offsets.mainHand()), hand(offsets.offHand())));
        }

        private static org.geysermc.paletter.internal.wire.item.RenderOffsets.Hand hand(RenderOffsets.Hand hand) {
            return new org.geysermc.paletter.internal.wire.item.RenderOffsets.Hand(offset(hand.firstPerson()), offset(hand.thirdPerson()));
        }

        private static org.geysermc.paletter.internal.wire.item.RenderOffsets.Offset offset(RenderOffsets.Transform transform) {
            return new org.geysermc.paletter.internal.wire.item.RenderOffsets.Offset(
                List.of(transform.positionX(), transform.positionY(), transform.positionZ()),
                List.of(transform.rotationX(), transform.rotationY(), transform.rotationZ()),
                List.of(transform.scaleX(), transform.scaleY(), transform.scaleZ()));
        }
    }

    /**
     * Reads item component data back into {@link ItemComponents}, e.g. a vanilla item's so a layer can modify it. Tags
     * (from {@code item_tags} and {@code minecraft:tags}) become semantic; everything else is kept as raw NBT, so it is
     * re-encoded unchanged.
     *
     * @param componentData data as returned by {@link #componentData}, or just its {@code components} compound
     */
    public static @NonNull ItemComponents decode(@NonNull NbtMap componentData) {
        NbtMap componentsNbt = componentData.containsKey("components") ? componentData.getCompound("components") : componentData;
        ItemComponents.Builder builder = ItemComponents.builder();
        for (Map.Entry<String, Object> entry : componentsNbt.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key.equals(ITEM_PROPERTIES) && value instanceof NbtMap properties) {
                properties.forEach(builder::rawProperty);
            } else if (key.equals(ITEM_TAGS) && value instanceof List<?> tags) {
                tags.forEach(tag -> builder.tag((String) tag));
            } else if (key.equals(Tags.TYPE.name()) && value instanceof NbtMap map) {
                Tags.fromNbt(map).tags().forEach(builder::tag);
            } else if (value instanceof NbtMap map) {
                builder.raw(key, map);
            }
        }
        return builder.build();
    }
}
