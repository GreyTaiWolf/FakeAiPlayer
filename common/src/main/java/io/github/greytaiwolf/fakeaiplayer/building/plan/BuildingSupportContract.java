package io.github.greytaiwolf.fakeaiplayer.building.plan;

import java.util.Map;

/** Shared confirmation/persistence contract for plan cells that depend on existing terrain. */
public final class BuildingSupportContract {
    private BuildingSupportContract() {
    }

    /**
     * A foundation PLACE needs external support when the plan does not provide a PLACE directly
     * below it. Persisting this result into the executor blueprint closes the confirmation-to-build
     * race even after terrain adaptation moves the first supported cell above local y=0.
     */
    public static boolean requiresExternalSupport(
            PlanPlacement placement,
            Map<String, PlanPlacement> placementsById
    ) {
        if (placement == null
                || placement.phase() != BuildPhase.FOUNDATION
                || placement.operation() != CellOperation.PLACE) {
            return false;
        }
        for (String dependencyId : placement.dependencies()) {
            PlanPlacement dependency = placementsById.get(dependencyId);
            if (dependency != null
                    && dependency.operation() == CellOperation.PLACE
                    && dependency.dx() == placement.dx()
                    && dependency.dy() == placement.dy() - 1
                    && dependency.dz() == placement.dz()) {
                return false;
            }
        }
        return true;
    }
}
