package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.action.BlockMiner;
import io.github.greytaiwolf.fakeaiplayer.action.FarmAction;
import io.github.greytaiwolf.fakeaiplayer.action.HarvestCore;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.mining.OreProspector;
import io.github.greytaiwolf.fakeaiplayer.mining.OreScan;
import io.github.greytaiwolf.fakeaiplayer.mining.ToolTier;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 造黑曜石(真实玩家"水浇岩浆现造"):循环 { 找岩浆源 → 站安全位 → 放水成黑曜石 → 挖 } 直到够数。
 * 自然黑曜石矿脉稀少,凑 ≥15 块只能现造。本项目放水是模拟(FarmAction.placeWater 直接 setBlockState),
 * 而源水碰源岩浆原版不转黑曜石(只流动水转)——故确定性落地:软放水(有水桶才放)+ 直接把岩浆源格
 * 写成 OBSIDIAN(1 tick 成型,与 placeWater/milkCow 同"模拟动作"风格),再用钻石镐挖。
 * 安全第一:只站"脚下实心、不挨岩浆/水"的格作业;够不到的岩浆源换下一个。自包含状态机,全程主线程。
 */
public final class CreateObsidianTask extends AbstractTask {
    private static final int MAX_ELAPSED = 12000;
    private static final int NO_PROGRESS_LIMIT = 600;
    private static final int SCAN_INTERVAL = 20;
    private static final int PROSPECT_RANGE = 48;
    private static final double REACH_SQUARED = 20.25D;
    private static final int PICKUP_GRACE_TICKS = 30;

    private enum Phase { SCAN, APPROACH, MAKE, MINE, DONE }

    private final int targetCount;
    private final BlockMiner miner = new BlockMiner();
    private int invBaseline;
    private int collected;
    private int lastProgressTick;
    private int lastScanTick = -SCAN_INTERVAL;
    private int approachTick;
    private int pickupGrace;
    private Phase phase = Phase.SCAN;
    private BlockPos lavaTarget;
    private BlockPos standPos;
    private BlockPos obsidian;

    public CreateObsidianTask(int targetCount) {
        this.targetCount = Math.max(1, targetCount);
    }

    @Override
    public String name() {
        return "create_obsidian";
    }

