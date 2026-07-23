package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventorySessionManager;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import io.github.greytaiwolf.fakeaiplayer.runtime.PauseOwner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SAFE-1 / NAV-12:执行期环境安全网。每 tick 在所有其它检查之前运行,只在"真正致命地形"
 * (溺水 / 站在岩浆里或将陷入 / 高坠落即将砸地)时即时接管 ActionPack 自救,脱险后让出控制。
 *
 * 设计要点:
 * - 在 BotTickCoordinator 的每 bot 循环最前面调用;返回 true 表示本 tick 已接管,后续 watcher 跳过。
 * - 它在 TaskManager.tickAll(任务驱动 ActionPack)之后运行(见 FakeAiPlayer tick 顺序),
 *   因此可以在危险 tick 覆盖任务设置的移动输入,危险解除后任务输入照常生效。
 * - 不调用 stopAll(不杀任务),只覆盖本 tick 的 forward/jump/yaw。
 */
public final class NavSafetyNet {
    public static final NavSafetyNet INSTANCE = new NavSafetyNet();

    private static final int AIR_SURFACE_THRESHOLD = 120; // 满 300;低于此且在水下→上浮换气
    private static final int EMERGENCY_AIR = 60;           // 低于此且上浮无望→紧急传送到可呼吸落点
    private static final int BREATHE_SCAN_UP = 5;          // 头顶向上找空气的格数
    private static final int RESCUE_RADIUS_H = 16;
    private static final int RESCUE_RADIUS_V = 16;
    private static final int SUFFOCATION_CLIMB_UP = 24;   // 窒息脱困优先垂直向上钻出的最大格数(地表方向)
    private final Map<UUID, Integer> nextLogTick = new ConcurrentHashMap<>();
    private final Set<UUID> taskPauseLeases = ConcurrentHashMap.newKeySet();
    // SAFE-DROWN2:水危机接管标志。旧逻辑只在 submerged&&air<阈值 的 tick 接管(jump 一口气),
    // bot 露头 air 回升就放手 → 任务的移动输入又把它怼回水里 → 反复半淹、hp 被溺水伤害磨光
    //(实测 hunt roam 路过湖:30s 内 navsafe 触发 12 次仍 drowned)。改:一旦触发即置危机态,
    // 持续接管(jump+朝最近可呼吸岸点游)直到脚踩实地才释放——自救的目标是"上岸",不是"换口气"。
    private final Map<UUID, BlockPos> waterRescueShore = new ConcurrentHashMap<>();
    // SAFE-DROWN3:水危机开始 tick。游向岸点可能永远到不了(岸壁 2 格高跳不上去/被流推回,
    // 实测平原湖远征 hp 2.2 仍 drowned)——危机持续超时就放弃体面,直接紧急传送上岸保命。
    private final Map<UUID, Integer> waterRescueSince = new ConcurrentHashMap<>();
    private static final int WATER_RESCUE_TELEPORT_AFTER = 200; // 10s 还没脱水 → 强制传送

    private NavSafetyNet() {
    }

    public void clear(AIPlayerEntity bot) {
        UUID id = bot.getUUID();
        bot.getActionPack().resumeControllers(PauseOwner.SAFETY);
        TaskManager.INSTANCE.releaseNavigationSafetyLease(bot);
        nextLogTick.remove(id);
        waterRescueShore.remove(id);
        waterRescueSince.remove(id);
        taskPauseLeases.remove(id);
    }

    public void clearAll() {
        nextLogTick.clear();
        waterRescueShore.clear();
        waterRescueSince.clear();
        taskPauseLeases.clear();
    }

