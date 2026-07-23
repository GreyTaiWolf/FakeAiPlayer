package io.github.greytaiwolf.fakeaiplayer.action;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.mode.CapabilityRuntime;
import io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery;
import io.github.greytaiwolf.fakeaiplayer.mode.PrivilegedCapability;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.InteractionPosePlanner;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.InteractionPosePlanner.InteractionPose;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.InteractionPosePlanner.PlanningBudget;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

public final class HarvestCore {
    // NAV-OPT(第0层B):可达性"名副其实"——只验证最近 N 个候选,用纯步行小预算 A*,兼顾准确与性能。
    private static final int REACH_VERIFY_LIMIT = 8;
    private HarvestCore() {
    }

    public static TargetChoice nearestReachableBlock(AIPlayerEntity bot, Block targetBlock, int horizontalRadius, int down, int up) {
        CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "harvest_nearest_block");
        BlockPos origin = bot.blockPosition();
        return bestReachableTarget(bot, origin,
                BlockPos.betweenClosedStream(origin.offset(-horizontalRadius, -down, -horizontalRadius), origin.offset(horizontalRadius, up, horizontalRadius))
                        .filter(pos -> ObservableWorldQuery.canObserveBlock(bot, pos))
                        .filter(pos -> bot.serverLevel().getBlockState(pos).is(targetBlock))
                        .map(BlockPos::immutable));
    }

    // MINE-DIG/Fix C:在一组候选方块里找最近可达的(如"任意原木"),供 GatherQuotaTask 跨树种采集。
    public static TargetChoice nearestReachableBlock(AIPlayerEntity bot, Set<Block> targetBlocks, int horizontalRadius, int down, int up) {
        return nearestReachableBlock(bot, targetBlocks, horizontalRadius, down, up, null);
    }

    // EXPLORE/不可达拉黑:带坐标过滤版——posFilter 拒绝的候选直接跳过(如工作记忆里"反复走不到"的目标),
    // null 不过滤。供 GatherQuotaTask.survey 滤掉拉黑目标,不再重锁同一棵不可达的树死循环。
    public static TargetChoice nearestReachableBlock(AIPlayerEntity bot, Set<Block> targetBlocks, int horizontalRadius, int down, int up,
                                                     Predicate<BlockPos> posFilter) {
        CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "harvest_nearest_blocks");
        BlockPos origin = bot.blockPosition();
        return bestReachableTarget(bot, origin,
                BlockPos.betweenClosedStream(origin.offset(-horizontalRadius, -down, -horizontalRadius), origin.offset(horizontalRadius, up, horizontalRadius))
                        .filter(pos -> ObservableWorldQuery.canObserveBlock(bot, pos))
                        .filter(pos -> targetBlocks.contains(bot.serverLevel().getBlockState(pos).getBlock()))
                        .filter(pos -> posFilter == null || posFilter.test(pos))
                        .map(BlockPos::immutable));
    }

    public static void startMining(AIPlayerEntity bot, BlockPos targetPos) {
        ToolSelector.equipBestTool(bot, bot.serverLevel().getBlockState(targetPos));
        MiningAction.startMining(bot, targetPos, Direction.getApproximateNearest(bot.getEyePosition().subtract(targetPos.getCenter())));
    }

    public static Optional<ItemEntity> nearestDrop(AIPlayerEntity bot, Item item, double radius) {
        return nearestDropAnyOf(bot, item == null ? null : Set.of(item), radius);
    }

    public static Optional<ItemEntity> nearestDropAnyOf(AIPlayerEntity bot, Set<Item> items, double radius) {
        return bot.serverLevel()
                .getEntitiesOfClass(ItemEntity.class, bot.getBoundingBox().inflate(radius),
                        entity -> !entity.getItem().isEmpty() && matches(entity.getItem(), items)
                                && ObservableWorldQuery.canObserveEntity(bot, entity))
                .stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceTo(bot)));
    }

    public static boolean forcePickupNearby(AIPlayerEntity bot, Item item, double maxH, double maxV) {
        return forcePickupNearbyAnyOf(bot, item == null ? null : Set.of(item), maxH, maxV);
    }

    public static boolean forcePickupNearbyAnyOf(AIPlayerEntity bot, Set<Item> items, double maxH, double maxV) {
        if (!CapabilityRuntime.decide(bot, PrivilegedCapability.FORCED_PICKUP, "harvest_force_pickup").allowed()) {
            return false;
        }
        AABB box = bot.getBoundingBox().inflate(maxH, maxV, maxH);
        List<ItemEntity> drops = bot.serverLevel().getEntitiesOfClass(ItemEntity.class, box,
                entity -> !entity.getItem().isEmpty()
                        && matches(entity.getItem(), items)
                        && ObservableWorldQuery.canObserveEntity(bot, entity)
                        && canForcePickup(bot, entity, maxH, maxV));
        boolean picked = false;
        for (ItemEntity drop : drops) {
            ItemStack remaining = drop.getItem().copy();
            int before = remaining.getCount();
            ActionResult result = InventoryAction.giveItem(bot, remaining);
            int inserted = before - remaining.getCount();
            if (inserted <= 0) {
                continue;
            }
            picked = true;
            BotLog.action(bot, "pickup_forced",
                    "item", drop.getItem().getItem(),
                    "count", inserted,
                    "result", result.isSuccess() ? "all" : result.reason());
            if (remaining.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(remaining);
            }
        }
        return picked;
    }

    public static boolean forcePickupNearby(AIPlayerEntity bot, Item item) {
        AIBotConfig.Pickup pickup = AIBotConfig.get().pickup();
        return forcePickupNearby(bot, item, pickup.forceRadiusH(), pickup.forceRadiusV());
    }

    public static boolean forcePickupNearbyAnyOf(AIPlayerEntity bot, Set<Item> items) {
        AIBotConfig.Pickup pickup = AIBotConfig.get().pickup();
        return forcePickupNearbyAnyOf(bot, items, pickup.forceRadiusH(), pickup.forceRadiusV());
    }

    public static void chaseDrop(AIPlayerEntity bot, Item item, double radius) {
        chaseDropAnyOf(bot, item == null ? null : Set.of(item), radius);
    }

    public static void chaseDropAnyOf(AIPlayerEntity bot, Set<Item> items, double radius) {
        if (forcePickupNearbyAnyOf(bot, items)) {
            bot.getActionPack().stopMovement();
            return;
        }
        nearestDropAnyOf(bot, items, radius).ifPresent(drop -> {
            if (bot.distanceTo(drop) > 1.3F) {
                if (bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().isWalkToIdle()
                        && bot.getActionPack().isMiningIdle()) {
                    ActionResult result = bot.getActionPack().startNonMutatingPathTo(
                            pickupStandPos(bot, drop.blockPosition()));
                    if (result.isFailed() || result.isSuccess()) {
                        // Exact block navigation can finish on an adjacent stand cell while the
                        // entity rests at the far edge of its block. Direct-walk the final fraction
                        // so vanilla collision pickup, not a privileged teleport, closes the gap.
                        bot.getActionPack().startWalkTo(drop.position());
                    }
                }
            } else {
                bot.getActionPack().stopMovement();
            }
        });
    }

    public static int sweepPickup(AIPlayerEntity bot, Item item, double radius, int maxTargets) {
        return sweepPickupAnyOf(bot, item == null ? null : Set.of(item), radius, maxTargets);
    }

    public static int sweepPickupAnyOf(AIPlayerEntity bot, Set<Item> items, double radius, int maxTargets) {
        int picked = 0;
        for (int i = 0; i < maxTargets; i++) {
            if (!forcePickupNearbyAnyOf(bot, items)) {
                break;
            }
            picked++;
        }
        if (picked > 0) {
            return picked;
        }
        nearestDropAnyOf(bot, items, radius).ifPresent(drop -> {
            if (bot.getActionPack().isPathExecutorIdle()
                    && bot.getActionPack().isWalkToIdle()
                    && bot.getActionPack().isMiningIdle()) {
                ActionResult result = bot.getActionPack().startNonMutatingPathTo(
                        pickupStandPos(bot, drop.blockPosition()));
                if (result.isFailed() || result.isSuccess()) {
                    bot.getActionPack().startWalkTo(drop.position());
                }
            }
        });
        return 0;
    }

    public static int sweepPickup(AIPlayerEntity bot, Item item, int maxTargets) {
        return sweepPickup(bot, item, AIBotConfig.get().pickup().sweepRadius(), maxTargets);
    }

    public static int sweepPickupAnyOf(AIPlayerEntity bot, Set<Item> items, int maxTargets) {
        return sweepPickupAnyOf(bot, items, AIBotConfig.get().pickup().sweepRadius(), maxTargets);
    }

    public static int totalInventoryCount(AIPlayerEntity bot) {
        int count = 0;
        for (ItemStack stack : bot.getInventory().items) {
            if (!stack.isEmpty()) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : bot.getInventory().offhand) {
            if (!stack.isEmpty()) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static int countInventoryItems(AIPlayerEntity bot, Set<Item> items) {
        int count = 0;
        for (ItemStack stack : bot.getInventory().items) {
            if (!stack.isEmpty() && matches(stack, items)) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : bot.getInventory().offhand) {
            if (!stack.isEmpty() && matches(stack, items)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static Set<Item> expectedDropsFor(Set<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return Set.of();
        }
        java.util.LinkedHashSet<Item> result = new java.util.LinkedHashSet<>();
        for (Block block : blocks) {
            result.addAll(expectedDropsFor(block));
        }
        return Set.copyOf(result);
    }

    public static Set<Item> expectedDropsFor(Block block) {
        if (block == Blocks.STONE) {
            return Set.of(Items.COBBLESTONE);
        }
        if (block == Blocks.DEEPSLATE) {
            return Set.of(Items.COBBLED_DEEPSLATE);
        }
        if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE) {
            return Set.of(Items.COAL);
        }
        if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) {
            return Set.of(Items.RAW_IRON);
        }
        if (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE) {
            return Set.of(Items.RAW_COPPER);
        }
        if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE) {
            return Set.of(Items.RAW_GOLD);
        }
        if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) {
            return Set.of(Items.REDSTONE);
        }
        if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) {
            return Set.of(Items.LAPIS_LAZULI);
        }
        if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) {
            return Set.of(Items.DIAMOND);
        }
        if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) {
            return Set.of(Items.EMERALD);
        }
        Item item = block.asItem();
        return item == Items.AIR ? Set.of() : Set.of(item);
    }

    public static boolean isInventoryFull(AIPlayerEntity bot) {
        for (ItemStack stack : bot.getInventory().items) {
            if (stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static BlockPos pickupStandPos(AIPlayerEntity bot, BlockPos itemPos) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos[] candidates = {
                itemPos,
                itemPos.north(),
                itemPos.south(),
                itemPos.east(),
                itemPos.west()
        };
        for (BlockPos candidate : candidates) {
            if (!Standability.isStandable(bot.serverLevel(), candidate)) {
                continue;
            }
            double distance = candidate.distSqr(bot.blockPosition());
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best == null ? itemPos : best;
    }

    public static boolean canReach(AIPlayerEntity bot, BlockPos target) {
        return InteractionPosePlanner.canInteractFromCurrent(bot, target);
    }

    public static boolean canDirectMine(AIPlayerEntity bot, BlockPos target) {
        // 允许挖脚下/低处方块(够得着即可)——地表往下挖石头的核心。
        return canReach(bot, target);
    }

    // NAV-OPT(第0层B):候选按距离近→远,返回第一个**纯步行真能走到**的;只验证最近 REACH_VERIFY_LIMIT 个
    // (纯步行小预算 A*),兼顾准确与性能。旧逻辑只看"目标相邻有空格"却不验证 bot 走得到,导致 GOTO 反复失败 stuck。
    private static TargetChoice bestReachableTarget(
            AIPlayerEntity bot,
            BlockPos origin,
            java.util.stream.Stream<BlockPos> candidates) {
        PlanningBudget budget = PlanningBudget.bounded(REACH_VERIFY_LIMIT * 8, 40L);
        return candidates
                // Bound the synchronous A* work before pose planning. Mapping every block in a
                // 48-block cube first would turn target selection into a server-thread spike.
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(origin)))
                .limit(REACH_VERIFY_LIMIT)
                .map(pos -> targetChoice(bot, pos, budget))
                .filter(java.util.Objects::nonNull)
                .min(Comparator
                        .comparingDouble((TargetChoice choice) -> choice.pose().pathCost())
                        .thenComparingDouble(choice -> choice.pos().distSqr(origin))
                        .thenComparingLong(choice -> choice.pos().asLong()))
                .orElse(null);
    }

    public static boolean isWalkReachable(AIPlayerEntity bot, TargetChoice choice) {
        return choice != null && choice.pose() != null;
    }

    public static TargetChoice targetChoice(AIPlayerEntity bot, BlockPos target) {
        return targetChoice(bot, target, PlanningBudget.bounded(8, 30L));
    }

    private static TargetChoice targetChoice(
            AIPlayerEntity bot, BlockPos target, PlanningBudget budget) {
        return InteractionPosePlanner.plan(bot, target, Set.of(), budget)
                .map(pose -> new TargetChoice(
                        target,
                        pose.currentPosition() ? null : pose.stand(),
                        pose.currentPosition(),
                        pose))
                .orElse(null);
    }

    /** Starts the exact dry route that won pose selection; never digs or pillars as a fallback. */
    public static ActionResult startApproach(AIPlayerEntity bot, TargetChoice choice) {
        if (choice == null) {
            return ActionResult.failed("missing_interaction_pose");
        }
        if (choice.direct()) {
            return ActionResult.SUCCESS;
        }
        InteractionPose pose = choice.pose();
        if (pose == null || choice.stand() == null) {
            return ActionResult.failed("invalid_interaction_pose");
        }
        return bot.getActionPack().startPlannedNonMutatingPath(choice.stand(), pose.path());
    }

    private static boolean canForcePickup(AIPlayerEntity bot, ItemEntity drop, double maxH, double maxV) {
        if (drop.hasPickUpDelay()) {
            return false;
        }
        double dx = drop.getX() - bot.getX();
        double dz = drop.getZ() - bot.getZ();
        return dx * dx + dz * dz <= maxH * maxH && Math.abs(drop.getY() - bot.getY()) <= maxV;
    }

    private static boolean matches(ItemStack stack, Set<Item> items) {
        if (items == null || items.isEmpty()) {
            return true;
        }
        return items.contains(stack.getItem());
    }

    public record TargetChoice(
            BlockPos pos,
            BlockPos stand,
            boolean direct,
            InteractionPose pose
    ) {
        public TargetChoice {
            pos = pos.immutable();
            stand = stand == null ? null : stand.immutable();
        }
    }
}
