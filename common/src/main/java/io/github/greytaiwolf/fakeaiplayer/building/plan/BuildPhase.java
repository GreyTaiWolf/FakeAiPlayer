package io.github.greytaiwolf.fakeaiplayer.building.plan;

/**
 * Stable, semantic construction phases used by planning, preview filtering and execution.
 *
 * <p>The enum order is dependency order: a placement may depend on the same phase or an
 * earlier phase, never a later one.</p>
 */
public enum BuildPhase {
    SITE_SURVEY,
    SITE_PREPARATION,
    FOUNDATION,
    /** Permanent stairs/decks required to execute later high work without disposable pillars. */
    CONSTRUCTION_ACCESS,
    FRAME,
    FLOORS_AND_STAIRS,
    WALLS_AND_OPENINGS,
    ROOF,
    EXTERIOR_FEATURES,
    INTERIOR_DETAILS,
    LIGHTING,
    CLEANUP,
    VERIFY
}