    public boolean tickBot(MinecraftServer server, AIPlayerEntity bot) {
        if (!bot.isAlive()) {
            return false;
        }
        ServerLevel world = bot.serverLevel();
        BlockPos feet = bot.blockPosition();

        // 0) 窒息/卡方块:脚位或头位有实体碰撞体时,优先**向上**钻出地表(修"越救越深")。
        if (blockedColumn(world, feet)) {
            acquireTaskPauseLease(bot, "suffocation_escape");
            if (escapeSuffocation(bot, world, feet)) {
                throttledLog(server, bot, "navsafe_suffocation_snap", feet);
                return true;
            }
        }

        // 1) 岩浆:站在岩浆里 / 脚下是岩浆 → 立即逃离(最高优先级)
        if (inLava(world, feet) || inLava(world, feet.below())) {
            acquireTaskPauseLease(bot, "lava_escape");
            escapeLava(bot, world, feet);
            throttledLog(server, bot, "navsafe_lava_escape", feet);
            return true;
        }

        // 2) 溺水/水危机:触发后持续接管到上岸(见 waterRescueShore 注释)。
        boolean inCrisis = waterRescueShore.containsKey(bot.getUUID());
        boolean lowAir = bot.isUnderWater() && bot.getAirSupply() < AIR_SURFACE_THRESHOLD;
        boolean accidentalWaterEntry = bot.isInWater() && !bot.getActionPack().hasWaterCapablePath();
        if (!inCrisis && (accidentalWaterEntry || lowAir)) {
            inCrisis = true; // 新触发
        }
        if (inCrisis) {
            acquireTaskPauseLease(bot, "water_rescue");
            // Cancel only navigation. The owning task remains available to recover after the bot
            // reaches dry ground, and repeated unsafe submissions are cancelled on later ticks.
            bot.getActionPack().cancelActivePathForSafety("water_rescue");
            // 释放条件:脚踩实地且不在水里 → 危机解除,交还控制。
            if (!bot.isInWater() && bot.onGround()) {
                waterRescueShore.remove(bot.getUUID());
                waterRescueSince.remove(bot.getUUID());
                releaseTaskPauseLease(bot);
                return false;
            }
            int now = server.getTickCount();
            Integer since = waterRescueSince.putIfAbsent(bot.getUUID(), now);
            // SAFE-DROWN:空气危急且头顶无空气可上浮(被石头封顶的水兜)→ 紧急传送到最近可呼吸落点。
            // SAFE-DROWN3:或者危机拖太久(游向岸点到不了:岸壁高/水流推)→ 同样强制传送保命。
            boolean rescueTimedOut = since != null && now - since > WATER_RESCUE_TELEPORT_AFTER;
            if (rescueTimedOut || (bot.getAirSupply() <= EMERGENCY_AIR && !breathableAbove(world, feet))) {
                if (emergencyTeleportToAir(bot, world, feet)) {
                    waterRescueShore.remove(bot.getUUID());
                    waterRescueSince.remove(bot.getUUID());
                    releaseTaskPauseLease(bot);
                    throttledLog(server, bot, "navsafe_drown_teleport", feet);
                    // Safety no longer owns movement after the teleport. Let GoalExecutor consume
                    // the latched Mission interruption in this same coordinator tick, before the
                    // resumed Task can receive another TaskManager tick.
                    return false;
                }
            }
            // 岸点:缓存的还有效就用,否则重找(最近"可站+脚头都是空气"的落点)。
            BlockPos shore = waterRescueShore.get(bot.getUUID());
            if (shore == null || !Standability.isStandable(world, shore)) {
                shore = findNearestBreathableStandable(world, feet).orElse(null);
            }
            if (shore != null) {
                waterRescueShore.put(bot.getUUID(), shore.immutable());
                double yaw = Math.toDegrees(Math.atan2(
                        -(shore.getX() + 0.5D - bot.getX()), shore.getZ() + 0.5D - bot.getZ()));
                bot.setYRot((float) yaw);
                bot.setYHeadRot((float) yaw);
                bot.setYBodyRot((float) yaw);
                bot.getActionPack().setForward(1.0F); // 朝岸游
            } else {
                waterRescueShore.put(bot.getUUID(), feet.immutable()); // 无岸点(开阔深水):占位保持危机态,先上浮
                bot.getActionPack().setForward(0.0F);
            }
            bot.getActionPack().setSprinting(false);
            bot.getActionPack().setJumping(true); // 水中持续 jump = 上浮/游泳
            throttledLog(server, bot, "navsafe_surface_for_air", feet);
            return true;
        }

        if (!blockedColumn(world, bot.blockPosition())
                && !inLava(world, bot.blockPosition())
                && !inLava(world, bot.blockPosition().below())) {
            releaseTaskPauseLease(bot);
        }
        return false;
    }

