package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * One resumable work unit for the player-like sequence "return to my mine, then continue mining".
 * Keeping both phases under one TaskManager assignment prevents the mining phase from overwriting
 * its own return journey. OreDigTask measures drops from its start baseline, so count is a delta.
 */
public final class ResumeMiningTask extends AbstractTask {
    private static final double MINE_FACE_TOLERANCE_SQUARED = 16.0D;

    private enum Phase {
        RETURNING,
        MINING
    }

    private final BlockPos mineFace;
    private final String dimension;
    private final Set<Block> ores;
    private final int count;
    private Phase phase = Phase.RETURNING;
    private Task child;

    public ResumeMiningTask(BlockPos mineFace, String dimension, Set<Block> ores, int count) {
        if (mineFace == null || dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("resume_mining_location_missing");
        }
        if (ores == null || ores.isEmpty()) {
            throw new IllegalArgumentException("resume_mining_ores_missing");
        }
        if (count < 1 || count > 64) {
            throw new IllegalArgumentException("resume_mining_count_out_of_range");
        }
        this.mineFace = mineFace.immutable();
        this.dimension = dimension.trim();
        this.ores = Set.copyOf(ores);
        this.count = count;
    }

    @Override
    public String name() {
        return "resume_mining";
    }

    @Override
    public String describe() {
        return phase == Phase.RETURNING
                ? "Returning to mine face " + compact(mineFace)
                : "Continuing mine " + childDescription();
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        double childProgress = child == null ? 0.0D : child.progress();
        return phase == Phase.RETURNING
                ? Math.min(0.45D, childProgress * 0.45D)
                : Math.min(0.99D, 0.5D + childProgress * 0.49D);
    }

    @Override
    public boolean isWaiting() {
        return child != null && child.isWaiting();
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (!inExpectedDimension(bot)) {
            fail("resume_mining_wrong_dimension:" + currentDimension(bot));
            return;
        }
        phase = Phase.RETURNING;
        child = MoveTask.nonMutating(bot, mineFace);
        child.start(bot);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (!inExpectedDimension(bot)) {
            stopChild(bot, "resume_mining_dimension_changed");
            fail("resume_mining_wrong_dimension:" + currentDimension(bot));
            return;
        }
        if (child == null) {
            fail("resume_mining_child_missing:" + phase);
            return;
        }
        if (child.state() == TaskState.RUNNING) {
            child.tick(bot);
        }
        if (child.state() == TaskState.COMPLETED) {
            if (phase == Phase.RETURNING) {
                if (bot.blockPosition().distSqr(mineFace) > MINE_FACE_TOLERANCE_SQUARED) {
                    bot.getActionPack().stopAll();
                    fail("return_to_mine_failed:mine_face_not_reached");
                    return;
                }
                phase = Phase.MINING;
                child = new OreDigTask(ores, count);
                child.start(bot);
            } else {
                complete();
            }
            return;
        }
        if (child.state() == TaskState.FAILED || child.state() == TaskState.CANCELLED) {
            String prefix = phase == Phase.RETURNING
                    ? "return_to_mine_failed:" : "resume_mine_failed:";
            String reason = child.failureReason() == null || child.failureReason().isBlank()
                    ? child.state().name().toLowerCase(java.util.Locale.ROOT)
                    : child.failureReason();
            bot.getActionPack().stopAll();
            fail(prefix + reason);
        }
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        if (child != null) {
            child.pause(bot);
        }
        super.onPause(bot);
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        if (!inExpectedDimension(bot)) {
            stopChild(bot, "resume_mining_dimension_changed");
            fail("resume_mining_wrong_dimension:" + currentDimension(bot));
            return;
        }
        if (child != null) {
            child.resume(bot);
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        stopChild(bot, "parent_resume_mining_stopped");
    }

    private void stopChild(AIPlayerEntity bot, String reason) {
        if (child != null) {
            child.cancel(bot, reason);
        }
        bot.getActionPack().stopAll();
    }

    private boolean inExpectedDimension(AIPlayerEntity bot) {
        return dimension.equals(currentDimension(bot));
    }

    private static String currentDimension(AIPlayerEntity bot) {
        return bot.serverLevel().dimension().location().toString();
    }

    private String childDescription() {
        return child == null ? "pending" : child.describe();
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
