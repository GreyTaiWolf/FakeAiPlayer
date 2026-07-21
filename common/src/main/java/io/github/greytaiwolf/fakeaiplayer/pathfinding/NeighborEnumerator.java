package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class NeighborEnumerator {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH,
            Direction.EAST,
            Direction.SOUTH,
            Direction.WEST
    };

    private final boolean canPillar;
    private final boolean allowDig;
    private final TraversalPolicy policy;
    // 终点格:岩浆预检豁免用(终点贴岩浆由任务层封堵处理,不该让唯一入口无解)。P2 使用
    // predicate 保留多终点语义；旧单目标入口仍由 setPathGoal 适配。
    private Predicate<BlockPos> pathGoalPredicate = ignored -> false;

    public NeighborEnumerator() {
        this(false, true, TraversalPolicy.TASK_MUTATING_DRY);
    }

    // NAV-9:canPillar=true 时允许"垫方块上升"邻接(仅当 bot 背包有可放置方块时由 A* 传入)。
    public NeighborEnumerator(boolean canPillar) {
        this(canPillar, true, TraversalPolicy.TASK_MUTATING_DRY);
    }

    // NAV-OPT:allowDig=false 时**禁用 DIG_THROUGH 邻居**——只在空气格上做"纯步行"搜索。
    // 用于两阶段寻路的第一阶段:绝大多数移动靠纯步行即可达,搜索空间小(只空气格)、收敛快;
    // 而启用挖穿会把每个相邻实心方块都当邻居,使搜索退化成"3D 体积扩散",被困/地下时极易撑爆到
    // SEARCH_LIMIT(实测 5 格距离的 move 都 SEARCH_LIMIT 的机制根因)。纯步行无解再开第二阶段挖穿。
    public NeighborEnumerator(boolean canPillar, boolean allowDig) {
        this(canPillar, allowDig,
                canPillar || allowDig
                        ? TraversalPolicy.TASK_MUTATING_DRY
                        : TraversalPolicy.TASK_WALK_DRY);
    }

    public NeighborEnumerator(TraversalPolicy policy) {
        this(policy.allowsPillaring(), policy.allowsDigging(), policy);
    }

    public NeighborEnumerator(boolean canPillar,
                              boolean allowDig,
                              TraversalPolicy policy) {
        this.canPillar = canPillar;
        this.allowDig = allowDig;
        this.policy = policy;
    }

    public void setPathGoal(BlockPos goal) {
        this.pathGoalPredicate = goal == null ? ignored -> false : goal::equals;
    }

    public void setPathGoalPredicate(Predicate<BlockPos> goalPredicate) {
        this.pathGoalPredicate = goalPredicate == null ? ignored -> false : goalPredicate;
    }

    public List<NeighborCandidate> getNeighbors(BlockPos current, ServerLevel world) {
        return getNeighbors(current, world, false);
    }

    /**
     * Expands a search node while preserving the virtual effect of a preceding dig step.
     * A {@link MoveType#DIG_THROUGH} node represents a feet/head column that execution will clear;
     * without this distinction A* can enter the first wall block but can never leave it.
     */
    public List<NeighborCandidate> getNeighbors(Node current, ServerLevel world) {
        if (current == null) {
            return List.of();
        }
        return getNeighbors(
                current.pos(), world, current.moveType() == MoveType.DIG_THROUGH);
    }

    private List<NeighborCandidate> getNeighbors(BlockPos current,
                                                 ServerLevel world,
                                                 boolean currentColumnWillBeCleared) {
        List<NeighborCandidate> result = new ArrayList<>(HORIZONTAL.length);
        for (Direction direction : HORIZONTAL) {
            BlockPos target = current.relative(direction);
            if (Standability.isStandable(world, target, policy)
                    && (currentColumnWillBeCleared
                    || walkTransitionClear(world, current, target))) {
                result.add(new NeighborCandidate(target, MoveType.WALK, 0));
                continue;
            }

            BlockPos jumpTarget = target.above();
            if (canJumpOnto(world, current, target, currentColumnWillBeCleared)
                    && Standability.isStandable(world, jumpTarget, policy)) {
                result.add(new NeighborCandidate(jumpTarget, MoveType.JUMP_UP, 0));
                continue;
            }

            NeighborCandidate drop = findDrop(world, target);
            if (drop != null) {
                result.add(drop);
                continue;
            }

            if (allowDig && digEnterable(world, target)) {
                result.add(new NeighborCandidate(target, MoveType.DIG_THROUGH, 0));
            }
            // 斜上挖登(DIG 垂直分量之上行):目标=邻位高一格,挖开其脚头两格后跳进去。
            // 仅当自己头顶跳跃空间已空才生成(执行器只挖目标两格,不清自己头顶)——坡面/露天爬坡够用,
            // 全封闭竖井上行交给 pillar。治 geo_slope:坡体内矿(高 3 格)水平 DIG 永远够不到。
            BlockPos upTarget = target.above();
            if (allowDig && digEnterable(world, upTarget) && collisionEmpty(world, current.above(2))) {
                result.add(new NeighborCandidate(upTarget, MoveType.DIG_THROUGH, 0));
            }
        }
        // 垂直向下挖落(DIG 垂直分量之下行):挖开脚下一格掉下去站稳。治 geo_deep/埋矿族:
        // 矿在正下方若干格,水平 DIG 在本层泛洪永远够不到(实测 ore_dig_buried/deep 同源)。
        if (allowDig) {
            BlockPos below = current.below();
            if (isMineable(world, below) && !collisionEmpty(world, below.below())) {
                result.add(new NeighborCandidate(below, MoveType.DIG_THROUGH, 0));
            }
        }
        addDiagonals(current, world, result, currentColumnWillBeCleared);
        // A pillar entered directly from a virtual DIG column would stand in the old head block.
        // That block is clear at execution time but still solid in the search snapshot, so the
        // virtual state would have to survive beyond the PILLAR node. Keep that unsupported
        // transition out of the graph instead of merging it with an ordinary pillar state.
        if (!currentColumnWillBeCleared) {
            addPillar(current, world, result);
        }
        return result;
    }

    /** Package-safe transition probe for executors outside the pathfinding package. */
    public boolean hasTransition(BlockPos current,
                                 BlockPos expected,
                                 MoveType expectedMove,
                                 ServerLevel world) {
        return getNeighbors(current, world).stream()
                .anyMatch(candidate -> candidate.pos().equals(expected)
                        && candidate.moveType() == expectedMove);
    }

    // NAV-3:同高对角移动。仅当目标格可站、且两个正交相邻格都"可穿过"(不切墙角)时才允许。
    private void addDiagonals(BlockPos current,
                              ServerLevel world,
                              List<NeighborCandidate> result,
                              boolean currentColumnWillBeCleared) {
        Direction[][] pairs = {
                {Direction.NORTH, Direction.EAST},
                {Direction.NORTH, Direction.WEST},
                {Direction.SOUTH, Direction.EAST},
                {Direction.SOUTH, Direction.WEST}
        };
        for (Direction[] pair : pairs) {
            BlockPos diag = current.relative(pair[0]).relative(pair[1]);
            if (!Standability.isStandable(world, diag, policy)) {
                continue;
            }
            if (!passableColumn(world, diag)) {
                continue;
            }
            if (!passableColumn(world, current.relative(pair[0])) || !passableColumn(world, current.relative(pair[1]))) {
                continue;
            }
            if (!currentColumnWillBeCleared
                    && !walkTransitionClear(world, current, diag)) {
                continue;
            }
            result.add(new NeighborCandidate(diag, MoveType.DIAGONAL, 0));
        }
    }

    // NAV-9:垫方块上升一格(原地)。bot 会在脚下放方块并跳上去。需要头顶两格净空。
    private void addPillar(BlockPos current,
                           ServerLevel world,
                           List<NeighborCandidate> result) {
        if (!canPillar) {
            return;
        }
        BlockPos up1 = current.above();
        BlockPos up2 = current.above(2);
        // up1 = 新脚位(当前头位,应为空);up2 = 新头位,需净空
        if (collisionEmpty(world, up1)
                && collisionEmpty(world, up2)
                && !Standability.isDangerous(world.getBlockState(up1))) {
            result.add(new NeighborCandidate(up1, MoveType.PILLAR_UP, 0));
        }
    }

    private static boolean collisionEmpty(ServerLevel world, BlockPos pos) {
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private boolean passableColumn(ServerLevel world, BlockPos feet) {
        if (!collisionEmpty(world, feet) || !collisionEmpty(world, feet.above())) {
            return false;
        }
        return !policy.requiresDryPath()
                || (world.getFluidState(feet).isEmpty()
                && world.getFluidState(feet.above()).isEmpty());
    }

    private static boolean canJumpFrom(ServerLevel world, BlockPos current) {
        return collisionEmpty(world, current.above()) && collisionEmpty(world, current.above(2));
    }

    private static boolean canJumpOnto(ServerLevel world,
                                       BlockPos current,
                                       BlockPos front,
                                       boolean currentColumnWillBeCleared) {
        boolean jumpSpace = currentColumnWillBeCleared
                ? collisionEmpty(world, current.above(2))
                : canJumpFrom(world, current);
        if (!jumpSpace) {
            return false;
        }
        BlockState frontState = world.getBlockState(front);
        if (frontState.getCollisionShape(world, front).isEmpty()) {
            return false;
        }
        if (frontState.getCollisionShape(world, front).max(Direction.Axis.Y) > 1.0D) {
            return false;
        }
        return collisionEmpty(world, front.above()) && collisionEmpty(world, front.above(2));
    }

    private NeighborCandidate findDrop(ServerLevel world, BlockPos target) {
        if (!collisionEmpty(world, target)) {
            return null;
        }
        if (!collisionEmpty(world, target.above())) {
            return null;
        }
        int maxFall = AIBotConfig.get().nav().maxSafeFall();
        for (int fall = 1; fall <= maxFall; fall++) {
            BlockPos landing = target.below(fall);
            if (Standability.isStandable(world, landing, policy)) {
                return new NeighborCandidate(landing, MoveType.DROP_DOWN, fall);
            }
            if (!collisionEmpty(world, landing)) {
                return null;
            }
        }
        return null;
    }

    // DIG 可进入:脚位与头位各自"可挖 或 已通行"(但不全空——全空是 WALK/JUMP 的领域),
    // 且脚下有支撑(挖完站得住)。修"脚空头实"死角:终点=矿正下方时站位空气、头顶是矿,
    // 原 isMineable 要求脚位非空气 → 四种邻居全拒,goal 节点永不入队,A* 万格泛洪 TIMEOUT(geo_wall 实测)。
    private boolean digEnterable(ServerLevel world, BlockPos target) {
        BlockPos head = target.above();
        boolean footOpen = collisionEmpty(world, target);
        boolean headOpen = collisionEmpty(world, head);
        if (footOpen && headOpen) {
            return false;
        }
        boolean footOk = footOpen || isMineable(world, target);
        boolean headOk = headOpen || isMineable(world, head);
        if (!footOk || !headOk || collisionEmpty(world, target.below())) {
            return false;
        }
        // P0 安全预检(深层挖矿头号死因):挖开这两格后侧面/上方岩浆会涌入——-59 钻石层就是岩浆层,
        // 实操挖钻石最常见死法。脚/头任一格暴露面贴岩浆 → 这条路不挖,A* 自然绕行。
        boolean isGoal = pathGoalPredicate.test(target) || pathGoalPredicate.test(head);
        if (!isGoal && (adjacentLava(world, target) || adjacentLava(world, head))) {
            return false; // 终点格豁免:贴岩浆的矿仍可达,挖前由任务层先封岩浆(ore_dig_lava_seal)
        }
        // P0 沙砾坍塌预检:头位上方是悬沙/砾(FallingBlock)→ 挖开即连环下落,砸头窒息+填回通道。
        if (world.getBlockState(head.above()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock) {
            return false;
        }
        return true;
    }

    // 暴露面岩浆:四水平邻+上方任一岩浆即危险(下方由 target.down 实心保证不漏)。
    private static boolean adjacentLava(ServerLevel world, BlockPos pos) {
        if (world.getFluidState(pos.above()).is(net.minecraft.tags.FluidTags.LAVA)) {
            return true;
        }
        for (Direction d : HORIZONTAL) {
            if (world.getFluidState(pos.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasHeadroom(ServerLevel world, BlockPos target) {
        // 挖掘语义的头位:已空 或 可挖(执行器 tickDigThrough 会把脚位+头位都挖开)。
        // 原"头上两格必须已空"把穿实心山体判成无路——每一步头位都是石头,DIG 邻居一个都生成不出,
        // 这正是 geo_slope/wall/pocket 全卡 no_progress 的根因(挖掘寻路只能贴地刨坑、不能穿山)。
        BlockPos head = target.above();
        return collisionEmpty(world, head) || isMineable(world, head);
    }

    private static boolean walkTransitionClear(ServerLevel world, BlockPos from, BlockPos to) {
        AABB fromBox = playerBox(from);
        AABB toBox = playerBox(to);
        return world.noCollision(fromBox.minmax(toBox).deflate(1.0E-4D));
    }

    private static AABB playerBox(BlockPos feet) {
        double centerX = feet.getX() + 0.5D;
        double centerZ = feet.getZ() + 0.5D;
        return new AABB(
                centerX - 0.3D, feet.getY(), centerZ - 0.3D,
                centerX + 0.3D, feet.getY() + 1.8D, centerZ + 0.3D);
    }

    private static boolean isMineable(ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(world, pos) < 0.0F || world.getBlockEntity(pos) != null) {
            return false;
        }
        if (!state.getFluidState().isEmpty() || Standability.isDangerous(state)) {
            return false;
        }
        // 矿石本身可挖(终点豁免的配套:目标矿格要能进路径;OreScan 含模组 _ore 后缀)。
        if (io.github.greytaiwolf.fakeaiplayer.mining.OreScan.isOreBlock(state.getBlock())) {
            return true;
        }
        return state.is(BlockTags.STONE_ORE_REPLACEABLES)
                || state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || state.is(BlockTags.DIRT)
                || state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.GRANITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND)
                || state.is(Blocks.GRAVEL);
    }
}
