package org.geysermc.paletter.block.component;

/**
 * A box in pixels (1/16 block), relative to the bottom center of the block: Bedrock's add-on convention, where the
 * full block is origin {@code (-8, 0, -8)}, size {@code (16, 16, 16)}.
 *
 * @param originX the minimum x, -8 to 8
 * @param originY the minimum y, 0 to 16
 * @param originZ the minimum z, -8 to 8
 * @param sizeX the size along x
 * @param sizeY the size along y
 * @param sizeZ the size along z
 * @since 2.0.0
 */
public record Box(float originX, float originY, float originZ, float sizeX, float sizeY, float sizeZ) {
    public static final Box FULL = new Box(-8, 0, -8, 16, 16, 16);
    public static final Box EMPTY = new Box(0, 0, 0, 0, 0, 0);

    public boolean isEmpty() {
        return sizeX == 0 || sizeY == 0 || sizeZ == 0;
    }
}
