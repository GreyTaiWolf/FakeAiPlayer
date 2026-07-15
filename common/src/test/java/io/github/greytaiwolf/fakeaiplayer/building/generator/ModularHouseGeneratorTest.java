package io.github.greytaiwolf.fakeaiplayer.building.generator;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlan;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanFingerprint;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanOrder;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildPhase;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanValidator;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.MaterialRole;
import io.github.greytaiwolf.fakeaiplayer.building.plan.PlanPlacement;
import io.github.greytaiwolf.fakeaiplayer.building.style.HouseMaterialStyle;
import io.github.greytaiwolf.fakeaiplayer.building.style.VanillaHouseStyles;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModularHouseGeneratorTest {
    private final ModularHouseGenerator generator = new ModularHouseGenerator();

    @Test
    void generatesAValidPlanWithEveryRequiredHouseModule() {
        BuildingPlan plan = generator.generate(request(42L));

        BuildingPlanValidator.ValidationResult result = BuildingPlanValidator.validate(plan);
        assertTrue(result.valid(), () -> result.problems().toString());
        assertEquals(9, plan.width());
        assertEquals(15, plan.depth());
        assertTrue(plan.height() > 7);

        Set<String> components = plan.placements().stream()
                .map(PlanPlacement::componentId)
                .collect(Collectors.toSet());
        assertTrue(components.contains("foundation:slab"));
        assertTrue(components.contains("floor:ground"));
        assertTrue(components.contains("floor:loft"));
        assertTrue(components.contains("interior:loft_stair"));
        assertTrue(components.contains("interior:stair_support"));
        assertTrue(components.contains("frame:corner_post_north_west"));
        assertTrue(components.contains("frame:corner_post_north_east"));
        assertTrue(components.contains("frame:corner_post_south_west"));
        assertTrue(components.contains("frame:corner_post_south_east"));
        assertTrue(components.contains("frame:beam_north"));
        assertTrue(components.contains("frame:beam_south"));
        assertTrue(components.contains("frame:beam_west"));
        assertTrue(components.contains("frame:beam_east"));
        assertTrue(components.stream().anyMatch(component -> component.startsWith("envelope:wall_")));
        assertTrue(components.stream().anyMatch(component -> component.startsWith("opening:window_")));
        assertTrue(components.contains("opening:front_door"));
        assertTrue(components.contains("exterior:porch_deck"));
        assertTrue(components.contains("foundation:porch_step_support"));
        assertTrue(components.contains("foundation:front_terrace"));
        assertTrue(components.contains("foundation:rear_terrace"));
        assertTrue(components.contains("exterior:porch_step_lower"));
        assertTrue(components.contains("exterior:porch_step_upper"));
        assertTrue(components.contains("envelope:gable_west"));
        assertTrue(components.contains("envelope:gable_east"));
        assertTrue(components.contains("roof:eave"));
        assertTrue(components.contains("roof:pitch"));
        assertTrue(components.contains("roof:ridge"));
        assertTrue(plan.placements().stream()
                        .filter(placement -> placement.componentId().startsWith("envelope:gable_"))
                        .allMatch(placement -> placement.phase() == BuildPhase.ROOF),
                "gable infill must interleave with roof rows instead of blocking roof access");
    }

    @Test
    void generationIsDeterministicAndSeedControlsLayoutVariation() {
        BuildingPlan first = generator.generate(request(91L));
        BuildingPlan second = generator.generate(request(91L));

        assertEquals(first, second);
        assertEquals(
                BuildingPlanFingerprint.sha256(first),
                BuildingPlanFingerprint.sha256(second));
        assertNotEquals(
                BuildingPlanFingerprint.sha256(first),
                BuildingPlanFingerprint.sha256(generator.generate(request(92L))));

        Set<String> variations = LongStream.range(0, 16)
                .mapToObj(seed -> generator.generate(request(seed)))
                .map(ModularHouseGeneratorTest::entranceSignature)
                .collect(Collectors.toSet());
        assertTrue(variations.size() > 1, "seed should vary door position/hinge or porch width");
    }

    @Test
    void canonicalOakHouseHasAStableGoldenFingerprint() {
        BuildingPlan plan = generator.generate(request(42L));

        assertEquals(539, plan.placements().size());
        assertEquals(
                "57ce452248146796800c75f735a23bd8c6f5adaae5d7bc2125a2a4999f5cc600",
                BuildingPlanFingerprint.sha256(plan));
    }

    @Test
    void permanentLoftStairAndEaveProvideReviewedRoofAccess() {
        BuildingPlan plan = generator.generate(request(42L));
        List<PlanPlacement> stairs = plan.placements().stream()
                .filter(placement -> placement.componentId().equals("interior:loft_stair"))
                .sorted(Comparator.comparingInt(PlanPlacement::dy))
                .toList();
        assertEquals(4, stairs.size());
        for (int index = 0; index < stairs.size(); index++) {
            PlanPlacement stair = stairs.get(index);
            assertEquals(BuildPhase.CONSTRUCTION_ACCESS, stair.phase());
            assertEquals("south", stair.state().properties().get("facing"));
            PlanPlacement support = placementAt(plan, stair.dx(), stair.dy() - 1, stair.dz());
            assertTrue(stair.dependencies().contains(support.id()));
            if (index > 0) {
                PlanPlacement previous = stairs.get(index - 1);
                assertEquals(previous.dx(), stair.dx());
                assertEquals(previous.dy() + 1, stair.dy());
                assertEquals(previous.dz() + 1, stair.dz());
                assertTrue(stair.dependencies().contains(previous.id()));
            }
        }

        List<PlanPlacement> loft = plan.placements().stream()
                .filter(placement -> placement.componentId().equals("floor:loft"))
                .toList();
        assertTrue(!loft.isEmpty());
        loft.forEach(placement -> {
            assertEquals(BuildPhase.CONSTRUCTION_ACCESS, placement.phase());
            assertEquals(4, placement.dy(),
                    "the work loft must leave headroom yet expose tall post tops from its edge");
            assertTrue(!placement.dependencies().isEmpty());
        });
        assertEquals(5, stairs.stream().mapToInt(PlanPlacement::dy).max().orElseThrow(),
                "wall-height 5 keeps one stair above the work loft for roof access");

        List<PlanPlacement> eaves = plan.placements().stream()
                .filter(placement -> placement.componentId().equals("roof:eave"))
                .toList();
        assertTrue(!eaves.isEmpty());
        eaves.forEach(placement -> {
            assertEquals(MaterialRole.ROOF_TRIM, placement.materialRole());
            assertEquals(BuildPhase.ROOF, placement.phase());
            assertTrue(placement.state().properties().isEmpty(),
                    "first roof row must not require an unreachable directional work pose");
        });
        int frontEaveZ = eaves.stream().mapToInt(PlanPlacement::dz).min().orElseThrow();
        int rearEaveZ = eaves.stream().mapToInt(PlanPlacement::dz).max().orElseThrow();
        assertEquals(plan.width() * 2, eaves.size(),
                "only the buildable front/rear overhangs should remain");
        Map<String, PlanPlacement> byId = plan.placements().stream()
                .collect(Collectors.toMap(PlanPlacement::id, placement -> placement));
        eaves.forEach(placement -> {
            PlanPlacement ringBeam = byId.get(placement.dependencies().get(0));
            assertEquals(placement.dx(), ringBeam.dx());
            assertEquals(placement.dy(), ringBeam.dy());
            assertEquals(
                    placement.dz() == frontEaveZ ? placement.dz() + 1 : placement.dz() - 1,
                    ringBeam.dz(),
                    "each eave must click the outward face of its own ring-beam cell");
        });
        List<PlanPlacement> terraces = plan.placements().stream()
                .filter(placement -> placement.componentId().equals("foundation:front_terrace")
                        || placement.componentId().equals("foundation:porch_support_west")
                        || placement.componentId().equals("foundation:porch_support_east")
                        || placement.componentId().equals("foundation:rear_terrace"))
                .toList();
        for (PlanPlacement eave : eaves) {
            int workZ = eave.dz() == frontEaveZ ? eave.dz() - 1 : eave.dz() + 1;
            PlanPlacement terrace = terraces.stream()
                    .filter(candidate -> candidate.dx() == eave.dx()
                            && candidate.dy() == 0 && candidate.dz() == workZ)
                    .findFirst()
                    .orElse(null);
            assertTrue(terrace != null,
                    () -> "missing permanent eave work terrace at x=" + eave.dx());
            assertTrue(eave.dependencies().contains(terrace.id()),
                    "the non-adjacent work platform must be an explicit plan dependency");
        }

        plan.placements().stream()
                .filter(placement -> placement.componentId().equals("roof:pitch"))
                .forEach(placement -> {
                    String facing = placement.state().properties().get("facing");
                    int outwardZ = "south".equals(facing)
                            ? placement.dz() - 1
                            : placement.dz() + 1;
                    assertTrue(placement.dependencies().stream()
                                    .map(byId::get)
                                    .anyMatch(dependency -> dependency != null
                                            && dependency.dx() == placement.dx()
                                            && dependency.dy() == placement.dy() - 1
                                            && dependency.dz() == outwardZ),
                            () -> "pitch row lacks its lower outward work platform: "
                                    + placement.id());
                });

        List<PlanPlacement> order = BuildingPlanOrder.stableTopological(plan);
        int lastAccess = order.stream()
                .filter(placement -> placement.componentId().equals("floor:loft")
                        || placement.componentId().equals("interior:loft_stair")
                        || placement.componentId().equals("interior:stair_support"))
                .mapToInt(order::indexOf)
                .max()
                .orElseThrow();
        int firstFrame = order.stream()
                .filter(placement -> placement.phase() == BuildPhase.FRAME)
                .mapToInt(order::indexOf)
                .min()
                .orElseThrow();
        assertTrue(lastAccess < firstFrame,
                "permanent high work access must be complete before tall frame placement");
        assertEquals("permanent_loft_stair", plan.metadata().get("roof_access"));
        assertTrue(plan.placements().stream()
                .noneMatch(placement -> placement.operation() == CellOperation.TEMPORARY));
    }

    @Test
    void entranceHasTwoWalkableHalfBlockRisesFromNaturalGround() {
        BuildingPlan plan = generator.generate(request(42L));
        PlanPlacement lower = component(plan, "exterior:porch_step_lower");
        PlanPlacement upper = component(plan, "exterior:porch_step_upper");
        PlanPlacement support = component(plan, "foundation:porch_step_support");
        PlanPlacement porch = plan.placements().stream()
                .filter(placement -> placement.componentId().equals("exterior:porch_deck"))
                .filter(placement -> placement.dx() == upper.dx())
                .filter(placement -> placement.dz() == upper.dz() + 1)
                .findFirst()
                .orElseThrow();

        assertEquals(0, lower.dy());
        assertEquals(1, upper.dy());
        assertEquals(lower.dz() + 1, upper.dz());
        assertEquals(upper.dx(), support.dx());
        assertEquals(upper.dz(), support.dz());
        assertEquals(0, support.dy());
        assertEquals(1, porch.dy());
        assertEquals("south", lower.state().properties().get("facing"));
        assertEquals("south", upper.state().properties().get("facing"));

        List<PlanPlacement> order = BuildingPlanOrder.stableTopological(plan);
        int upperStepIndex = order.indexOf(upper);
        int firstFrameIndex = order.indexOf(order.stream()
                .filter(placement -> placement.phase() == BuildPhase.FRAME)
                .findFirst()
                .orElseThrow());
        assertTrue(upperStepIndex < firstFrameIndex,
                "construction entrance must exist before frame work starts");
    }

    @Test
    void dependenciesAlwaysExistInAnEqualOrEarlierPhase() {
        BuildingPlan plan = generator.generate(request(1234L));
        Map<String, PlanPlacement> placements = new HashMap<>();
        for (PlanPlacement placement : plan.placements()) {
            placements.put(placement.id(), placement);
        }

        for (PlanPlacement placement : plan.placements()) {
            for (String dependencyId : placement.dependencies()) {
                PlanPlacement dependency = placements.get(dependencyId);
                assertTrue(dependency != null, () -> "missing " + dependencyId);
                assertTrue(
                        dependency.phase().ordinal() <= placement.phase().ordinal(),
                        () -> dependency.id() + " is after " + placement.id());
            }
        }
    }

    @Test
    void directionalAndAtomicStatesAreExplicit() {
        BuildingPlan plan = generator.generate(request(7L));

        Set<String> stairProperties = Set.of("facing", "half", "shape", "waterlogged");
        plan.placements().stream()
                .filter(placement -> placement.state().blockId().endsWith("_stairs"))
                .forEach(placement -> assertEquals(stairProperties, placement.state().properties().keySet()));

        Set<String> doorProperties = Set.of("facing", "half", "hinge", "open", "powered");
        var door = plan.placements().stream()
                .filter(placement -> placement.materialRole() == MaterialRole.DOOR)
                .toList();
        assertEquals(2, door.size());
        assertEquals(doorProperties, door.get(0).state().properties().keySet());
        assertEquals(doorProperties, door.get(1).state().properties().keySet());
        assertEquals("door:front", door.get(0).atomicGroup());
        assertEquals("door:front", door.get(1).atomicGroup());
        assertTrue(door.get(1).dependencies().contains(door.get(0).id()));

        PlanPlacement lowerDoor = door.stream()
                .filter(placement -> placement.state().properties().get("half").equals("lower"))
                .findFirst()
                .orElseThrow();
        assertEquals(BuildPhase.EXTERIOR_FEATURES, lowerDoor.phase());
        assertEquals(3, lowerDoor.dependencies().size());
        List<PlanPlacement> order = BuildingPlanOrder.stableTopological(plan);
        int doorIndex = order.indexOf(lowerDoor);
        int lastRoofIndex = order.stream()
                .filter(placement -> placement.phase() == BuildPhase.ROOF)
                .mapToInt(order::indexOf)
                .max()
                .orElseThrow();
        assertTrue(lastRoofIndex < doorIndex, "doorway must remain open until roof access is finished");
        for (String dependency : lowerDoor.dependencies()) {
            int dependencyIndex = order.indexOf(plan.placements().stream()
                    .filter(placement -> placement.id().equals(dependency))
                    .findFirst()
                    .orElseThrow());
            assertTrue(dependencyIndex < doorIndex, () -> dependency + " must precede door click");
        }
        List<PlanPlacement> delayedWestReveal = plan.placements().stream()
                .filter(placement -> placement.dx() == lowerDoor.dx() - 1)
                .filter(placement -> placement.dz() == lowerDoor.dz())
                .filter(placement -> placement.dy() >= lowerDoor.dy())
                .toList();
        assertTrue(delayedWestReveal.size() >= 2);
        delayedWestReveal.forEach(placement -> {
            assertTrue(placement.dependencies().contains(lowerDoor.id()));
            assertEquals(BuildPhase.EXTERIOR_FEATURES, placement.phase());
            assertTrue(order.indexOf(placement) > doorIndex);
        });

        plan.placements().stream()
                .filter(placement -> placement.materialRole() == MaterialRole.FRAME)
                .forEach(placement -> assertTrue(placement.state().properties().containsKey("axis")));
    }

    @Test
    void rejectsDimensionsThatCannotCarryTheModules() {
        assertThrows(IllegalArgumentException.class, () -> new HouseDimensions(6, 9, 5));
        assertThrows(IllegalArgumentException.class, () -> new HouseDimensions(9, 49, 5));
        assertThrows(IllegalArgumentException.class, () -> new HouseDimensions(9, 9, 3));
    }

    @Test
    void bothBuiltInStylesGenerateValidPlansAcrossThePublicDimensionAndSeedMatrix() {
        List<HouseMaterialStyle> styles = List.of(
                VanillaHouseStyles.OAK_COTTAGE,
                VanillaHouseStyles.SPRUCE_LODGE);

        for (HouseMaterialStyle style : styles) {
            for (int width = HouseDimensions.MIN_FOOTPRINT;
                 width <= HouseDimensions.MAX_FOOTPRINT;
                 width++) {
                for (int depth = HouseDimensions.MIN_FOOTPRINT;
                     depth <= HouseDimensions.MAX_FOOTPRINT;
                     depth++) {
                    for (int wallHeight = HouseDimensions.MIN_WALL_HEIGHT;
                         wallHeight <= HouseDimensions.MAX_WALL_HEIGHT;
                         wallHeight++) {
                        for (long seed = 0; seed < 32; seed++) {
                            BuildingPlan plan = generator.generate(new ModularHouseRequest(
                                    "test:matrix/" + style.id() + "/" + width + "/" + depth
                                            + "/" + wallHeight + "/" + seed,
                                    "Matrix house",
                                    new HouseDimensions(width, depth, wallHeight),
                                    seed,
                                    style));

                            BuildingPlanValidator.ValidationResult result =
                                    BuildingPlanValidator.validate(plan);
                            assertTrue(result.valid(), () -> result.problems().toString());
                            assertEquals(width, plan.width());
                            assertEquals(depth + 6, plan.depth());
                            assertEquals(
                                    wallHeight < depth ? "permanent_loft_stair" : "design_only",
                                    plan.metadata().get("roof_access"));
                            assertTrue(plan.placements().size()
                                    <= BuildingPlanValidator.MAX_PLACEMENTS);
                        }
                    }
                }
            }
        }
    }

    @Test
    void stableExecutionOrderAlwaysHasAnAdjacentPlacedSupport() {
        BuildingPlan plan = generator.generate(request(42L));
        Set<Cell> placed = new HashSet<>();
        for (PlanPlacement placement : BuildingPlanOrder.stableTopological(plan)) {
            Cell cell = new Cell(placement.dx(), placement.dy(), placement.dz());
            if (placement.phase() != BuildPhase.FOUNDATION) {
                assertTrue(hasAdjacent(cell, placed),
                        () -> "no placed click support for " + placement.id());
            }
            placed.add(cell);
        }
    }

    private ModularHouseRequest request(long seed) {
        return new ModularHouseRequest(
                "test:oak_house",
                "Oak test house",
                new HouseDimensions(9, 9, 5),
                seed,
                VanillaHouseStyles.OAK_COTTAGE);
    }

    private static String entranceSignature(BuildingPlan plan) {
        PlanPlacement lowerDoor = plan.placements().stream()
                .filter(placement -> placement.componentId().equals("opening:front_door"))
                .filter(placement -> placement.state().properties().get("half").equals("lower"))
                .findFirst()
                .orElseThrow();
        return lowerDoor.dx()
                + ":" + lowerDoor.state().properties().get("hinge")
                + ":" + plan.metadata().get("porch_width");
    }

    private static PlanPlacement component(BuildingPlan plan, String componentId) {
        return plan.placements().stream()
                .filter(placement -> placement.componentId().equals(componentId))
                .findFirst()
                .orElseThrow();
    }

    private static PlanPlacement placementAt(BuildingPlan plan, int x, int y, int z) {
        return plan.placements().stream()
                .filter(placement -> placement.dx() == x
                        && placement.dy() == y
                        && placement.dz() == z)
                .findFirst()
                .orElseThrow();
    }

    private static boolean hasAdjacent(Cell cell, Set<Cell> placed) {
        return placed.contains(new Cell(cell.x() + 1, cell.y(), cell.z()))
                || placed.contains(new Cell(cell.x() - 1, cell.y(), cell.z()))
                || placed.contains(new Cell(cell.x(), cell.y() + 1, cell.z()))
                || placed.contains(new Cell(cell.x(), cell.y() - 1, cell.z()))
                || placed.contains(new Cell(cell.x(), cell.y(), cell.z() + 1))
                || placed.contains(new Cell(cell.x(), cell.y(), cell.z() - 1));
    }

    private record Cell(int x, int y, int z) {
    }
}
