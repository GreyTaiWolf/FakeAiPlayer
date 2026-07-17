package io.github.greytaiwolf.fakeaiplayer.task;

import com.google.gson.Gson;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueprintLoaderStateTest {
    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.bootStrap();
    }

    @Test
    void legacyConstructorKeepsAnEmptyPropertyMask() {
        BlueprintSchema.BlockPlacement placement = new BlueprintSchema.BlockPlacement(
                0, 0, 0, "minecraft:oak_planks", "planks");

        assertTrue(placement.properties().isEmpty());
    }

    @Test
    void legacyJsonReceivesSafeOperationDefaults() {
        BlueprintSchema.BlockPlacement placement = new Gson().fromJson(
                "{\"dx\":0,\"dy\":0,\"dz\":0,\"blockId\":\"minecraft:stone\"}",
                BlueprintSchema.BlockPlacement.class);

        assertEquals(CellOperation.PLACE, placement.operation());
        assertEquals(ReplacePolicy.REPLACE_REPLACEABLE, placement.replacePolicy());
        assertTrue(placement.properties().isEmpty());
    }

    @Test
    void expansionPreservesCanonicalStateProperties() throws IOException {
        BlueprintSchema source = new BlueprintSchema(
                "stateful",
                2,
                1,
                1,
                List.of(),
                List.of(new BlueprintSchema.Op(
                        "fill",
                        new int[]{0, 0, 0},
                        new int[]{1, 0, 0},
                        "minecraft:oak_log",
                        null,
                        Map.of("axis", "x"))));

        BlueprintSchema expanded = BlueprintLoader.expand(source);

        assertEquals(2, expanded.placements().size());
        assertEquals(Map.of("axis", "x"), expanded.placements().get(0).properties());
    }

    @Test
    void rejectsUnknownStatePropertyBeforeConstruction() {
        BlueprintSchema source = new BlueprintSchema(
                "invalid_state",
                1,
                1,
                1,
                List.of(new BlueprintSchema.BlockPlacement(
                        0, 0, 0, "minecraft:oak_log", null, Map.of("direction", "sideways"))),
                List.of());

        assertThrows(IOException.class, () -> BlueprintLoader.expand(source));
    }

    @Test
    void rejectsPlacementsOutsideDeclaredBounds() {
        BlueprintSchema source = new BlueprintSchema(
                "outside",
                1,
                1,
                1,
                List.of(new BlueprintSchema.BlockPlacement(1, 0, 0, "minecraft:oak_planks")),
                List.of());

        assertThrows(IOException.class, () -> BlueprintLoader.expand(source));
    }

    @Test
    void rejectsPaletteAndExplicitBlockMismatch() {
        BlueprintSchema source = new BlueprintSchema(
                "palette_mismatch",
                1,
                1,
                1,
                List.of(new BlueprintSchema.BlockPlacement(
                        0, 0, 0, "minecraft:oak_stairs", "planks", Map.of("facing", "north"))),
                List.of());

        assertThrows(IOException.class, () -> BlueprintLoader.expand(source));
    }

    @Test
    void rejectsCumulativeExpansionWorkEvenWhenOpsOverlap() {
        BlueprintSchema.Op fullVolume = new BlueprintSchema.Op(
                "fill",
                new int[]{0, 0, 0},
                new int[]{127, 3, 127},
                "minecraft:oak_planks",
                null);
        BlueprintSchema source = new BlueprintSchema(
                "overlapping_work",
                128,
                4,
                128,
                List.of(),
                List.of(fullVolume, fullVolume, fullVolume, fullVolume, fullVolume));

        assertThrows(IOException.class, () -> BlueprintLoader.expand(source));
    }

    @Test
    void rejectsSitePreparationVolumesThatCannotFitTheExecutionBudget() {
        BlueprintSchema source = new BlueprintSchema(
                "oversized_site",
                128,
                128,
                128,
                List.of(new BlueprintSchema.BlockPlacement(0, 0, 0, "minecraft:stone")),
                List.of());

        assertThrows(IOException.class, () -> BlueprintLoader.expand(source));
    }

    @Test
    void normalizesDocumentedParametricPaletteAliases() throws IOException {
        BlueprintSchema expanded = BlueprintLoader.expand(
                BlueprintSchema.parametricHouse("custom:7x5x4:stone"));

        assertTrue(expanded.placements().stream()
                .filter(placement -> !placement.blockId().equals("minecraft:air"))
                .allMatch(placement -> "stone_like".equals(placement.palette())));
    }

    @Test
    void preservesV2SemanticsAndSortsOnlyByCompleteSequence() throws IOException {
        BlueprintSchema.BlockPlacement second = new BlueprintSchema.BlockPlacement(
                0, 0, 0, "minecraft:oak_planks", null, Map.of(),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, "", 1, List.of(0));
        BlueprintSchema.BlockPlacement first = new BlueprintSchema.BlockPlacement(
                1, 0, 0, "minecraft:stone", null, Map.of(),
                CellOperation.PRESERVE, ReplacePolicy.PRESERVE_EXISTING, "", 0);

        BlueprintSchema expanded = BlueprintLoader.expand(new BlueprintSchema(
                "ordered", 2, 1, 1, List.of(second, first), List.of()));

        assertEquals(1, expanded.placements().get(0).dx());
        assertEquals(CellOperation.PRESERVE, expanded.placements().get(0).operation());
        assertEquals(ReplacePolicy.REQUIRE_EMPTY, expanded.placements().get(1).replacePolicy());
        assertEquals("", expanded.placements().get(1).atomicGroup());
        assertEquals(List.of(0), expanded.placements().get(1).prerequisites());
    }

    @Test
    void rejectsPartialOrDuplicateSequences() {
        BlueprintSchema.BlockPlacement sequenced = new BlueprintSchema.BlockPlacement(
                0, 0, 0, "minecraft:stone", null, Map.of(),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, "", 0);
        BlueprintSchema.BlockPlacement legacy = new BlueprintSchema.BlockPlacement(
                1, 0, 0, "minecraft:stone");
        BlueprintSchema.BlockPlacement duplicate = new BlueprintSchema.BlockPlacement(
                1, 0, 0, "minecraft:stone", null, Map.of(),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, "", 0);
        BlueprintSchema.BlockPlacement gap = new BlueprintSchema.BlockPlacement(
                1, 0, 0, "minecraft:stone", null, Map.of(),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, "", 2);
        BlueprintSchema.BlockPlacement futureDependency = new BlueprintSchema.BlockPlacement(
                1, 0, 0, "minecraft:stone", null, Map.of(),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, "", 1, List.of(1));
        BlueprintSchema.BlockPlacement legacyDependency = new BlueprintSchema.BlockPlacement(
                1, 0, 0, "minecraft:stone", null, Map.of(),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, "", null, List.of(0));

        assertThrows(IOException.class, () -> BlueprintLoader.expand(new BlueprintSchema(
                "mixed_sequence", 2, 1, 1, List.of(sequenced, legacy), List.of())));
        assertThrows(IOException.class, () -> BlueprintLoader.expand(new BlueprintSchema(
                "duplicate_sequence", 2, 1, 1, List.of(sequenced, duplicate), List.of())));
        assertThrows(IOException.class, () -> BlueprintLoader.expand(new BlueprintSchema(
                "sequence_gap", 2, 1, 1, List.of(sequenced, gap), List.of())));
        assertThrows(IOException.class, () -> BlueprintLoader.expand(new BlueprintSchema(
                "future_dependency", 2, 1, 1, List.of(sequenced, futureDependency), List.of())));
        assertThrows(IOException.class, () -> BlueprintLoader.expand(new BlueprintSchema(
                "legacy_dependency", 2, 1, 1, List.of(sequenced, legacyDependency), List.of())));
    }

    @Test
    void rejectsOperationPolicyAndStateMismatches() {
        BlueprintSchema.BlockPlacement clearStone = new BlueprintSchema.BlockPlacement(
                0, 0, 0, "minecraft:stone", null, Map.of(),
                CellOperation.CLEAR, ReplacePolicy.CLEAR_AUTHORIZED, "", null);
        BlueprintSchema.BlockPlacement preserveWithPlacePolicy = new BlueprintSchema.BlockPlacement(
                0, 0, 0, "minecraft:stone", null, Map.of(),
                CellOperation.PRESERVE, ReplacePolicy.REQUIRE_EMPTY, "", null);

        assertThrows(IOException.class, () -> BlueprintLoader.expand(new BlueprintSchema(
                "clear_stone", 1, 1, 1, List.of(clearStone), List.of())));
        assertThrows(IOException.class, () -> BlueprintLoader.expand(new BlueprintSchema(
                "preserve_policy", 1, 1, 1, List.of(preserveWithPlacePolicy), List.of())));
    }

    @Test
    void rejectsExecutorConceptsWithoutAuthorizationOrCleanup() {
        BlueprintSchema.BlockPlacement forced = new BlueprintSchema.BlockPlacement(
                0, 0, 0, "minecraft:stone", null, Map.of(),
                CellOperation.PLACE, ReplacePolicy.FORCE_AUTHORIZED, "", null);
        BlueprintSchema.BlockPlacement temporary = new BlueprintSchema.BlockPlacement(
                0, 0, 0, "minecraft:stone", null, Map.of(),
                CellOperation.TEMPORARY, ReplacePolicy.REQUIRE_EMPTY, "", null);

        assertThrows(IOException.class, () -> BlueprintLoader.expand(new BlueprintSchema(
                "forced", 1, 1, 1, List.of(forced), List.of())));
        assertThrows(IOException.class, () -> BlueprintLoader.expand(new BlueprintSchema(
                "temporary", 1, 1, 1, List.of(temporary), List.of())));
    }

    @Test
    void atomicGroupsMustDescribeOnePlaceItemAndPolicy() throws IOException {
        BlueprintSchema.BlockPlacement first = new BlueprintSchema.BlockPlacement(
                0, 0, 0, "minecraft:oak_door", null, Map.of("half", "lower"),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, "door", null);
        BlueprintSchema.BlockPlacement validUpper = new BlueprintSchema.BlockPlacement(
                0, 1, 0, "minecraft:oak_door", null, Map.of("half", "upper"),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, "door", null);
        BlueprintSchema.BlockPlacement wrongBlock = new BlueprintSchema.BlockPlacement(
                0, 1, 0, "minecraft:spruce_door", null, Map.of("half", "upper"),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, "door", null);
        BlueprintSchema.BlockPlacement wrongPolicy = new BlueprintSchema.BlockPlacement(
                0, 1, 0, "minecraft:oak_door", null, Map.of("half", "upper"),
                CellOperation.PLACE, ReplacePolicy.REPLACE_REPLACEABLE, "door", null);
        BlueprintSchema.BlockPlacement clearMember = new BlueprintSchema.BlockPlacement(
                0, 1, 0, "minecraft:air", null, Map.of(),
                CellOperation.CLEAR, ReplacePolicy.CLEAR_AUTHORIZED, "door", null);
        BlueprintSchema.BlockPlacement diagonalUpper = new BlueprintSchema.BlockPlacement(
                1, 1, 0, "minecraft:oak_door", null, Map.of("half", "upper"),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, "door", null);
        BlueprintSchema.BlockPlacement ordinaryFirst = new BlueprintSchema.BlockPlacement(
                0, 0, 0, "minecraft:oak_planks", null, Map.of(),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, "fake_pair", null);
        BlueprintSchema.BlockPlacement ordinarySecond = new BlueprintSchema.BlockPlacement(
                0, 1, 0, "minecraft:oak_planks", null, Map.of(),
                CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY, "fake_pair", null);

        assertEquals(2, BlueprintLoader.expand(new BlueprintSchema(
                "atomic_valid", 1, 2, 1, List.of(first, validUpper), List.of())).placements().size());
        assertThrows(IOException.class, () -> BlueprintLoader.expand(new BlueprintSchema(
                "atomic_block", 1, 2, 1, List.of(first, wrongBlock), List.of())));
        assertThrows(IOException.class, () -> BlueprintLoader.expand(new BlueprintSchema(
                "atomic_policy", 1, 2, 1, List.of(first, wrongPolicy), List.of())));
        assertThrows(IOException.class, () -> BlueprintLoader.expand(new BlueprintSchema(
                "atomic_clear", 1, 2, 1, List.of(first, clearMember), List.of())));
        assertThrows(IOException.class, () -> BlueprintLoader.expand(new BlueprintSchema(
                "atomic_diagonal", 2, 2, 1, List.of(first, diagonalUpper), List.of())));
        assertThrows(IOException.class, () -> BlueprintLoader.expand(new BlueprintSchema(
                "atomic_ordinary", 1, 2, 1, List.of(ordinaryFirst, ordinarySecond), List.of())));
    }
}
