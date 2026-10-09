package org.geysermc.paletter.api.block;

import java.util.List;

/**
 * Essentially a minor of `blocks.json` from the CB data repo
 * @param translationKey translation key for the name
 * @param isSolid if the block is solid or not (affects redstone)
 * @param hardness the hardness value of the block (TODO this is block breaking speed?)
 * @param explosionResistance how resistant the block is to explosions
 * @param friction the friction of the block, used in vanilla for ice
 * @param thickness (?)
 * @param requiresCorrectToolForDrops checks the tool for drops
 * @param translucency (?)
 * @param mapColor the color of the block on a map
 * @param tintMethod (?)
 * @param lightEmission how much light the block emits
 * @param lightDampening (?)
 * @param burnOdds how likely the block is to burn (?)
 * @param flameOdds (?)
 * @param canContainLiquidSource if the block can be waterlogged
 * @param liquidReactionOnTouch what happens to the block when contacting liquid
 * @param collisionShape the block collision shape
 * @param outlineShape the block outline shape
 * @param visualShape the block visual shape
 * @param uiShape the block UI shape (huh?)
 * @param liquidClipShape (?)
 */
public record BedrockBlockAttributes(
        String translationKey,
        boolean isSolid,
        double hardness,
        double explosionResistance,
        double friction,
        double thickness,
        boolean requiresCorrectToolForDrops,
        double translucency,
        String mapColor,
        String tintMethod,
        int lightEmission,
        int lightDampening,
        int burnOdds,
        int flameOdds,
        boolean canContainLiquidSource,
        String liquidReactionOnTouch,
        List<List<Double>> collisionShape,
        List<Double> outlineShape,
        List<Double> visualShape,
        List<Double> uiShape,
        List<Double> liquidClipShape
) {
}
