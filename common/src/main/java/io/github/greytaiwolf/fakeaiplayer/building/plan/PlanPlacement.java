package io.github.greytaiwolf.fakeaiplayer.building.plan;

import java.util.List;

/**
 * One immutable final-state design/preview cell in local coordinates.
 *
 * <p>It is not necessarily one physical click: a later compiler must turn multi-cell and
 * multi-click states into atomic {@code BuildStep}s before strict-survival execution.</p>
 */
public record PlanPlacement(
        String id,
        int dx,
        int dy,
        int dz,
        BlockStateSpec state,
        CellOperation operation,
        ReplacePolicy replacePolicy,
        MaterialRole materialRole,
        BuildPhase phase,
        String componentId,
        List<String> dependencies,
        String atomicGroup
) {
    public PlanPlacement {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("placement_id_missing");
        }
        if (state == null || operation == null || replacePolicy == null || materialRole == null || phase == null) {
            throw new IllegalArgumentException("placement_required_field_missing: " + id);
        }
        componentId = componentId == null || componentId.isBlank() ? "unassigned" : componentId;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        atomicGroup = atomicGroup == null ? "" : atomicGroup;
    }
}
