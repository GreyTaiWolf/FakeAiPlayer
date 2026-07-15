package io.github.greytaiwolf.fakeaiplayer.action;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.log.LogCategory;
import io.github.greytaiwolf.fakeaiplayer.log.LogFields;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class WalkToController {
    private static final double ARRIVAL_THRESHOLD = 0.6D;
    private static final double PROGRESS_EPSILON = 0.04D;
    private static final double HARD_PROGRESS_EPSILON = 0.005D;
    private static final int MAX_TICKS = 160;
    private static final int SIDLE_STEP_TICKS = 8;

    private final Vec3 target;
    private final BlockPos requiredArrivalColumn;
    private Vec3 lastPos;
    private int noProgressTicks;
    private int hardStuckTicks;
    private int sidleTicks;
    private int elapsed;

    public WalkToController(Vec3 target) {
        this(target, null);
    }

    /**
     * @param requiredArrivalColumn optional final path column; when present, the ordinary
     *                              0.6-block tolerance cannot complete from an adjacent cell
     */
    public WalkToController(Vec3 target, BlockPos requiredArrivalColumn) {
        this.target = target;
        this.requiredArrivalColumn = requiredArrivalColumn == null
                ? null
                : requiredArrivalColumn.immutable();
    }

    public ActionResult tick(ActionPack pack) {
        elapsed++;
        if (elapsed > MAX_TICKS) {
            pack.stopMovement();
            return ActionResult.failed("timeout");
        }

        var player = pack.player();
        ServerLevel world = player.serverLevel();
        AIBotConfig.Nav nav = AIBotConfig.get().nav();
        Vec3 current = player.position();
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance <= ARRIVAL_THRESHOLD && hasReachedRequiredColumn(player.blockPosition())) {
            pack.stopMovement();
            return ActionResult.SUCCESS;
        }
        if (horizontalDistance < 1.0E-6D) {
            pack.stopMovement();
            return ActionResult.failed("arrival_column_height_mismatch");
        }

        Vec3 move = new Vec3(dx / horizontalDistance, 0.0D, dz / horizontalDistance);
        SidleCommand sidle = sidleCommand(move, nav);
        LookAction.lookHorizontallyAt(player, current.add(sidle.lookVector.scale(4.0D)));
        pack.setForward(1.0F);
        pack.setStrafing(sidle.strafing);

        JumpDecision jump = shouldJump(current, move, world, nav);
        // 拟人化:只在"已落地 + 前方确有台阶/缺口"时点跳一次(单跳),绝不长按跳键。
        // 旧实现 setJumping(jump.jump) 会在障碍持续存在的多 tick 里一直按住跳——bot 落地即连跳(兔子跳),
        // 既不像正常玩家,跳跃还会拉低水平速度(实测"边跳边砍树、影响速度")。落地门控确保一台阶只跳一次。
        if (jump.jump && player.onGround()) {
            pack.jumpOnce();
        }
        pack.setJumping(false);
        pack.setSprinting(shouldSprint(horizontalDistance, jump, current, move, world, nav));

        if (lastPos != null && current.distanceTo(lastPos) < PROGRESS_EPSILON) {
            noProgressTicks++;
        } else {
            noProgressTicks = 0;
            sidleTicks = 0;
        }
        if (lastPos != null && current.distanceTo(lastPos) < HARD_PROGRESS_EPSILON) {
            hardStuckTicks++;
        } else {
            hardStuckTicks = 0;
        }
        lastPos = current;

        boolean sidling = noProgressTicks >= nav.sidleAfter();
        if (hardStuckTicks > nav.hardLimit() && !sidling) {
            pack.stopMovement();
            logStuck(pack, "hard", current, move, world);
            return ActionResult.failed("stuck_hard");
        }
        if (sidling) {
            sidleTicks++;
        }
        if (sidleTicks > nav.sidleLimit()) {
            pack.stopMovement();
            logStuck(pack, "blocked", current, move, world);
            return ActionResult.failed("stuck_blocked");
        }
        return ActionResult.IN_PROGRESS;
    }

    private boolean hasReachedRequiredColumn(BlockPos current) {
        return requiredArrivalColumn == null
                || current.getX() == requiredArrivalColumn.getX()
                        && current.getZ() == requiredArrivalColumn.getZ()
                        && Math.abs(current.getY() - requiredArrivalColumn.getY()) <= 1;
    }

    private SidleCommand sidleCommand(Vec3 move, AIBotConfig.Nav nav) {
        if (noProgressTicks < nav.sidleAfter()) {
            return new SidleCommand(move, 0.0F);
        }
        int step = Math.floorMod(sidleTicks / SIDLE_STEP_TICKS, 4);
        return switch (step) {
            case 0 -> new SidleCommand(rotate(move, 35.0D), 1.0F);
            case 1 -> new SidleCommand(rotate(move, -35.0D), -1.0F);
            case 2 -> new SidleCommand(rotate(move, 60.0D), 0.7F);
            default -> new SidleCommand(rotate(move, -60.0D), -0.7F);
        };
    }

    private static JumpDecision shouldJump(Vec3 current, Vec3 move, ServerLevel world, AIBotConfig.Nav nav) {
        BlockPos front = footPos(current, move, nav.jumpReach());
        BlockState frontState = world.getBlockState(front);
        BlockState aboveFront = world.getBlockState(front.above());
        BlockPos playerPos = BlockPos.containing(current);
        BlockState abovePlayer = world.getBlockState(playerPos.above());
        boolean headClear = isClear(world, front.above()) && isClear(world, playerPos.above());

        if (hasCollision(frontState, world, front)) {
            double top = collisionTop(frontState, world, front);
            if (top <= 1.0D && headClear) {
                return new JumpDecision(true, false, false);
            }
            return new JumpDecision(false, true, false);
        }

        if (isGapAhead(current, move, world) && isClear(world, abovePlayer, playerPos.above())) {
            return new JumpDecision(true, false, true);
        }
        return new JumpDecision(false, false, false);
    }

    private static boolean isGapAhead(Vec3 current, Vec3 move, ServerLevel world) {
        BlockPos near = footPos(current, move, 1.35D);
        if (!isClear(world, near) || !isClear(world, near.above()) || !isClear(world, near.below())) {
            return false;
        }
        BlockPos landing = footPos(current, move, 2.1D);
        return isClear(world, landing)
                && isClear(world, landing.above())
                && hasCollision(world.getBlockState(landing.below()), world, landing.below());
    }

    private static boolean shouldSprint(double horizontalDistance, JumpDecision jump, Vec3 current, Vec3 move, ServerLevel world, AIBotConfig.Nav nav) {
        if (horizontalDistance < nav.sprintMinDist()) {
            return false;
        }
        if (jump.blocked || (jump.jump && !jump.gap)) {
            return false;
        }
        return clearAhead(current, move, world, 1.0D) && clearAhead(current, move, world, 2.0D);
    }

    private static boolean clearAhead(Vec3 current, Vec3 move, ServerLevel world, double distance) {
        BlockPos pos = footPos(current, move, distance);
        return isClear(world, pos) && isClear(world, pos.above());
    }

    private static boolean isClear(ServerLevel world, BlockPos pos) {
        return isClear(world, world.getBlockState(pos), pos);
    }

    private static boolean isClear(ServerLevel world, BlockState state, BlockPos pos) {
        return state.getCollisionShape(world, pos).isEmpty();
    }

    private static boolean hasCollision(BlockState state, ServerLevel world, BlockPos pos) {
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    private static double collisionTop(BlockState state, ServerLevel world, BlockPos pos) {
        if (!hasCollision(state, world, pos)) {
            return 0.0D;
        }
        return state.getCollisionShape(world, pos).max(Direction.Axis.Y);
    }

    private static BlockPos footPos(Vec3 current, Vec3 move, double distance) {
        return BlockPos.containing(current.x + move.x * distance, current.y, current.z + move.z * distance);
    }

    private static Vec3 rotate(Vec3 move, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(move.x * cos - move.z * sin, 0.0D, move.x * sin + move.z * cos);
    }

    private static void logStuck(ActionPack pack, String reason, Vec3 current, Vec3 move, ServerLevel world) {
        BlockPos front = footPos(current, move, 1.0D);
        BlockState state = world.getBlockState(front);
        BotLog.warn(LogCategory.PATH, pack.player(), "walk_stuck",
                "reason", reason,
                "front", LogFields.pos(front),
                "front_block", BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                "yaw", Math.round(pack.player().getYRot()),
                "target", String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", current.x + move.x, current.y, current.z + move.z));
    }

    private record SidleCommand(Vec3 lookVector, float strafing) {
    }

    private record JumpDecision(boolean jump, boolean blocked, boolean gap) {
    }
}
