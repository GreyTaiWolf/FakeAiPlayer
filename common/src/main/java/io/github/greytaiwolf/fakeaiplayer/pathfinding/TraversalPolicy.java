package io.github.greytaiwolf.fakeaiplayer.pathfinding;

/**
 * Names the environmental and world-editing rules for a path request.
 *
 * <p>Keeping these rules together prevents emergency and ambient routes from silently inheriting
 * the digging/pillaring permissions used by ordinary task navigation.</p>
 */
public enum TraversalPolicy {
    AMBIENT_DRY_OPEN(false, false, true, true),
    ESCAPE_DRY_OPEN(false, false, true, true),
    TASK_WALK_DRY(false, false, true, false),
    TASK_MUTATING_DRY(true, true, true, false),
    WATER_CAPABLE(false, false, false, false);

    private final boolean allowsDigging;
    private final boolean allowsPillaring;
    private final boolean requiresDryPath;
    private final boolean requiresOpenGoal;

    TraversalPolicy(boolean allowsDigging,
                    boolean allowsPillaring,
                    boolean requiresDryPath,
                    boolean requiresOpenGoal) {
        this.allowsDigging = allowsDigging;
        this.allowsPillaring = allowsPillaring;
        this.requiresDryPath = requiresDryPath;
        this.requiresOpenGoal = requiresOpenGoal;
    }

    public boolean allowsDigging() {
        return allowsDigging;
    }

    public boolean allowsPillaring() {
        return allowsPillaring;
    }

    public boolean requiresDryPath() {
        return requiresDryPath;
    }

    public boolean allowsWater() {
        return !requiresDryPath;
    }

    public boolean requiresOpenGoal() {
        return requiresOpenGoal;
    }

}
