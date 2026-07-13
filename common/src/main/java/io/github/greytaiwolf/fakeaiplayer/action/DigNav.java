package io.github.greytaiwolf.fakeaiplayer.action;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;

/**
 * 挖掘式导航:当纯寻路(A*)走不通(被墙 / 复杂地形 / 自挖隧道 / SEARCH_LIMIT)时,朝目标"挖一格走一格"硬开一条路。
 *
 * 这是"AI 玩家手里有镐,被挡住就该挖开走过去"这一本该有的能力——根治反复出现的"被困出不去"
 *(实测:丛林 + 自挖隧道里 move 寻路 SEARCH_LIMIT,bot 卡死、只能靠大脑一格格手动 mine_block 直到耗尽轮次)。
 *
 * 纯函数 {@link #stepToward}(朝目标的下一格)+ 有状态 {@link #digStep}(调用方持有 {@link BlockMiner});全程主线程(G2)。
 */
public final class DigNav {
    private DigNav() {
    }

    /**
     * 朝 target 挖掘式前进一格:清出朝向格(脚位+头位)→ 已通则走进去(更低则主动下沉,bot 无被动重力)。
     * 返回 true=本 tick 有进展(在挖或已迈步);false=该方向受阻(如相邻岩浆),调用方应改道或失败。
     */
    public static boolean digStep(AIPlayerEntity bot, BlockMiner miner, BlockPos target) {
        ServerLevel world = bot.serverLevel();
        BlockPos feet = bot.blockPosition();
        BlockPos step = stepToward(feet, target);
        if (step == null) {
            return false;
        }
        if (adjacentLava(world, step)) {
            return false; // 朝向格挨着岩浆 → 不挖,交还调用方
        }
        BlockPos solid = firstSolid(world, step, step.above());
        if (solid == null) {
            // 朝向格已是空气 → 迈进去(更低则下沉,平/高则走)。
            miner.cancel(bot);
            if (step.getY() < feet.getY()) {
                bot.getActionPack().descendInto(step);
            } else {
                bot.getActionPack().startWalkTo(step.getCenter());
            }
            return true;
        }
        BlockMiner.Status st = miner.target() != null && miner.target().equals(solid)
                ? miner.tick(bot)
                : begin(bot, miner, solid);
        return st == BlockMiner.Status.DONE || st == BlockMiner.Status.MINING;
    }

    private static BlockMiner.Status begin(AIPlayerEntity bot, BlockMiner miner, BlockPos pos) {
        miner.begin(bot, pos);
        return miner.tick(bot);
    }

    /** 朝目标的下一格:竖直优先(目标更低且水平已对齐则下挖),否则较大水平分量(避免对角穿墙角)。 */
    public static BlockPos stepToward(BlockPos from, BlockPos target) {
        int dy = target.getY() - from.getY();
        int dx = target.getX() - from.getX();
        int dz = target.getZ() - from.getZ();
        if (dy < 0 && Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
            return from.below();
        }
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return from.relative(dx > 0 ? Direction.EAST : Direction.WEST);
        }
        if (dz != 0) {
            return from.relative(dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }
        if (dy < 0) {
            return from.below();
        }
        if (dy > 0) {
            return from.above();
        }
        return null;
    }

    // a、b 中第一个需要挖开的(非空气)方块。
    private static BlockPos firstSolid(ServerLevel world, BlockPos a, BlockPos b) {
        if (!world.getBlockState(a).isAir()) {
            return a.immutable();
        }
        if (!world.getBlockState(b).isAir()) {
            return b.immutable();
        }
        return null;
    }

    private static boolean adjacentLava(ServerLevel world, BlockPos pos) {
        if (world.getBlockState(pos).getFluidState().is(FluidTags.LAVA)) {
            return true;
        }
        for (Direction d : Direction.values()) {
            if (world.getBlockState(pos.relative(d)).getFluidState().is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }
}
