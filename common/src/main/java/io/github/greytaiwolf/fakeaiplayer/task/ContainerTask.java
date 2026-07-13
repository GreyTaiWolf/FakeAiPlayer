package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.action.ContainerAction;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.memory.BotMemoryStore;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ContainerTask extends AbstractTask {
    public enum Mode {
        DEPOSIT,
        WITHDRAW
    }

    private enum Phase {
        FINDING,
        WALKING,
        TRANSFERRING,
        DONE
    }

    private static final int SEARCH_RADIUS = 8;
    private static final double REACH_SQUARED = 20.25D;

    private final Mode mode;
    private final BlockPos requestedContainerPos;
    private final Item item;
    private final int targetCount;
    private final boolean allExceptTools;
    private Phase phase = Phase.FINDING;
    private BlockPos containerPos;
    private int transferred;
    private String doneReason = "";

    public static ContainerTask deposit(BlockPos containerPos, Item item, int count, boolean allExceptTools) {
        return new ContainerTask(Mode.DEPOSIT, containerPos, item, count, allExceptTools);
    }

    public static ContainerTask withdraw(BlockPos containerPos, Item item, int count) {
        return new ContainerTask(Mode.WITHDRAW, containerPos, item, count, false);
    }

    private ContainerTask(Mode mode, BlockPos containerPos, Item item, int count, boolean allExceptTools) {
        this.mode = mode;
        this.requestedContainerPos = containerPos == null ? null : containerPos.immutable();
        this.item = item;
        this.targetCount = count <= 0 ? Integer.MAX_VALUE : count;
        this.allExceptTools = allExceptTools;
    }

    @Override
    public String name() {
        return mode == Mode.DEPOSIT ? "deposit" : "withdraw";
    }

    @Override
    public String describe() {
        String target = item == null ? (allExceptTools ? "all_except_tools" : "all") : BuiltInRegistries.ITEM.getKey(item).toString();
        String count = targetCount == Integer.MAX_VALUE ? "all" : String.valueOf(targetCount);
        return "mode=" + mode + " target=" + target + " count=" + count + " transferred=" + transferred + " phase=" + phase;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (targetCount == Integer.MAX_VALUE) {
            return transferred > 0 ? 0.75D : 0.0D;
        }
        return Math.min(1.0D, (double) transferred / targetCount);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FINDING;
        transferred = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 1200) {
            fail("container_timeout");
            return;
        }
        switch (phase) {
            case FINDING -> findContainer(bot);
            case WALKING -> walkToContainer(bot);
            case TRANSFERRING -> transfer(bot);
            case DONE -> complete();
        }
    }

    private void findContainer(AIPlayerEntity bot) {
        containerPos = requestedContainerPos == null
                ? nearestContainer(bot, SEARCH_RADIUS).orElseGet(() -> rememberedContainer(bot).orElse(null))
                : requestedContainerPos;
        if (containerPos == null) {
            fail("no_container");
            return;
        }
        boolean observable = io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, containerPos);
        if (observable && ContainerAction.resolve(bot, containerPos).isEmpty()) {
            fail("no_container_at: " + shortPos(containerPos));
            return;
        }
        if (observable && bot.getEyePosition().distanceToSqr(containerPos.getCenter()) <= REACH_SQUARED) {
            phase = Phase.TRANSFERRING;
            return;
        }
        BlockPos stand = adjacentStand(bot, containerPos);
        if (stand == null) {
            fail("no_stand_position_for_container");
            return;
        }
        ActionResult result = bot.getActionPack().startPathTo(stand);
        if (result.isFailed()) {
            fail(result.reason());
            return;
        }
        phase = Phase.WALKING;
    }

    private void walkToContainer(AIPlayerEntity bot) {
        if (containerPos == null) {
            phase = Phase.FINDING;
            return;
        }
        boolean observable = io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, containerPos);
        if (observable && ContainerAction.resolve(bot, containerPos).isEmpty()) {
            phase = Phase.FINDING;
            return;
        }
        if (observable && bot.getEyePosition().distanceToSqr(containerPos.getCenter()) <= REACH_SQUARED) {
            bot.getActionPack().stopAll();
            phase = Phase.TRANSFERRING;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            phase = Phase.FINDING;
        }
    }

    private void transfer(AIPlayerEntity bot) {
        if (containerPos == null
                || bot.getEyePosition().distanceToSqr(containerPos.getCenter()) > REACH_SQUARED
                || !io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, containerPos)) {
            phase = Phase.FINDING;
            return;
        }
        Container container = ContainerAction.resolve(bot, containerPos).orElse(null);
        if (container == null) {
            fail("container_missing");
            return;
        }
        int remaining = targetCount == Integer.MAX_VALUE ? 64 : targetCount - transferred;
        if (remaining <= 0) {
            complete();
            return;
        }
        ContainerAction.TransferResult result = mode == Mode.DEPOSIT
                ? ContainerAction.depositOne(container, bot, depositFilter(), remaining)
                : ContainerAction.withdrawOne(container, bot, item, remaining);
        if (result.movedAny()) {
            transferred += result.count();
            if (targetCount != Integer.MAX_VALUE && transferred >= targetCount) {
                complete();
            }
            return;
        }
        doneReason = result.reason();
        if (mode == Mode.WITHDRAW && transferred < targetCount) {
            fail(doneReason.isBlank() ? "missing " + BuiltInRegistries.ITEM.getKey(item) + " x" + (targetCount - transferred) : doneReason);
            return;
        }
        if ("container_full".equals(doneReason) && transferred == 0) {
            fail(doneReason);
            return;
        }
        complete();
    }

    private Predicate<ItemStack> depositFilter() {
        if (item != null) {
            return stack -> stack.is(item);
        }
        if (allExceptTools) {
            return stack -> !ContainerAction.isReservedTool(stack);
        }
        return stack -> true;
    }

    public static Optional<BlockPos> nearestContainer(AIPlayerEntity bot, int radius) {
        return nearestContainerNear(bot, bot.blockPosition(), radius);
    }

    public static Optional<BlockPos> nearestContainerNear(AIPlayerEntity bot, BlockPos center, int radius) {
        BlockPos origin = bot.blockPosition();
        return BlockPos.betweenClosedStream(center.offset(-radius, -3, -radius), center.offset(radius, 4, radius))
                .filter(pos -> io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> ContainerAction.resolve(bot, pos).isPresent())
                .map(BlockPos::immutable)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
    }

    private static Optional<BlockPos> rememberedContainer(AIPlayerEntity bot) {
        return BotMemoryStore.INSTANCE.of(bot.getUUID())
                .placeIn(bot.serverLevel(), "depot", "home", "base", "chest")
                .flatMap(pos -> io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, pos)
                        && ContainerAction.resolve(bot, pos).isPresent()
                        ? Optional.of(pos.immutable())
                        : nearestContainerNear(bot, pos, 4));
    }

    private static BlockPos adjacentStand(AIPlayerEntity bot, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = pos.relative(direction);
            if (Standability.isStandable(bot.serverLevel(), candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
