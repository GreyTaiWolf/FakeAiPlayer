package io.github.greytaiwolf.fakeaiplayer.task;

import com.mojang.datafixers.util.Either;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.memory.BotMemoryStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import java.util.OptionalInt;

public final class SleepTask extends AbstractTask {
    private enum Phase {
        FIND_BED,
        PLACE_BED,
        WALK_TO_BED,
        SLEEP,
        WAIT_MORNING
    }

    private Phase phase = Phase.FIND_BED;
    private BlockPos bedPos;
    private BlockPos standPos;
    private int sleepWaitTicks;

    @Override
    public String name() {
        return "sleep";
    }

    @Override
    public String describe() {
        return "Sleeping phase=" + phase + " bed=" + (bedPos == null ? "(pending)" : compact(bedPos));
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case FIND_BED -> 0.05D;
            case PLACE_BED -> 0.2D;
            case WALK_TO_BED -> 0.45D;
            case SLEEP -> 0.7D;
            case WAIT_MORNING -> Math.min(0.95D, 0.7D + elapsed / 2400.0D);
        };
    }

    @Override
    public boolean isWaiting() {
        return phase == Phase.WAIT_MORNING;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FIND_BED;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 3000) {
            fail("sleep_timeout");
            return;
        }
        switch (phase) {
            case FIND_BED -> findBed(bot);
            case PLACE_BED -> placeBed(bot);
            case WALK_TO_BED -> walkToBed(bot);
            case SLEEP -> sleep(bot);
            case WAIT_MORNING -> waitMorning(bot);
        }
    }

    private void findBed(AIPlayerEntity bot) {
        bedPos = findNearbyBed(bot, 8);
        if (bedPos == null) {
            bedPos = rememberedBed(bot);
        }
        if (bedPos != null) {
            standPos = adjacentStandPos(bot, bedPos);
            phase = Phase.WALK_TO_BED;
            return;
        }
        if (findBedItemSlot(bot).isEmpty()) {
            fail("no_bed");
            return;
        }
        phase = Phase.PLACE_BED;
    }

    private void placeBed(AIPlayerEntity bot) {
        OptionalInt bedSlot = findBedItemSlot(bot);
        if (bedSlot.isEmpty()) {
            fail("no_bed");
            return;
        }
        BedPlacement placement = chooseBedPlacement(bot);
        if (placement == null) {
            fail("no_place_for_bed");
            return;
        }
        int hotbarSlot = InventoryAction.equipFromSlot(bot, bedSlot.getAsInt());
        if (hotbarSlot < 0) {
            fail("cannot_equip_bed");
            return;
        }
        ItemStack stack = bot.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof BedBlock bedBlock)) {
            fail("selected_item_not_bed");
            return;
        }
        ServerLevel world = bot.serverLevel();
        BlockState foot = bedBlock.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, placement.facing())
                .setValue(BedBlock.PART, BedPart.FOOT);
        BlockState head = bedBlock.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, placement.facing())
                .setValue(BedBlock.PART, BedPart.HEAD);
        world.setBlock(placement.foot(), foot, Block.UPDATE_ALL);
        world.setBlock(placement.head(), head, Block.UPDATE_ALL);
        if (!bot.getAbilities().instabuild) {
            stack.shrink(1);
        }
        bedPos = placement.foot();
        standPos = adjacentStandPos(bot, bedPos);
        phase = Phase.WALK_TO_BED;
    }

    private void walkToBed(AIPlayerEntity bot) {
        if (bedPos == null || !(bot.serverLevel().getBlockState(bedPos).getBlock() instanceof BedBlock)) {
            phase = Phase.FIND_BED;
            return;
        }
        if (bot.getEyePosition().distanceTo(bedPos.getCenter()) <= 4.5D) {
            bot.getActionPack().stopAll();
            phase = Phase.SLEEP;
            return;
        }
        if (standPos == null) {
            standPos = adjacentStandPos(bot, bedPos);
        }
        if (standPos == null) {
            fail("bed_not_reachable");
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(standPos);
        }
    }

    private void sleep(AIPlayerEntity bot) {
        Either<Player.BedSleepingProblem, Unit> result = bot.startSleepInBed(bedPos);
        if (result.left().isPresent()) {
            fail("sleep_failed:" + result.left().get().name().toLowerCase());
            return;
        }
        sleepWaitTicks = 0;
        phase = Phase.WAIT_MORNING;
    }

    private void waitMorning(AIPlayerEntity bot) {
        sleepWaitTicks++;
        if (bot.serverLevel().isDay()) {
            if (bot.isSleeping()) {
                bot.stopSleeping();
            }
            complete();
            return;
        }
        if (sleepWaitTicks > 1200) {
            if (bot.isSleeping()) {
                bot.stopSleeping();
            }
            fail("sleep_quorum_not_met");
        }
    }

    private static BlockPos findNearbyBed(AIPlayerEntity bot, int radius) {
        return findBedNear(bot, bot.blockPosition(), radius);
    }

    private static BlockPos findBedNear(AIPlayerEntity bot, BlockPos center, int radius) {
        BlockPos origin = bot.blockPosition();
        return BlockPos.betweenClosedStream(center.offset(-radius, -3, -radius), center.offset(radius, 3, radius))
                .map(BlockPos::immutable)
                .filter(pos -> io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> bot.serverLevel().getBlockState(pos).getBlock() instanceof BedBlock)
                .min((left, right) -> Double.compare(left.distSqr(origin), right.distSqr(origin)))
                .orElse(null);
    }

    public static boolean hasBedAccess(AIPlayerEntity bot) {
        return findNearbyBed(bot, 8) != null || rememberedBed(bot) != null || findBedItemSlot(bot).isPresent();
    }

    private static BlockPos rememberedBed(AIPlayerEntity bot) {
        return BotMemoryStore.INSTANCE.of(bot.getUUID())
                .placeIn(bot.serverLevel(), "bed", "home", "base")
                .map(pos -> io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, pos)
                        && bot.serverLevel().getBlockState(pos).getBlock() instanceof BedBlock
                        ? pos.immutable()
                        : findBedNear(bot, pos, 4))
                .orElse(null);
    }

    private static OptionalInt findBedItemSlot(AIPlayerEntity bot) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof BedBlock) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    private static BedPlacement chooseBedPlacement(AIPlayerEntity bot) {
        BlockPos origin = bot.blockPosition();
        Direction first = bot.getDirection();
        for (Direction facing : orderedDirections(first)) {
            for (BlockPos foot : BlockPos.betweenClosed(origin.offset(-2, -1, -2), origin.offset(2, 1, 2))) {
                BlockPos footPos = foot.immutable();
                BlockPos headPos = footPos.relative(facing);
                if (canPlaceBedAt(bot, footPos, headPos) && adjacentStandPos(bot, footPos) != null) {
                    return new BedPlacement(footPos, headPos, facing);
                }
            }
        }
        return null;
    }

    private static boolean canPlaceBedAt(AIPlayerEntity bot, BlockPos foot, BlockPos head) {
        ServerLevel world = bot.serverLevel();
        BlockPos botFeet = bot.blockPosition();
        if (foot.equals(botFeet) || foot.equals(botFeet.above()) || head.equals(botFeet) || head.equals(botFeet.above())) {
            return false;
        }
        return world.getBlockState(foot).isAir()
                && world.getBlockState(head).isAir()
                && !world.getBlockState(foot.below()).isAir()
                && !world.getBlockState(head.below()).isAir();
    }

    private static Direction[] orderedDirections(Direction first) {
        return switch (first) {
            case NORTH -> new Direction[]{Direction.NORTH, Direction.EAST, Direction.WEST, Direction.SOUTH};
            case SOUTH -> new Direction[]{Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.NORTH};
            case EAST -> new Direction[]{Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.WEST};
            case WEST -> new Direction[]{Direction.WEST, Direction.NORTH, Direction.SOUTH, Direction.EAST};
            default -> new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        };
    }

    private static BlockPos adjacentStandPos(AIPlayerEntity bot, BlockPos target) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = target.relative(direction);
            if (io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability.isStandable(bot.serverLevel(), candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private record BedPlacement(BlockPos foot, BlockPos head, Direction facing) {
    }
}
