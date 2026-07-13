package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.action.BuildAction;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.action.LookAction;
import io.github.greytaiwolf.fakeaiplayer.action.MaterialPalette;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * 入浆即自救(P0):bot 身陷岩浆时由 DangerWatcher 派发,把它从岩浆里拉出来。
 *
 * 历史病灶:SurvivalGuard 检测到 in_lava 只中断作业任务、注释写"让位 DangerWatcher 脱困/灭火"——但脱困
 * 从未实现。bot 泡在岩浆里被持续烧死(实测 real_diamond 下潜 Y-58 挖穿岩浆袋,hp 20→16→…→死,
 * 14/15 步功亏一篑)。本任务补上这一环。
 *
 * 策略(拟人逃浆):①每 tick 按住跳=在岩浆里缓慢上浮,头露出岩浆面少挨烧、也便于踏上岸沿;
 * ②朝最近的非岩浆落脚点(岸)走出去;③够不到岸时在脚边垫一格方块当踏台,跳上去脱浆。
 * 逃出岩浆即完成。SurvivalGuard 豁免本任务(否则 guard_in_lava 会把自救本身打断)。
 */
public final class LavaEscapeTask extends AbstractTask {
    private static final int ESCAPE_RADIUS = 5;   // 找岸的水平半径
    private static final int MAX_ELAPSED = 200;   // 10s 还没脱浆 → 失败(下一 tick DangerWatcher 会重派,继续试)
    private static final int PLACE_INTERVAL = 4;  // 垫方块限频

    private BlockPos target;
    private int lastPlaceTick = -100;

    @Override
    public String name() {
        return "lava_escape";
    }

    @Override
    public String describe() {
        return "Lava escape -> " + (target == null ? "(scan)" : target.toShortString());
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.9D, elapsed / 40.0D);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        bot.getActionPack().stopAll(); // 丢下当前作业的路径/挖掘,专心逃浆
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        // 脱浆即成功:已出岩浆且站稳即完成(出浆但未落地再等一拍,避免岸边一脚踩空又掉回)。
        if (!bot.isInLava()) {
            bot.getActionPack().setJumping(false);
            bot.getActionPack().setForward(0.0F);
            if (bot.onGround()) {
                bot.getActionPack().stopAll();
                BotLog.action(bot, "lava_escape_done", "pos", bot.blockPosition().toShortString());
                complete();
            }
            return;
        }
        if (elapsed > MAX_ELAPSED) {
            fail("lava_escape_timeout");
            return;
        }

        var world = bot.serverLevel();
        // 持续上浮:岩浆里按住跳缓慢上升。
        bot.getActionPack().setJumping(true);

        // 找/复用最近的非岩浆落脚点(到达或失效则重扫)。
        if (target == null
                || bot.blockPosition().closerThan(target, 1.6D)
                || !Standability.isStandable(world, target)) {
            Optional<BlockPos> bank = Standability.findNearestStandable(world, bot.blockPosition(), ESCAPE_RADIUS, 2, 3);
            target = bank.orElse(null);
        }

        if (target != null) {
            // 朝岸沿冲:看向它 + 直线走过去(短距逃浆用 walk,不寻路——A* 穿岩浆会失败)。
            LookAction.lookAt(bot, Vec3.atCenterOf(target));
            if (bot.getActionPack().isWalkToIdle()) {
                bot.getActionPack().startWalkTo(Vec3.atCenterOf(target));
            }
            return;
        }

        // 四周 5 格都没岸 → 垫方块自救:在脚边水平放一格当踏台(替换流动岩浆),跳上去脱浆。
        if (elapsed - lastPlaceTick >= PLACE_INTERVAL) {
            lastPlaceTick = elapsed;
            OptionalInt slot = MaterialPalette.pickAnyBlockSlot(bot);
            if (slot.isPresent()) {
                InventoryAction.equipFromSlot(bot, slot.getAsInt());
                BlockPos feet = bot.blockPosition();
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos side = feet.relative(dir);
                    if (!BuildAction.placeBlockAt(bot, side).isFailed()) {
                        target = side; // 造好踏台 → 朝它爬上去
                        BotLog.action(bot, "lava_escape_platform", "at", side.toShortString());
                        break;
                    }
                }
            }
        }
    }
}
