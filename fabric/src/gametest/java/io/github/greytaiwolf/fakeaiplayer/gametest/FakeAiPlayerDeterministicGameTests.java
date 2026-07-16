package io.github.greytaiwolf.fakeaiplayer.gametest;

import io.github.greytaiwolf.fakeaiplayer.building.generator.HouseDimensions;
import io.github.greytaiwolf.fakeaiplayer.building.generator.ModularHouseGenerator;
import io.github.greytaiwolf.fakeaiplayer.building.generator.ModularHouseRequest;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateResolver;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlan;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanBlueprintAdapter;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanValidator;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.PlanTransform;
import io.github.greytaiwolf.fakeaiplayer.building.style.VanillaHouseStyles;
import io.github.greytaiwolf.fakeaiplayer.goal.Goal;
import io.github.greytaiwolf.fakeaiplayer.goal.StructureVerifier;
import io.github.greytaiwolf.fakeaiplayer.persist.MissionSpec;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.AStarPathfinder;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.PathfindingResult;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.TraversalPolicy;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintLoader;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintSchema;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
    public void missionSpecsRoundTripWhileLegacyBuildRemainsUntrusted(GameTestHelper context) {
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
            if (restored instanceof Goal.Build build && build.hasCompleteConfirmedBinding()) {
                context.fail("Legacy build unexpectedly acquired a confirmation binding");
            }
        }
        context.succeed();
    }

    /**
     * Small live-world contract for the modular compiler. This deliberately does not run a whole
     * survival {@code BuildTask}: it proves that the reviewed generator output reaches the legacy
     * executor schema with real server registries, and samples one support/dependent column in the
     * GameTest world without requiring a large structure template, inventory or pathfinding.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 20)
    public void modularHouseCompilesToLiveBlockStatesAndDependencies(GameTestHelper context) {
        try {
            BuildingPlan plan = new ModularHouseGenerator().generate(new ModularHouseRequest(
                    "gametest:modular_house",
                    "GameTest modular house",
                    new HouseDimensions(7, 7, 4),
                    20260610L,
                    VanillaHouseStyles.OAK_COTTAGE));
            BuildingPlanValidator.ValidationResult validation =
                    BuildingPlanValidator.validateForExecution(plan);
            require(validation.valid(), "registry-aware plan validation failed: " + validation.problems());

            BlueprintSchema blueprint = BlueprintLoader.expand(
                    BuildingPlanBlueprintAdapter.adapt(plan, PlanTransform.IDENTITY));
            require(blueprint.placements().size() == plan.placements().size(),
                    "adapter changed placement count");

            BlueprintSchema.BlockPlacement required = null;
            BlueprintSchema.BlockPlacement dependent = null;
            for (int index = 0; index < blueprint.placements().size(); index++) {
                BlueprintSchema.BlockPlacement placement = blueprint.placements().get(index);
                require(placement.sequence() != null && placement.sequence() == index,
                        "non-contiguous sequence at index " + index);
                for (Integer prerequisite : placement.prerequisites()) {
                    require(prerequisite != null && prerequisite >= 0 && prerequisite < index,
                            "invalid prerequisite " + prerequisite + " at index " + index);
                }

                // Resolution here uses the registries of the running Minecraft server rather than
                // the generator's registry-independent block-state descriptions.
                BlockStateResolver.resolve(new BlockStateSpec(
                        placement.blockId(), placement.properties()));

                if (required == null && placement.operation() == CellOperation.PLACE
                        && placement.dy() == 1
                        && !placement.properties().isEmpty()) {
                    for (Integer prerequisite : placement.prerequisites()) {
                        BlueprintSchema.BlockPlacement candidate = blueprint.placements().get(prerequisite);
                        if (candidate.operation() == CellOperation.PLACE
                                && candidate.dy() == 0
                                && candidate.dx() == placement.dx()
                                && candidate.dz() == placement.dz()) {
                            required = candidate;
                            dependent = placement;
                            break;
                        }
                    }
                }
            }
            require(required != null && dependent != null,
                    "missing generated foundation-to-frame dependency");
            require(dependent.prerequisites().contains(required.sequence()),
                    "compiled dependency does not reference its support sequence");

            BlockPos requiredWorld = context.absolutePos(new BlockPos(1, 1, 1));
            BlockPos verifierAnchor = requiredWorld.offset(
                    -required.dx(), -required.dy(), -required.dz());
            BlockPos dependentWorld = verifierAnchor.offset(
                    dependent.dx(), dependent.dy(), dependent.dz());
            require(dependentWorld.equals(requiredWorld.above()),
                    "selected dependency is not a vertical support pair");

            BlockPos supportWorld = requiredWorld.below();
            context.getLevel().setBlockAndUpdate(supportWorld, Blocks.STONE.defaultBlockState());
            BlockState support = context.getLevel().getBlockState(supportWorld);
            require(support.getFluidState().isEmpty()
                            && support.isFaceSturdy(context.getLevel(), supportWorld, Direction.UP),
                    "foundation sample lacks dry sturdy support");

            BlockState requiredState = BlockStateResolver.resolve(new BlockStateSpec(
                    required.blockId(), required.properties()));
            BlockState dependentState = BlockStateResolver.resolve(new BlockStateSpec(
                    dependent.blockId(), dependent.properties()));
            context.getLevel().setBlockAndUpdate(requiredWorld, requiredState);
            context.getLevel().setBlockAndUpdate(dependentWorld, dependentState);
            require(StructureVerifier.matches(context.getLevel(), verifierAnchor, required),
                    "foundation state did not survive live-world placement");
            require(StructureVerifier.matches(context.getLevel(), verifierAnchor, dependent),
                    "dependent state did not survive live-world placement");

            // The dependent cell deliberately remains in the world. Removing and restoring only
            // its declared prerequisite proves the exact world predicate consumed by BuildTask's
            // prerequisite gate, without claiming that this test executes the whole task.
            context.getLevel().setBlockAndUpdate(requiredWorld, Blocks.AIR.defaultBlockState());
            require(!StructureVerifier.matches(context.getLevel(), verifierAnchor, required),
                    "removed prerequisite still matched");
            require(StructureVerifier.matches(context.getLevel(), verifierAnchor, dependent),
                    "removing the prerequisite unexpectedly changed the dependent cell");
            context.getLevel().setBlockAndUpdate(requiredWorld, requiredState);
            require(StructureVerifier.matches(context.getLevel(), verifierAnchor, required),
                    "restored prerequisite did not match");
            context.succeed();
        } catch (Exception exception) {
            context.fail("Modular house live-world contract failed: " + exception.getMessage());
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 20)
    public void dryPathDetoursAroundWater(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(1, 2, 1));
        BlockPos goal = start.offset(6, 0, 0);
        prepareFlat(context, start);
        try {
            for (int dz = -1; dz <= 1; dz++) {
                context.getLevel().setBlockAndUpdate(
                        start.offset(3, 0, dz), Blocks.WATER.defaultBlockState());
            }

            PathfindingResult result = new AStarPathfinder(
                    context.getLevel(), start, goal, 5_000, 250L,
                    TraversalPolicy.TASK_WALK_DRY, 1.0D).findPath(true);
            require(result.success(), "dry route did not find the available detour: " + result.reason());
            for (var node : result.path()) {
                BlockPos position = node.pos();
                require(context.getLevel().getFluidState(position).isEmpty()
                                && context.getLevel().getFluidState(position.above()).isEmpty()
                                && context.getLevel().getFluidState(position.below()).isEmpty(),
                        "dry route entered fluid at " + position.toShortString());
            }
            PathfindingResult aquatic = new AStarPathfinder(
                    context.getLevel(), start, goal, 5_000, 250L,
                    TraversalPolicy.WATER_CAPABLE, 1.0D).findPath(true);
            require(aquatic.success(), "explicit water-capable route failed: " + aquatic.reason());
            require(aquatic.path().stream().anyMatch(
                            node -> context.getLevel().getFluidState(node.pos()).is(net.minecraft.tags.FluidTags.WATER)),
                    "water-capable route never used the shorter water crossing");
        } finally {
            clearFlat(context, start);
        }
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 20)
    public void cachedPathIsRejectedAfterWallAppears(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(1, 2, 1));
        BlockPos goal = start.offset(6, 0, 0);
        prepareFlat(context, start);
        try {
            AStarPathfinder firstFinder = new AStarPathfinder(
                    context.getLevel(), start, goal, 5_000, 250L,
                    TraversalPolicy.TASK_WALK_DRY, 1.0D);
            PathfindingResult first = firstFinder.findPath(true);
            require(first.success() && first.path().size() >= 4, "initial flat route failed");

            BlockPos blocked = first.path().get(first.path().size() / 2).pos();
            context.getLevel().setBlockAndUpdate(blocked, Blocks.STONE.defaultBlockState());
            context.getLevel().setBlockAndUpdate(blocked.above(), Blocks.STONE.defaultBlockState());
            PathfindingResult replanned = new AStarPathfinder(
                    context.getLevel(), start, goal, 5_000, 250L,
                    TraversalPolicy.TASK_WALK_DRY, 1.0D).findPath();
            require(replanned.success(), "wall invalidation did not produce a detour: " + replanned.reason());
            require(replanned.path().stream().noneMatch(node -> node.pos().equals(blocked)),
                    "cached route still crossed the newly blocked cell");
        } finally {
            clearFlat(context, start);
        }
        context.succeed();
    }

    private static void prepareFlat(GameTestHelper context, BlockPos center) {
        for (int dx = -1; dx <= 7; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = center.offset(dx, 0, dz);
                context.getLevel().setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
                context.getLevel().setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
                context.getLevel().setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static void clearFlat(GameTestHelper context, BlockPos center) {
        for (int dx = -1; dx <= 7; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = center.offset(dx, 0, dz);
                context.getLevel().setBlockAndUpdate(feet.below(), Blocks.AIR.defaultBlockState());
                context.getLevel().setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
                context.getLevel().setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
