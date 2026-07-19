package io.github.greytaiwolf.fakeaiplayer.building.catalog;

import io.github.greytaiwolf.fakeaiplayer.building.generator.BuildingRoofType;
import io.github.greytaiwolf.fakeaiplayer.building.generator.MultiStoreyBuildingGenerator;
import io.github.greytaiwolf.fakeaiplayer.building.generator.MultiStoreyBuildingRequest;
import io.github.greytaiwolf.fakeaiplayer.building.style.VanillaHouseStyles;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingCatalogTest {
    @Test
    void fourDigitNamespaceIsTotalDeterministicAndWithinCompilerBounds() {
        List<BuildingCatalogEntry> entries = BuildingCatalog.initialEntries().toList();

        assertEquals(BuildingSeedCode.INITIAL_CAPACITY, entries.size());
        int minimumWidth = Integer.MAX_VALUE;
        int maximumWidth = Integer.MIN_VALUE;
        int maximumDepth = Integer.MIN_VALUE;
        int maximumFloors = Integer.MIN_VALUE;
        for (int index = 0; index < entries.size(); index++) {
            BuildingCatalogEntry entry = entries.get(index);
            assertEquals(BuildingSeedCode.fourDigit(index), entry.seedCode());
            assertEquals(entry, BuildingCatalog.resolve(entry.seedCode()));
            assertEquals(BuildingCatalog.CATALOG_VERSION, entry.catalogVersion());
            assertEquals(MultiStoreyBuildingGenerator.GENERATOR_VERSION, entry.generatorVersion());
            assertTrue(entry.width() >= MultiStoreyBuildingRequest.MIN_FOOTPRINT);
            assertTrue(entry.width() <= MultiStoreyBuildingRequest.MAX_FOOTPRINT);
            assertTrue(entry.depth() >= MultiStoreyBuildingRequest.MIN_FOOTPRINT);
            assertTrue(entry.depth() <= MultiStoreyBuildingRequest.MAX_FOOTPRINT);
            assertTrue(entry.floors() >= MultiStoreyBuildingRequest.MIN_FLOORS);
            assertTrue(entry.floors() <= MultiStoreyBuildingRequest.MAX_FLOORS);
            assertSame(VanillaHouseStyles.byId(entry.styleId()), entry.materialStyle());
            assertTrue(entry.usesCurrentGenerator());
            minimumWidth = Math.min(minimumWidth, entry.width());
            maximumWidth = Math.max(maximumWidth, entry.width());
            maximumDepth = Math.max(maximumDepth, entry.depth());
            maximumFloors = Math.max(maximumFloors, entry.floors());
        }
        assertEquals(11, minimumWidth);
        assertEquals(48, maximumWidth);
        assertEquals(48, maximumDepth);
        assertEquals(8, maximumFloors);
    }

    @Test
    void selectedCodesHaveStableGoldenCatalogueAssignments() {
        assertEntry("0000", BuildingArchetype.LODGE, "dark_oak_manor",
                33, 32, 4, BuildingRoofType.STEPPED_GABLE, 9_223_212_457_466_898_894L);
        assertEntry("0001", BuildingArchetype.KEEP, "stone_keep",
                34, 36, 7, BuildingRoofType.STEPPED_GABLE, -4_521_928_294_636_493_979L);
        assertEntry("0042", BuildingArchetype.APARTMENT, "stone_keep",
                42, 45, 6, BuildingRoofType.FLAT_PARAPET, -6_930_649_001_066_360_724L);
        assertEntry("9999", BuildingArchetype.MANOR, "spruce_lodge",
                32, 29, 5, BuildingRoofType.STEPPED_GABLE, 7_499_407_423_807_033_039L);
        // The first expanded code proves that adding capacity does not reinterpret 9999.
        assertEntry("10000", BuildingArchetype.APARTMENT, "stone_keep",
                24, 44, 5, BuildingRoofType.FLAT_PARAPET, -2_992_240_336_324_043_151L);
    }

    @Test
    void frozenV1RouterAndWholeNamespaceManifestPreventSilentRemapping() {
        BuildingSeedCode code = BuildingSeedCode.parse("0042");
        assertEquals(BuildingCatalog.resolve(code),
                BuildingCatalog.resolve(code, BuildingCatalog.V1_CATALOG_VERSION));
        assertThrows(IllegalArgumentException.class,
                () -> BuildingCatalog.resolve(code, "building-catalog-2"));
        assertEquals("b1482d121f04972df990a54872f93a58dda5ec748c18a55d413a4ad299a5f33f",
                BuildingCatalog.initialNamespaceManifestSha256(
                        BuildingCatalog.V1_CATALOG_VERSION));
    }

    private static void assertEntry(
            String code,
            BuildingArchetype archetype,
            String style,
            int width,
            int depth,
            int floors,
            BuildingRoofType roof,
            long entropy
    ) {
        BuildingCatalogEntry entry = BuildingCatalog.resolve(code);
        assertEquals(archetype, entry.archetype());
        assertEquals(style, entry.styleId());
        assertEquals(width, entry.width());
        assertEquals(depth, entry.depth());
        assertEquals(floors, entry.floors());
        assertEquals(roof, entry.roofType());
        assertEquals(entropy, entry.entropy());
    }
}
