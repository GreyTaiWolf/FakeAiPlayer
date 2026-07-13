package io.github.greytaiwolf.fakeaiplayer.gametest;

import io.github.greytaiwolf.fakeaiplayer.goal.Goal;
import io.github.greytaiwolf.fakeaiplayer.persist.MissionSpec;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import java.util.List;
import java.util.Set;

/**
 * Minimal world-backed smoke tests for the isolated GameTest source set.
 *
 * <p>These tests deliberately avoid random state, external services and FakeAiPlayer persistence so a
 * failure always reflects the compiled mod/runtime rather than a reused world.</p>
 */
public final class FakeAiPlayerDeterministicGameTests implements FabricGameTest {
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 20)
    public void blockMutationIsVisible(GameTestHelper context) {
        context.setBlock(1, 1, 1, Blocks.STONE);
        context.assertBlockPresent(Blocks.STONE, 1, 1, 1);
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 20)
    public void scheduledAssertionRunsAtExpectedTick(GameTestHelper context) {
        context.setBlock(2, 1, 2, Blocks.OAK_PLANKS);
        context.runAtTickTime(2, () -> {
            context.assertBlockPresent(Blocks.OAK_PLANKS, 2, 1, 2);
            context.succeed();
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 20)
    public void missionSpecsRoundTripWithBootstrappedRegistries(GameTestHelper context) {
        List<Goal> goals = List.of(
                new Goal.HaveItem(Items.IRON_INGOT, 3),
                new Goal.HavePickaxeTier(3),
                new Goal.MineOre(Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE), 4),
                new Goal.HarvestCrop(Blocks.WHEAT, Items.WHEAT_SEEDS, Items.WHEAT, 8),
                new Goal.Armor(),
                new Goal.Workstation(),
                new Goal.Stockpile(Items.COBBLESTONE, 64),
                new Goal.Food(5),
                new Goal.Build("small_hut"));

        for (Goal goal : goals) {
            Goal restored = MissionSpec.fromGoal(goal).toGoal().orElseThrow();
            if (!goal.equals(restored)) {
                context.fail("MissionSpec round-trip mismatch for " + goal);
            }
        }
        context.succeed();
    }
}
