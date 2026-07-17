package io.github.greytaiwolf.fakeaiplayer.building.site;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildPhase;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.MaterialRole;
import io.github.greytaiwolf.fakeaiplayer.building.plan.PlanPlacement;
import io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingSiteSelectorTest {
    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.bootStrap();
    }

    @Test
    void candidateSearchIsDeterministicAndStrictlyBounded() {
        BlockPos centre = new BlockPos(100, 72, -40);

        List<BlockPos> first = BuildingSiteSelector.candidateOrigins(centre, 31, 19, 48);
        List<BlockPos> second = BuildingSiteSelector.candidateOrigins(centre, 31, 19, 48);

        assertEquals(first, second);
        assertEquals(1 + 8 * 3, first.size());
        assertEquals(new BlockPos(85, 72, -49), first.get(0));
        assertEquals((long) first.size(), first.stream().distinct().count());
        assertTrue(BuildingSiteSelector.MAX_FINE_SURVEYS_PER_ORIENTATION < first.size());
    }

    @Test
    void zeroRadiusOnlySurveysTheCentredFootprint() {
        assertEquals(
                List.of(new BlockPos(-5, 64, -7)),
                BuildingSiteSelector.candidateOrigins(new BlockPos(0, 64, 0), 11, 15, 0));
    }

    @Test
    void standabilityProbeBudgetIsAHardSharedCeiling() {
        assertEquals(1, BuildingSiteSelector.MAX_FINE_SURVEYS_PER_ORIENTATION);
        assertTrue(BuildingSiteSurveyor.MAX_SELECTION_STANDABILITY_PROBES <= 40_000);
        BuildingSiteSurveyor.ProbeBudget budget = new BuildingSiteSurveyor.ProbeBudget(3);

        assertTrue(budget.tryProbe());
        assertTrue(budget.tryProbe());
        assertTrue(budget.tryProbe());
        assertFalse(budget.tryProbe());
        assertTrue(budget.exhausted());
        assertEquals(3, budget.used());
        assertEquals(3, budget.maximum());
        assertThrows(IllegalArgumentException.class,
                () -> new BuildingSiteSurveyor.ProbeBudget(0));
    }

    @Test
    void clearanceRejectsSolidFluidAndBlockEntityConflicts() {
        PlanPlacement emptyOnly = placement(ReplacePolicy.REQUIRE_EMPTY);
        PlanPlacement replaceable = placement(ReplacePolicy.REPLACE_REPLACEABLE);

        assertTrue(BuildingSiteSelector.isCompatibleCell(
                Blocks.AIR.defaultBlockState(), false, emptyOnly));
        assertTrue(BuildingSiteSelector.isCompatibleCell(
                Blocks.COBBLESTONE.defaultBlockState(), false, emptyOnly));
        assertFalse(BuildingSiteSelector.isCompatibleCell(
                Blocks.STONE.defaultBlockState(), false, emptyOnly));
        assertFalse(BuildingSiteSelector.isCompatibleCell(
                Blocks.WATER.defaultBlockState(), false, replaceable));
        assertTrue(BuildingSiteSelector.isCompatibleCell(
                Blocks.SHORT_GRASS.defaultBlockState(), false, replaceable));
        assertFalse(BuildingSiteSelector.isCompatibleCell(
                Blocks.COBBLESTONE.defaultBlockState(), true, emptyOnly));
    }

    private static PlanPlacement placement(ReplacePolicy policy) {
        return new PlanPlacement(
                "test@0,0,0", 0, 0, 0,
                new BlockStateSpec("minecraft:cobblestone", Map.of()),
                CellOperation.PLACE, policy, MaterialRole.FOUNDATION,
                BuildPhase.FOUNDATION, "test", List.of(), "");
    }
}
