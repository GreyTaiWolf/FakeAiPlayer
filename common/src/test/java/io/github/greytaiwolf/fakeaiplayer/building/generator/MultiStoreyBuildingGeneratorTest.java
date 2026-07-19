package io.github.greytaiwolf.fakeaiplayer.building.generator;

import io.github.greytaiwolf.fakeaiplayer.building.catalog.BuildingCatalog;
import io.github.greytaiwolf.fakeaiplayer.building.catalog.BuildingCatalogEntry;
import io.github.greytaiwolf.fakeaiplayer.building.catalog.BuildingSeedCode;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingDesignFingerprint;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlan;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanFingerprint;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanValidator;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.PlanPlacement;
import io.github.greytaiwolf.fakeaiplayer.building.style.HouseMaterialStyle;
import io.github.greytaiwolf.fakeaiplayer.building.style.VanillaHouseStyles;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiStoreyBuildingGeneratorTest {
    private final MultiStoreyBuildingGenerator generator = new MultiStoreyBuildingGenerator();

    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.bootStrap();
    }

    @Test
    void everyCatalogMaterialStyleCompilesToExecutableBlockStates() {
        for (HouseMaterialStyle style : VanillaHouseStyles.all()) {
            BuildingCatalogEntry entry = BuildingCatalog.initialEntries()
                    .filter(candidate -> candidate.styleId().equals(style.id()))
                    .findFirst()
                    .orElseThrow();
            BuildingPlan plan = generator.generate(entry.toRequest("execution:" + style.id()));
            BuildingPlanValidator.ValidationResult validation =
                    BuildingPlanValidator.validateForExecution(plan);
            assertTrue(validation.valid(), () -> style.id() + ": " + validation.problems());
        }
    }

    @Test
    void emitsEveryLargeBuildingModuleAndRequiredMetadata() {
        BuildingCatalogEntry entry = BuildingCatalog.resolve("0042");
        BuildingPlan plan = generator.generate(entry.toRequest("test:0042", "Golden building"));

        BuildingPlanValidator.ValidationResult validation = BuildingPlanValidator.validate(plan);
        assertTrue(validation.valid(), () -> validation.problems().toString());
        assertEquals(entry.width(), plan.width());
        assertEquals(entry.depth(), plan.depth());
        assertEquals(entry.floors(), Integer.parseInt(plan.metadata().get("floors")));
        assertEquals("0042", plan.metadata().get("building_code"));
        assertEquals(BuildingCatalog.CATALOG_VERSION, plan.metadata().get("catalog_version"));
        assertEquals(entry.archetype().id(), plan.metadata().get("archetype"));
        assertEquals(entry.styleId(), plan.metadata().get("style"));
        assertEquals(MultiStoreyBuildingGenerator.GENERATOR_VERSION, plan.generatorVersion());
        assertEquals(20_074, plan.placements().size());
        assertEquals("f1ca52a7c898ad9cbf7cf8ef08649ab59092e07c726f225da15dc4f4b44ce738",
                BuildingDesignFingerprint.sha256(plan));
        assertTrue(plan.placements().size() <= BuildingPlanValidator.MAX_PLACEMENTS);

        Set<String> components = plan.placements().stream()
                .map(PlanPlacement::componentId)
                .collect(Collectors.toSet());
        assertTrue(components.contains("foundation:raft"));
        assertTrue(components.contains("floor:ground_slab"));
        assertTrue(components.stream().anyMatch(value -> value.startsWith("floor:level_")));
        assertTrue(components.contains("vertical_core:support"));
        assertTrue(components.contains("vertical_core:stair"));
        assertTrue(components.contains("frame:post"));
        assertTrue(components.contains("frame:ring_beam"));
        assertTrue(components.contains("envelope:wall"));
        assertTrue(components.contains("opening:window"));
        assertTrue(components.contains("opening:main_door"));
        assertTrue(components.stream().anyMatch(value -> value.startsWith("interior:partition_")));
        assertTrue(components.contains("roof:deck"));
        assertTrue(components.contains("roof:pitch") || components.contains("roof:parapet"));
        assertTrue(plan.metadata().get("module_set").contains("interior_partitions"));
        assertEquals("permanent_supported_switchback_stair", plan.metadata().get("vertical_core"));
        assertTrue(plan.placements().stream()
                .noneMatch(placement -> placement.operation() == CellOperation.TEMPORARY));
    }

    @Test
    void permanentCoreConnectsEverySlabAndTheRoof() {
        BuildingCatalogEntry entry = BuildingCatalog.resolve("0420");
        BuildingPlan plan = generator.generate(entry.toRequest("test:connected"));
        List<PlanPlacement> stairs = plan.placements().stream()
                .filter(placement -> placement.componentId().equals("vertical_core:stair"))
                .sorted(Comparator.comparingInt(PlanPlacement::dy))
                .toList();

        assertEquals(entry.floors() * MultiStoreyBuildingRequest.FLOOR_HEIGHT, stairs.size());
        for (int index = 0; index < stairs.size(); index++) {
            PlanPlacement stair = stairs.get(index);
            assertEquals(2 + index, stair.dy());
            if (index > 0) {
                assertTrue(stair.dependencies().contains(stairs.get(index - 1).id()),
                        () -> stair.id() + " is disconnected from prior stair");
            }
        }

        int coreX = Integer.parseInt(plan.metadata().get("vertical_core_x"));
        int coreStartZ = Integer.parseInt(plan.metadata().get("vertical_core_z").split("\\.\\.")[0]);
        int coreEndZ = Integer.parseInt(plan.metadata().get("vertical_core_z").split("\\.\\.")[1]);
        for (int transition = 0; transition < entry.floors(); transition++) {
            List<PlanPlacement> flight = stairs.subList(
                    transition * MultiStoreyBuildingRequest.FLOOR_HEIGHT,
                    (transition + 1) * MultiStoreyBuildingRequest.FLOOR_HEIGHT);
            assertTrue(flight.stream().allMatch(stair -> stair.dx() == coreX));
            assertEquals(transition % 2 == 0 ? coreStartZ : coreEndZ, flight.get(0).dz());
            assertEquals(transition % 2 == 0 ? coreEndZ : coreStartZ, flight.get(3).dz());
            assertEquals(transition % 2 == 0 ? "south" : "north",
                    flight.get(0).state().properties().get("facing"));
        }

        Set<String> cells = plan.placements().stream()
                .map(placement -> placement.dx() + "," + placement.dy() + "," + placement.dz())
                .collect(Collectors.toSet());
        for (int level = 0; level <= entry.floors(); level++) {
            int y = 1 + level * MultiStoreyBuildingRequest.FLOOR_HEIGHT;
            long cellsAtSlab = cells.stream()
                    .filter(cell -> Integer.parseInt(cell.split(",")[1]) == y)
                    .count();
            assertEquals((long) entry.width() * entry.depth(), cellsAtSlab,
                    "every slab must be complete except for the stair cell occupying its landing");
        }
    }

    @Test
    void designHashIsStableAcrossCallsAndPlanInstances() {
        BuildingCatalogEntry entry = BuildingCatalog.resolve("0042");
        BuildingPlan first = generator.generate(entry.toRequest("owner-a:preview-1", "Alice's preview"));
        BuildingPlan repeated = generator.generate(entry.toRequest("owner-a:preview-1", "Alice's preview"));
        BuildingPlan otherInstance = generator.generate(entry.toRequest("owner-b:preview-9", "Bob's preview"));

        assertEquals(first, repeated);
        assertEquals(BuildingPlanFingerprint.sha256(first), BuildingPlanFingerprint.sha256(repeated));
        assertEquals(BuildingDesignFingerprint.sha256(first), BuildingDesignFingerprint.sha256(otherInstance));
        assertNotEquals(BuildingPlanFingerprint.sha256(first), BuildingPlanFingerprint.sha256(otherInstance));

        Map<String, String> instanceMetadata = new HashMap<>(otherInstance.metadata());
        instanceMetadata.put("owner_id", "bob");
        instanceMetadata.put("session_id", "session-9");
        BuildingPlan decorated = new BuildingPlan(
                otherInstance.schemaVersion(), "another-plan-id", 17, "Renamed",
                otherInstance.width(), otherInstance.height(), otherInstance.depth(),
                otherInstance.seed(), otherInstance.generatorVersion(), otherInstance.placements(),
                instanceMetadata);
        assertEquals(BuildingDesignFingerprint.sha256(first), BuildingDesignFingerprint.sha256(decorated));

        BuildingPlan differentCode = generator.generate(
                BuildingCatalog.resolve("0043").toRequest("owner-a:preview-1"));
        assertNotEquals(BuildingDesignFingerprint.sha256(first),
                BuildingDesignFingerprint.sha256(differentCode));
    }

    @Test
    void validatesAndHashesAtLeastTwoThousandDeterministicRandomDesigns() {
        Set<String> sampledCodes = new HashSet<>();
        List<String> failures = new ArrayList<>();
        for (int sample = 0; sample < 2_048; sample++) {
            // 7,919 is coprime to 10,000, producing a deterministic non-repeating permutation.
            int ordinal = Math.floorMod(137 + sample * 7_919, BuildingSeedCode.INITIAL_CAPACITY);
            BuildingSeedCode code = BuildingSeedCode.fourDigit(ordinal);
            sampledCodes.add(code.value());
            BuildingCatalogEntry entry = BuildingCatalog.resolve(code);
            BuildingPlan plan = generator.generate(entry.toRequest("stress:" + code.value()));
            BuildingPlanValidator.ValidationResult validation = BuildingPlanValidator.validate(plan);
            if (!validation.valid()) {
                failures.add(code + ":" + validation.problems().get(0));
            }
            assertEquals(64, BuildingDesignFingerprint.sha256(plan).length());
            assertTrue(plan.placements().size() <= BuildingPlanValidator.MAX_PLACEMENTS);
        }
        assertEquals(2_048, sampledCodes.size());
        assertTrue(failures.isEmpty(), () -> failures.subList(0, Math.min(8, failures.size())).toString());
    }

    @Test
    void enforcesPublishedLargeBuildingBounds() {
        BuildingCatalogEntry base = BuildingCatalog.resolve("0042");
        assertThrows(IllegalArgumentException.class, () -> requestWith(base, 10, 11, 2));
        assertThrows(IllegalArgumentException.class, () -> requestWith(base, 49, 11, 2));
        assertThrows(IllegalArgumentException.class, () -> requestWith(base, 11, 10, 2));
        assertThrows(IllegalArgumentException.class, () -> requestWith(base, 11, 49, 2));
        assertThrows(IllegalArgumentException.class, () -> requestWith(base, 11, 11, 1));
        assertThrows(IllegalArgumentException.class, () -> requestWith(base, 11, 11, 9));
    }

    @Test
    void maximumFootprintAndFloorCountStayInsidePlanLimitsForBothRoofModules() {
        BuildingCatalogEntry base = BuildingCatalog.resolve("0042");
        for (BuildingRoofType roofType : BuildingRoofType.values()) {
            MultiStoreyBuildingRequest request = new MultiStoreyBuildingRequest(
                    "test:max:" + roofType.id(), "Maximum building", base.seedCode(),
                    base.entropy(), base.archetype(), 48, 48, 8, base.materialStyle(),
                    roofType, base.catalogVersion());
            BuildingPlan plan = generator.generate(request);
            BuildingPlanValidator.ValidationResult validation = BuildingPlanValidator.validate(plan);
            assertTrue(validation.valid(), () -> validation.problems().toString());
            assertEquals(48, plan.width());
            assertEquals(48, plan.depth());
            assertTrue(plan.placements().size() <= BuildingPlanValidator.MAX_PLACEMENTS);
        }
    }

    private static MultiStoreyBuildingRequest requestWith(
            BuildingCatalogEntry base,
            int width,
            int depth,
            int floors
    ) {
        return new MultiStoreyBuildingRequest(
                "test:bounds", "Bounds", base.seedCode(), base.entropy(), base.archetype(),
                width, depth, floors, base.materialStyle(), base.roofType(), base.catalogVersion());
    }
}
