package dev.opencollab.paletter.creative;

/**
 * {@link CreativePlacement#DEFAULT} and {@link CreativePlacement#FIRST}: positions the menu category alone decides. Not
 * public, there are only the constants.
 */
final class DefaultPlacement implements CreativePlacement {
    private final boolean first;

    DefaultPlacement(boolean first) {
        this.first = first;
    }

    @Override
    public String toString() {
        return first ? "CreativePlacement.FIRST" : "CreativePlacement.DEFAULT";
    }
}
