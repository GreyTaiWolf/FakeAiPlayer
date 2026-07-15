package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import io.github.greytaiwolf.fakeaiplayer.action.ActionPack;
import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.action.BuildAction;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.action.LookAction;
import io.github.greytaiwolf.fakeaiplayer.action.MiningController;
import io.github.greytaiwolf.fakeaiplayer.action.WalkToController;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.log.LogCategory;
import io.github.greytaiwolf.fakeaiplayer.log.LogFields;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class PathExecutor {
    private static final int STUCK_TICKS_LIMIT = 60;
    private static final int REPLAN_COOLDOWN_TICKS = 40;

    private List<Node> path;
    private int index = 1;
    private final BlockPos originalGoal;
    private final boolean exactGoal;
    private final boolean allowPillarOnReplan;
    private final boolean allowDigOnReplan;
    private WalkToController subWalker;
    private MiningController subMiner;
    private boolean digWalking;
    private boolean replanTried;
    private Vec3 lastPos;
    private int stuckTicks;
    private int totalTicks;
    private int lastReplanTick = -REPLAN_COOLDOWN_TICKS;
    private int activeWalkTargetIndex = -1;
    private int nodeRetry;

    public PathExecutor(List<Node> path, BlockPos originalGoal) {
        this(path, originalGoal, false, true, true);
    }

    public PathExecutor(List<Node> path, BlockPos originalGoal, boolean exactGoal) {
        this(path, originalGoal, exactGoal, true, true);
    }

    public PathExecutor(List<Node> path,
                        BlockPos originalGoal,
                        boolean exactGoal,
                        boolean allowPillarOnReplan,
                        boolean allowDigOnReplan) {
        this.path = List.copyOf(path);
        this.originalGoal = originalGoal.immutable();
        this.exactGoal = exactGoal;
        this.allowPillarOnReplan = allowPillarOnReplan;
        this.allowDigOnReplan = allowDigOnReplan;
    }

    public ActionResult tick(ActionPack pack) {
        totalTicks++;
        if (path.isEmpty() || index >= path.size()) {
            cleanup(pack);
            double distSq = pack.player().blockPosition().distSqr(originalGoal);
            if (exactGoal && !isExactGoal(pack.player().blockPosition(), originalGoal)) {
                BotLog.warn(LogCategory.PATH, pack.player(), "path_end_not_exact",
                        "dist_sq", distSq, "goal", LogFields.pos(originalGoal));
                return ActionResult.failed("ended_before_exact_goal dist_sq=" + (int) distSq);
            }
            if (distSq > 4.0D) {
                BotLog.warn(LogCategory.PATH, pack.player(), "path_end_far_from_goal",
                        "dist_sq", distSq, "goal", LogFields.pos(originalGoal));
                return ActionResult.failed("ended_far_from_goal dist_sq=" + (int) distSq);
            }
            return ActionResult.SUCCESS;
        }

        Node next = path.get(index);
        String danger = DangerCheck.scan(pack.player().serverLevel(), next.pos());
        if (danger != null) {
            BotLog.warn(LogCategory.PATH, pack.player(), "path_danger", "at_node", LogFields.pos(next.pos()), "reason", danger);
            cleanup(pack);
            return ActionResult.failed("danger_at_node: " + danger);
        }

        ActionResult result = switch (next.moveType()) {
            case WALK, DIAGONAL, JUMP_UP, DROP_DOWN -> tickWalk(pack, next);
            case DIG_THROUGH -> tickDigThrough(pack, next);
            case PILLAR_UP -> tickPillar(pack, next);
        };
        if (!result.isInProgress()) {
            return result;
        }
        Node progressNode = activeWalkTargetIndex >= index && activeWalkTargetIndex < path.size()
                ? path.get(activeWalkTargetIndex)
                : next;
        return checkProgress(pack, progressNode);
    }

    public void abort(ActionPack pack) {
        cleanup(pack);
    }

    public int totalTicks() {
        return totalTicks;
    }

    public static boolean hasPlaceableBlock(AIPlayerEntity player) {
        return findPlaceableBlock(player) >= 0;
    }

    private ActionResult tickWalk(ActionPack pack, Node next) {
        if (hasArrived(pack.player().blockPosition(), next.pos())) {
            advance();
            return ActionResult.IN_PROGRESS;
        }
        if (subWalker == null) {
            activeWalkTargetIndex = chooseWalkTargetIndex(pack);
            Node target = path.get(activeWalkTargetIndex);
            if (activeWalkTargetIndex > index) {
                BotLog.path(pack.player(), "path_skip",
                        "from_index", index,
                        "to_index", activeWalkTargetIndex,
                        "from", LogFields.pos(next.pos()),
                        "to", LogFields.pos(target.pos()));
            }
            BlockPos requiredColumn = exactGoal && target.pos().equals(originalGoal)
                    ? originalGoal
                    : null;
            subWalker = new WalkToController(Vec3.atCenterOf(target.pos()), requiredColumn);
        }
        Node target = path.get(activeWalkTargetIndex);
        if (hasArrived(pack.player().blockPosition(), target.pos())) {
            advanceTo(activeWalkTargetIndex + 1);
            return ActionResult.IN_PROGRESS;
        }
        ActionResult result = subWalker.tick(pack);
        if (result.isSuccess()) {
            advanceTo(activeWalkTargetIndex + 1);
        }
        if (result.isFailed()) {
            return handleWalkFailure(pack, "walk_failed: " + result.reason());
        }
        return ActionResult.IN_PROGRESS;
    }

    private ActionResult tickDigThrough(ActionPack pack, Node next) {
        if (!digWalking) {
            if (subMiner == null) {
                Direction face = faceFromPlayer(pack, next.pos());
                LookAction.lookAtBlock(pack.player(), next.pos(), face);
                subMiner = new MiningController(next.pos(), face);
            }
            ActionResult mine = subMiner.tick(pack);
            if (mine.isFailed()) {
                return handleStuck(pack, "dig_failed: " + mine.reason());
            }
            if (mine.isInProgress()) {
                return ActionResult.IN_PROGRESS;
            }
            // 穿山双格挖:脚位挖完后头位仍有碰撞(实心山体内部每步如此)→ 再挖头位,人才进得去。
            // 配合 NeighborEnumerator.hasHeadroom 的"头位可挖即可"放宽,挖掘寻路从贴地刨坑升级为穿山打洞。
            BlockPos headPos = next.pos().above();
            if (!pack.player().serverLevel().getBlockState(headPos)
                    .getCollisionShape(pack.player().serverLevel(), headPos).isEmpty()) {
                Direction headFace = faceFromPlayer(pack, headPos);
                LookAction.lookAtBlock(pack.player(), headPos, headFace);
                subMiner = new MiningController(headPos, headFace);
                return ActionResult.IN_PROGRESS;
            }
            subMiner = null;
            digWalking = true;
            subWalker = new WalkToController(Vec3.atCenterOf(next.pos()));
        }

        ActionResult walk = subWalker.tick(pack);
        if (walk.isSuccess()) {
            advance();
        }
        if (walk.isFailed()) {
            return handleWalkFailure(pack, "dig_walk_failed: " + walk.reason());
        }
        return ActionResult.IN_PROGRESS;
    }

    // NAV-9:垫方块上升一格。看向脚下→起跳→升空瞬间在原脚位放支撑方块→落到其上。
    private ActionResult tickPillar(ActionPack pack, Node next) {
        AIPlayerEntity player = pack.player();
        BlockPos placeSlot = next.pos().below(); // 当前脚位,支撑方块放这里
        if (player.getBlockY() >= next.pos().getY() && player.onGround()) {
            advance();
            return ActionResult.IN_PROGRESS;
        }
        int slot = findPlaceableBlock(player);
        if (slot < 0) {
            return handleStuck(pack, "pillar_no_block");
        }
        InventoryAction.equipFromSlot(player, slot);
        LookAction.lookAtBlock(player, placeSlot, Direction.UP);
        pack.setForward(0.0F);
        pack.setJumping(true);
        pack.jumpOnce();
        double rise = player.getY() - placeSlot.getY();
        if (rise > 0.5D && rise < 1.2D && player.serverLevel().getBlockState(placeSlot).isAir()) {
            BuildAction.placeBlockAt(player, placeSlot);
        }
        return ActionResult.IN_PROGRESS;
    }

    private static int findPlaceableBlock(AIPlayerEntity player) {
        var main = player.getInventory().items;
        for (int i = 0; i < main.size(); i++) {
            ItemStack stack = main.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            var block = blockItem.getBlock();
            if (isPathFillerBlock(block)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isPathFillerBlock(net.minecraft.world.level.block.Block block) {
        return block == Blocks.COBBLESTONE || block == Blocks.DIRT || block == Blocks.STONE
                || block == Blocks.COBBLED_DEEPSLATE || block == Blocks.DEEPSLATE
                || block == Blocks.NETHERRACK || block == Blocks.ANDESITE
                || block == Blocks.DIORITE || block == Blocks.GRANITE;
    }

    private ActionResult checkProgress(ActionPack pack, Node next) {
        Vec3 current = pack.player().position();
        if (lastPos != null && current.distanceTo(lastPos) < 0.03D) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPos = current;
        if (stuckTicks > STUCK_TICKS_LIMIT) {
            return handleStuck(pack, "no_progress_at: " + compact(next.pos()));
        }
        return ActionResult.IN_PROGRESS;
    }

    private void advance() {
        advanceTo(index + 1);
    }

    private void advanceTo(int nextIndex) {
        Node next = path.get(index);
        BotLog.path(null, "path_advance", "index", index, "total", path.size(), "move_type", next.moveType(), "pos", LogFields.pos(next.pos()));
        index = Math.max(index + 1, Math.min(nextIndex, path.size()));
        subWalker = null;
        subMiner = null;
        digWalking = false;
        stuckTicks = 0;
        lastPos = null;
        activeWalkTargetIndex = -1;
        nodeRetry = 0;
        replanTried = false;
    }

    private int chooseWalkTargetIndex(ActionPack pack) {
        int best = index;
        BlockPos from = pack.player().blockPosition();
        int max = Math.min(path.size() - 1, index + AIBotConfig.get().nav().lookahead());
        for (int candidate = index + 1; candidate <= max; candidate++) {
            if (!canStringPullTo(pack, from, candidate)) {
                break;
            }
            best = candidate;
        }
        return best;
    }

    private boolean canStringPullTo(ActionPack pack, BlockPos from, int candidateIndex) {
        for (int i = index; i <= candidateIndex; i++) {
            MoveType type = path.get(i).moveType();
            if (type != MoveType.WALK && type != MoveType.DIAGONAL && type != MoveType.JUMP_UP) {
                return false;
            }
        }
        BlockPos target = path.get(candidateIndex).pos();
        int dy = target.getY() - from.getY();
        if (dy < -1 || dy > 1) {
            return false;
        }
        return lineClearForStringPull(pack.player().serverLevel(), from, target);
    }

    private static boolean lineClearForStringPull(net.minecraft.server.level.ServerLevel world, BlockPos from, BlockPos target) {
        int dx = target.getX() - from.getX();
        int dy = target.getY() - from.getY();
        int dz = target.getZ() - from.getZ();
        int samples = Math.max(1, Math.max(Math.abs(dx), Math.abs(dz)) * 2);
        for (int i = 1; i <= samples; i++) {
            double t = (double) i / samples;
            BlockPos sample = BlockPos.containing(
                    from.getX() + 0.5D + dx * t,
                    from.getY() + dy * t,
                    from.getZ() + 0.5D + dz * t);
            if (!passableColumn(world, sample)) {
                return false;
            }
            if (!hasSupport(world, sample) && !sample.equals(from)) {
                return false;
            }
        }
        return Standability.isStandable(world, target);
    }

    private static boolean passableColumn(net.minecraft.server.level.ServerLevel world, BlockPos feet) {
        return world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                && world.getBlockState(feet.above()).getCollisionShape(world, feet.above()).isEmpty();
    }

    private static boolean hasSupport(net.minecraft.server.level.ServerLevel world, BlockPos feet) {
        BlockPos below = feet.below();
        return !world.getBlockState(below).getCollisionShape(world, below).isEmpty();
    }

    private static boolean arrivedAt(BlockPos current, BlockPos target) {
        int dx = current.getX() - target.getX();
        int dz = current.getZ() - target.getZ();
        return dx * dx + dz * dz <= 1 && Math.abs(current.getY() - target.getY()) <= 1;
    }

    private boolean hasArrived(BlockPos current, BlockPos target) {
        if (exactGoal && target.equals(originalGoal)) {
            return isExactGoal(current, target);
        }
        return arrivedAt(current, target);
    }

    /**
     * Directional placement only needs the reviewed horizontal work cell. A player standing on a
     * stair or slab can report a feet Y one block below the path node, so vertical equality would
     * reject an otherwise exact and stable placement column.
     */
    private static boolean isExactGoal(BlockPos current, BlockPos target) {
        return current.getX() == target.getX()
                && current.getZ() == target.getZ()
                && Math.abs(current.getY() - target.getY()) <= 1;
    }

    private ActionResult handleWalkFailure(ActionPack pack, String reason) {
        if (reason.contains("stuck_blocked") && nodeRetry < AIBotConfig.get().nav().nodeRetry()) {
            nodeRetry++;
            int previous = index;
            index = Math.max(1, index - 1);
            subWalker = null;
            activeWalkTargetIndex = -1;
            stuckTicks = 0;
            lastPos = null;
            pack.stopMovement();
            BotLog.path(pack.player(), "path_node_retry",
                    "reason", reason,
                    "retry", nodeRetry,
                    "from_index", previous,
                    "to_index", index);
            return ActionResult.IN_PROGRESS;
        }
        return handleStuck(pack, reason);
    }

    private ActionResult handleStuck(ActionPack pack, String reason) {
        if (!replanTried) {
            int now = pack.player().getServer().getTickCount();
            if (now - lastReplanTick < REPLAN_COOLDOWN_TICKS) {
                cleanup(pack);
                return ActionResult.failed(reason + "; replan_throttled");
            }
            lastReplanTick = now;
            replanTried = true;
            BotLog.path(pack.player(), "path_stuck", "at_node", reason, "stuck_ticks", stuckTicks);
            if (!pack.snapPlayerToNearestStandable("path_replan_start_invalid")) {
                cleanup(pack);
                return ActionResult.failed(reason + "; replan_failed: NO_START");
            }
            boolean canPillar = allowPillarOnReplan && hasPlaceableBlock(pack.player());
            AStarPathfinder finder = new AStarPathfinder(
                    pack.player().serverLevel(),
                    pack.player().blockPosition(),
                    originalGoal,
                    canPillar,
                    allowDigOnReplan);
            PathfindingResult fresh = finder.findPath();
            if (fresh.success()) {
                BotLog.path(pack.player(), "path_replan", "at_node", reason, "new_path_size", fresh.path().size());
                path = fresh.path();
                index = 1;
                subWalker = null;
                subMiner = null;
                digWalking = false;
                stuckTicks = 0;
                lastPos = null;
                replanTried = false;
                return ActionResult.IN_PROGRESS;
            }
            reason = reason + "; replan_failed: " + fresh.reason();
        }
        cleanup(pack);
        return ActionResult.failed(reason);
    }

    private void cleanup(ActionPack pack) {
        if (subMiner != null) {
            subMiner.abort(pack.player());
            subMiner = null;
        }
        subWalker = null;
        digWalking = false;
        pack.stopMovement();
    }

    private static Direction faceFromPlayer(ActionPack pack, BlockPos pos) {
        Direction raw = Direction.getApproximateNearest(pack.player().getEyePosition().subtract(pos.getCenter()));
        if (raw == Direction.UP || raw == Direction.DOWN) {
            double dx = pack.player().getX() - (pos.getX() + 0.5D);
            double dz = pack.player().getZ() - (pos.getZ() + 0.5D);
            if (Math.abs(dx) >= Math.abs(dz)) {
                return dx > 0.0D ? Direction.EAST : Direction.WEST;
            }
            return dz > 0.0D ? Direction.SOUTH : Direction.NORTH;
        }
        return raw;
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