    /**
     * 窒息脱困:优先**垂直向上**找第一个可站点(地表方向)并传送上去。
     * 修"越救越深"——旧实现用 snapPlayerToNearestStandable 找欧氏最近可站点,bot 被埋时最近点
     * 往往在下方/侧下方,反复 snap 把 bot 一格格往坑里拽(实测 994 列 64→63→62→61 困死)。
     * 向上钻出是被埋的正解(Standability.isStandable 已保证落点脚位+头位空气、脚下有支撑=能站能呼吸);
     * 向上 SUFFOCATION_CLIMB_UP 格内无解(深埋封顶)才回退到全向最近可站点,至少脱离当前窒息格。
     */
    private boolean escapeSuffocation(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        // 缓存必脏:走到"被埋"这一步说明方块刚变过(塌方/活埋场景 setBlockState),Standability 缓存
        // 还是变更前的世界——拿旧值判"向上无可站点"会走 fallback 全向 snap,把 bot 拽进远处洞里
        //(实测平原活埋:向上 2 格明明可站,却被 snap 到 y20 黑洞再触发保命传送,场景 aborted)。
        Standability.clearCache();
        int top = world.getMinY() + world.getHeight();
        for (int dy = 1; dy <= SUFFOCATION_CLIMB_UP && feet.getY() + dy < top - 1; dy++) {
            BlockPos candidate = feet.above(dy);
            if (Standability.isStandable(world, candidate)) {
                boolean moved = io.github.greytaiwolf.fakeaiplayer.mode.CapabilityRuntime.run(
                        bot, io.github.greytaiwolf.fakeaiplayer.mode.PrivilegedCapability.EMERGENCY_TELEPORT,
                        "navsafe_suffocation", () -> {
                            bot.getActionPack().stopAll();
                            bot.teleportTo(world, candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D,
                                    Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
                        });
                if (moved) {
                    Standability.clearCache();
                }
                return moved;
            }
        }
        // 向上无解(深埋/封顶)→ 回退原逻辑:全向最近可站点。
        return bot.getActionPack().snapPlayerToNearestStandable("navsafe_suffocation");
    }

    // 头顶 BREATHE_SCAN_UP 格内是否能露头呼吸(遇到非水的可通过格=能呼吸;遇到实体方块顶盖=封死)
    private static boolean breathableAbove(ServerLevel world, BlockPos feet) {
        for (int dy = 1; dy <= BREATHE_SCAN_UP; dy++) {
            BlockPos p = feet.above(dy);
            BlockState s = world.getBlockState(p);
            boolean water = s.getFluidState().is(FluidTags.WATER);
            boolean solid = !s.getCollisionShape(world, p).isEmpty();
            if (!water && !solid) {
                return true;   // 非水的空气格 → 上浮能呼吸
            }
            if (solid) {
                return false;  // 撞到实体方块顶盖,上浮无望
            }
        }
        return false;
    }

    private boolean emergencyTeleportToAir(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        Optional<BlockPos> safe = findNearestBreathableStandable(world, feet);
        if (safe.isEmpty()) {
            return false;
        }
        BlockPos to = safe.get();
        boolean moved = io.github.greytaiwolf.fakeaiplayer.mode.CapabilityRuntime.run(
                bot, io.github.greytaiwolf.fakeaiplayer.mode.PrivilegedCapability.EMERGENCY_TELEPORT,
                "navsafe_drowning", () -> {
                    bot.getActionPack().stopAll();
                    bot.teleportTo(world, to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D,
                            Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
                });
        if (moved) {
            Standability.clearCache();
        }
        return moved;
    }

    // 最近的"可站 + 脚位与头位都是空气(可呼吸,不是水)"落点
    private static Optional<BlockPos> findNearestBreathableStandable(ServerLevel world, BlockPos origin) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -RESCUE_RADIUS_H; dx <= RESCUE_RADIUS_H; dx++) {
            for (int dz = -RESCUE_RADIUS_H; dz <= RESCUE_RADIUS_H; dz++) {
                for (int dy = -RESCUE_RADIUS_V; dy <= RESCUE_RADIUS_V; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!Standability.isStandable(world, cursor)) {
                        continue;
                    }
                    if (!world.getBlockState(cursor).isAir() || !world.getBlockState(cursor.above()).isAir()) {
                        continue;   // 脚位或头位是水/方块 → 不可呼吸
                    }
                    double distance = cursor.distSqr(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static void escapeLava(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        // 朝最近的"安全可站"水平方向冲出 + 起跳
        Direction best = null;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(dir);
            if (!inLava(world, side) && !inLava(world, side.below())
                    && io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability.isStandable(world, side)) {
                best = dir;
                break;
            }
        }
        if (best != null) {
            double yaw = Math.toDegrees(Math.atan2(-best.getStepX(), best.getStepZ()));
            bot.setYRot((float) yaw);
            bot.setYHeadRot((float) yaw);
            bot.setYBodyRot((float) yaw);
            bot.getActionPack().setForward(1.0F);
        }
        // 无论是否找到方向,起跳脱离岩浆体
        bot.getActionPack().setJumping(true);
        bot.getActionPack().jumpOnce();
    }

    private static boolean inLava(ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getFluidState().is(FluidTags.LAVA);
    }

    private static boolean blockedColumn(ServerLevel world, BlockPos feet) {
        return hasCollision(world, feet) || hasCollision(world, feet.above());
    }

    private static boolean hasCollision(ServerLevel world, BlockPos pos) {
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private void acquireTaskPauseLease(AIPlayerEntity bot, String reason) {
        BotInventorySessionManager.INSTANCE.closeForSafety(bot, reason);
        bot.getActionPack().freezeControllers(PauseOwner.SAFETY);
        TaskManager.INSTANCE.acquireNavigationSafetyLease(bot, reason);
        UUID id = bot.getUUID();
        Optional<Task> active = TaskManager.INSTANCE.getActive(bot);
        if (taskPauseLeases.contains(id)
                || active.isEmpty()
                || ("lava_escape".equals(reason) && active.get() instanceof LavaEscapeTask)) {
            return;
        }
        if (TaskManager.INSTANCE.pauseFor(bot, PauseOwner.SAFETY, "nav_safety:" + reason)) {
            taskPauseLeases.add(id);
        }
    }

    private void releaseTaskPauseLease(AIPlayerEntity bot) {
        UUID id = bot.getUUID();
        bot.getActionPack().resumeControllers(PauseOwner.SAFETY);
        TaskManager.INSTANCE.releaseNavigationSafetyLease(bot);
        if (!taskPauseLeases.contains(id)) {
            return;
        }
        TaskManager.INSTANCE.resumeSafetyPause(bot);
        // A USER/INVENTORY lock may intentionally keep the frame on the stack. This lease still
        // ends here; releasing that persistent owner later performs the permitted resume.
        taskPauseLeases.remove(id);
    }

    private void throttledLog(MinecraftServer server, AIPlayerEntity bot, String event, BlockPos pos) {
        int now = server.getTickCount();
        if (now < nextLogTick.getOrDefault(bot.getUUID(), 0)) {
            return;
        }
        nextLogTick.put(bot.getUUID(), now + 40);
        BotLog.danger(bot, event,
                "pos", pos.getX() + "," + pos.getY() + "," + pos.getZ(),
                "air", bot.getAirSupply(),
                "hp", String.format(java.util.Locale.ROOT, "%.1f", bot.getHealth()));
    }
}