    @Override
    public String describe() {
        return "CreateObsidian " + collected + "/" + targetCount + " phase=" + phase;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, (double) collected / targetCount);
    }

    @Override
    public boolean isWaiting() {
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        invBaseline = HarvestCore.countInventoryItems(bot, Set.of(Items.OBSIDIAN));
        collected = 0;
        lastProgressTick = 0;
        phase = Phase.SCAN;
        lavaTarget = null;
        obsidian = null;
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            fail("create_obsidian_timeout collected=" + collected);
            return;
        }
        ServerLevel world = bot.serverLevel();
        if (!ToolTier.canHarvestWithInventory(bot, Blocks.OBSIDIAN.defaultBlockState())) {
            fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(Blocks.OBSIDIAN));
            return;
        }
        HarvestCore.forcePickupNearbyAnyOf(bot, Set.of(Items.OBSIDIAN), 4.0D, 4.0D);
        int total = Math.max(0, HarvestCore.countInventoryItems(bot, Set.of(Items.OBSIDIAN)) - invBaseline);
        if (total > collected) {
            collected = total;
            lastProgressTick = elapsed;
            BotLog.action(bot, "create_obsidian_collected", "total", collected + "/" + targetCount);
            io.github.greytaiwolf.fakeaiplayer.brain.BotReporter.INSTANCE.onGoalMessage(bot,
                    "造出黑曜石!" + collected + "/" + targetCount);
        }
        if (collected >= targetCount) {
            miner.cancel(bot);
            HarvestCore.sweepPickupAnyOf(bot, Set.of(Items.OBSIDIAN), 16);
            if (pickupGrace++ >= PICKUP_GRACE_TICKS
                    || HarvestCore.countInventoryItems(bot, Set.of(Items.OBSIDIAN)) - invBaseline >= targetCount) {
                complete();
            }
            return;
        }
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            BotLog.action(bot, "create_obsidian_stall", "phase", phase,
                    "collected", collected + "/" + targetCount);
            miner.cancel(bot);
            fail("create_obsidian_no_progress collected=" + collected);
            return;
        }
        switch (phase) {
            case SCAN -> scan(bot, world);
            case APPROACH -> approach(bot, world);
            case MAKE -> make(bot, world);
            case MINE -> mine(bot, world);
            case DONE -> { }
        }
    }

    private void scan(AIPlayerEntity bot, ServerLevel world) {
        int now = bot.getServer().getTickCount();
        if (now - lastScanTick < SCAN_INTERVAL) {
            return;
        }
        lastScanTick = now;
        BlockPos found = OreProspector.nearest(bot, PROSPECT_RANGE,
                st -> st.getFluidState().is(FluidTags.LAVA) && st.getFluidState().isSource());
        if (found == null) {
            fail("create_obsidian_no_lava collected=" + collected);
            return;
        }
        lavaTarget = found.immutable();
        standPos = pickStand(world, lavaTarget);
        if (standPos == null) {
            BotLog.action(bot, "create_obsidian_no_stand", "lava", lavaTarget.toShortString());
            // 这块够不到 → 当场封掉它(写成石头)防下轮再选到死循环,然后重扫。
            world.setBlock(lavaTarget, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            lavaTarget = null;
            return;
        }
        approachTick = elapsed;
        BotLog.action(bot, "create_obsidian_lava_found", "pos", lavaTarget.toShortString());
        phase = Phase.APPROACH;
    }

    private void approach(AIPlayerEntity bot, ServerLevel world) {
        if (lavaTarget == null || !isStillLava(world, lavaTarget)) {
            phase = Phase.SCAN;
            return;
        }
        if (bot.getEyePosition().distanceToSqr(lavaTarget.getCenter()) <= REACH_SQUARED
                && Standability.isStandable(world, bot.blockPosition())) {
            bot.getActionPack().stopAll();
            phase = Phase.MAKE;
            return;
        }
        if (elapsed - approachTick > 200) {
            BotLog.action(bot, "create_obsidian_approach_giveup", "lava", lavaTarget.toShortString());
            lavaTarget = null;
            standPos = null;
            phase = Phase.SCAN;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(standPos);
        }
    }

    private void make(AIPlayerEntity bot, ServerLevel world) {
        if (lavaTarget == null || !isStillLava(world, lavaTarget)) {
            phase = Phase.SCAN;
            return;
        }
        // 安全:岩浆源正上方若是流体 → 先封实(防造完邻流涌入冲走 bot/烧掉落)。
        BlockPos above = lavaTarget.above();
        if (!world.getFluidState(above).isEmpty()) {
            var blockSlot = io.github.greytaiwolf.fakeaiplayer.action.MaterialPalette.pickAnyBlockSlot(bot);
            if (blockSlot.isPresent()) {
                InventoryAction.equipFromSlot(bot, blockSlot.getAsInt());
                io.github.greytaiwolf.fakeaiplayer.action.BuildAction.placeBlockAt(bot, above);
            }
        }
        // 扣水桶(真实玩家每造一块耗一桶水;有水桶才扣,缺桶不阻断——放水仅记账,不实际放水块:
        // 实测放模拟水源会扩散把相邻源岩浆冲成圆石/黑曜石,把同带其它待造格吃掉,collected 缩水)。
        if (InventoryAction.countItem(bot, Items.WATER_BUCKET) > 0
                && InventoryAction.removeItems(bot, Items.WATER_BUCKET, 1)) {
            InventoryAction.giveItem(bot, new net.minecraft.world.item.ItemStack(Items.BUCKET, 1));
        }
        // 确定性成型:岩浆源格直接写成黑曜石(NOTIFY_LISTENERS 不触发邻格流体 update,保住相邻待造源)。
        world.setBlock(lavaTarget, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_CLIENTS);
        BotLog.action(bot, "create_obsidian_formed", "pos", lavaTarget.toShortString());
        lastProgressTick = elapsed;
        obsidian = lavaTarget;
        lavaTarget = null;
        phase = Phase.MINE;
    }

    private void mine(AIPlayerEntity bot, ServerLevel world) {
        if (obsidian == null || !world.getBlockState(obsidian).is(Blocks.OBSIDIAN)) {
            obsidian = null;
            phase = Phase.SCAN;
            return;
        }
        BlockMiner.Status st = miner.target() != null && miner.target().equals(obsidian)
                ? miner.tick(bot)
                : begin(bot, obsidian);
        if (st == BlockMiner.Status.DONE) {
            lastProgressTick = elapsed;
            HarvestCore.forcePickupNearbyAnyOf(bot, Set.of(Items.OBSIDIAN), 6.0D, 4.0D);
            obsidian = null;
            phase = Phase.SCAN;
        } else if (st == BlockMiner.Status.FAILED) {
            obsidian = null;
            phase = Phase.SCAN;
        }
    }

    private BlockMiner.Status begin(AIPlayerEntity bot, BlockPos pos) {
        bot.getActionPack().stopMovement();
        miner.begin(bot, pos);
        return miner.tick(bot);
    }

    private static boolean isStillLava(ServerLevel world, BlockPos pos) {
        var fs = world.getFluidState(pos);
        return fs.is(FluidTags.LAVA) && fs.isSource();
    }

    // 安全站位:造黑曜石是 setBlockState(服务端操作,无需贴脸),挖只需 4.5 reach——所以站**2 格外**:
    // 贴脸(1格)挨岩浆会被点着(guard_on_fire 实测),2 格外既够得到挖、又不着火。要求:站格可站/干燥、
    // 且站格自身 6 邻无任何流体(彻底离开火源)。先试 2 格、再退 3 格(更远更安全,仍在 4.5 reach 内)。
    private static BlockPos pickStand(ServerLevel world, BlockPos lava) {
        for (int dist = 2; dist <= 3; dist++) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos stand = lava.relative(d, dist);
                if (!Standability.isStandable(world, stand) || !world.getFluidState(stand).isEmpty()) {
                    continue;
                }
                if (anyAdjacentFluid(world, stand)) {
                    continue; // 站格挨任何岩浆/水 → 会着火/被冲,跳过
                }
                return stand.immutable();
            }
        }
        return null;
    }

    private static boolean anyAdjacentFluid(ServerLevel world, BlockPos stand) {
        for (Direction d : Direction.values()) {
            if (!world.getFluidState(stand.relative(d)).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
