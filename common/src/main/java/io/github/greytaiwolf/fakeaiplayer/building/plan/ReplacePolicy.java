package io.github.greytaiwolf.fakeaiplayer.building.plan;

/**
 * Safety policy evaluated before a planned cell may mutate the world.
 */
public enum ReplacePolicy {
    /** The destination must already be empty. */
    REQUIRE_EMPTY,
    /** Air, fluid, plants, snow and other vanilla-replaceable cells may be replaced. */
    REPLACE_REPLACEABLE,
    /** Terrain classified as natural by the site policy may be replaced. */
    REPLACE_NATURAL,
    /** Clearing is explicitly part of the confirmed design. */
    CLEAR_AUTHORIZED,
    /** The cell is evidence only and must not be changed. */
    PRESERVE_EXISTING,
    /** Operator-only overwrite after authorization and conflict reporting. */
    FORCE_AUTHORIZED
}
