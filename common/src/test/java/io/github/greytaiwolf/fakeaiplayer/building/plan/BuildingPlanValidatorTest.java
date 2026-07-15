package io.github.greytaiwolf.fakeaiplayer.building.plan;

import java.util.List;
import java.util.Map;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingPlanValidatorTest {
    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.bootStrap();
    }

    @Test
    void acceptsAValidSupportedDependencyChain() {
        PlanPlacement foundation = placement("foundation", 0, 0, 0, BuildPhase.FOUNDATION, List.of());
        PlanPlacement column = placement("column", 0, 1, 0, BuildPhase.FRAME, List.of("foundation"));
        BuildingPlan plan = plan(List.of(foundation, column));

        assertTrue(BuildingPlanValidator.validate(plan).valid());
        assertEquals(64, BuildingPlanFingerprint.sha256(plan).length());
    }

    @Test
    void rejectsDuplicateCellsMissingDependenciesAndCycles() {
        PlanPlacement first = placement("a", 0, 0, 0, BuildPhase.FRAME, List.of("b"));
        PlanPlacement second = placement("b", 0, 0, 0, BuildPhase.FRAME, List.of("a", "missing"));

        BuildingPlanValidator.ValidationResult result = BuildingPlanValidator.validate(plan(List.of(first, second)));

        assertFalse(result.valid());
        assertTrue(has(result, "duplicate_cell"));
        assertTrue(has(result, "missing_dependency"));
        assertTrue(has(result, "dependency_cycle"));
    }

    @Test
    void fingerprintChangesWhenAStatePropertyChanges() {
        PlanPlacement bottom = stairs("bottom");
        PlanPlacement top = stairs("top");

        assertNotEquals(
                BuildingPlanFingerprint.sha256(plan(List.of(bottom))),
                BuildingPlanFingerprint.sha256(plan(List.of(top))));
    }

    @Test
    void dependencyOrderDoesNotChangeTheFingerprint() {
        PlanPlacement a = placement("a", 0, 0, 0, BuildPhase.FOUNDATION, List.of());
        PlanPlacement b = placement("b", 1, 0, 0, BuildPhase.FOUNDATION, List.of());
        PlanPlacement firstOrder = placement("c", 0, 1, 0, BuildPhase.FRAME, List.of("a", "b"));
        PlanPlacement secondOrder = placement("c", 0, 1, 0, BuildPhase.FRAME, List.of("b", "a"));

        assertEquals(
                BuildingPlanFingerprint.sha256(plan(List.of(a, b, firstOrder))),
                BuildingPlanFingerprint.sha256(plan(List.of(a, b, secondOrder))));
    }

    @Test
    void rejectsInvalidOperationPolicyPairs() {
        PlanPlacement invalid = new PlanPlacement(
                "invalid", 0, 0, 0,
                new BlockStateSpec("minecraft:oak_planks"),
                CellOperation.PLACE, ReplacePolicy.CLEAR_AUTHORIZED, MaterialRole.WALL,
                BuildPhase.WALLS_AND_OPENINGS, "test:wall", List.of(), "");

        BuildingPlanValidator.ValidationResult result = BuildingPlanValidator.validate(plan(List.of(invalid)));

        assertFalse(result.valid());
        assertTrue(has(result, "operation_policy_mismatch"));
    }

    @Test
    void executionValidationRejectsUnknownRegistryState() {
        PlanPlacement unknown = new PlanPlacement(
                "unknown", 0, 0, 0,
                new BlockStateSpec("example:not_registered"),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, MaterialRole.GENERIC,
                BuildPhase.WALLS_AND_OPENINGS, "test:unknown", List.of(), "");

        BuildingPlanValidator.ValidationResult result =
                BuildingPlanValidator.validateForExecution(plan(List.of(unknown)));

        assertFalse(result.valid());
        assertTrue(has(result, "invalid_block_state"));
    }

    @Test
    void executionValidationRejectsDoorAtomicGroupWithDiagonalUpperHalf() {
        PlanPlacement lower = door("door_lower", 1, 0, 1, "lower", List.of());
        PlanPlacement upper = door("door_upper", 1, 1, 2, "upper", List.of("door_lower"));

        BuildingPlanValidator.ValidationResult result =
                BuildingPlanValidator.validateForExecution(plan(List.of(lower, upper)));

        assertFalse(result.valid());
        assertTrue(has(result, "door_atomic_group_footprint"));
    }

    @Test
    void executionValidationRejectsOrdinaryBlocksPretendingToBeOneAtomicItem() {
        PlanPlacement first = new PlanPlacement(
                "plank_a", 0, 0, 0, new BlockStateSpec("minecraft:oak_planks"),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, MaterialRole.WALL,
                BuildPhase.WALLS_AND_OPENINGS, "test:wall", List.of(), "fake:pair");
        PlanPlacement second = new PlanPlacement(
                "plank_b", 1, 0, 0, new BlockStateSpec("minecraft:oak_planks"),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, MaterialRole.WALL,
                BuildPhase.WALLS_AND_OPENINGS, "test:wall", List.of(), "fake:pair");

        BuildingPlanValidator.ValidationResult result =
                BuildingPlanValidator.validateForExecution(plan(List.of(first, second)));

        assertFalse(result.valid());
        assertTrue(has(result, "unsupported_atomic_group_block"));
    }

    private static PlanPlacement stairs(String half) {
        PlanPlacement top = new PlanPlacement(
                "stairs", 0, 0, 0,
                new BlockStateSpec("minecraft:oak_stairs", Map.of("half", half)),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, MaterialRole.STAIRS,
                BuildPhase.FLOORS_AND_STAIRS, "stairs:straight", List.of(), "");
        return top;
    }

    private static PlanPlacement door(String id,
                                      int x,
                                      int y,
                                      int z,
                                      String half,
                                      List<String> dependencies) {
        return new PlanPlacement(
                id, x, y, z,
                new BlockStateSpec("minecraft:oak_door", Map.of(
                        "facing", "south",
                        "half", half,
                        "hinge", "left",
                        "open", "false",
                        "powered", "false")),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, MaterialRole.DOOR,
                BuildPhase.WALLS_AND_OPENINGS, "test:door", dependencies, "door:test");
    }

    private static boolean has(BuildingPlanValidator.ValidationResult result, String code) {
        return result.problems().stream().anyMatch(problem -> problem.code().equals(code));
    }

    private static PlanPlacement placement(String id,
                                           int x,
                                           int y,
                                           int z,
                                           BuildPhase phase,
                                           List<String> dependencies) {
        return new PlanPlacement(
                id, x, y, z,
                new BlockStateSpec("minecraft:oak_planks"),
                CellOperation.PLACE,
                ReplacePolicy.REQUIRE_EMPTY,
                MaterialRole.FRAME,
                phase,
                "test:component",
                dependencies,
                "");
    }

    private static BuildingPlan plan(List<PlanPlacement> placements) {
        return new BuildingPlan(
                BuildingPlan.CURRENT_SCHEMA_VERSION,
                "test:plan",
                0,
                "test",
                4,
                4,
                4,
                42L,
                "test-1",
                placements,
                Map.of("style", "test"));
    }
}
