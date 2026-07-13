package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.action.BuildAction;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import java.util.LinkedList;
import java.util.OptionalInt;
import java.util.Queue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;

public final class EmergencyShelterTask extends AbstractTask {
    private final Queue<BlockPos> targets = new LinkedList<>();
    private int placed;

    @Override
    public String name() {
        return "shelter";
    }

    @Override
    public String describe() {
        return "Emergency shelter placed=" + placed + " remaining=" + targets.size();
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        int total = placed + targets.size();
        return total == 0 ? 0.0D : Math.min(0.95D, (double) placed / total);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        targets.clear();
        BlockPos feet = bot.blockPosition();
        BlockPos head = feet.above();
        targets.add(head.above());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            targets.add(feet.relative(direction));
            targets.add(head.relative(direction));
        }
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 120) {
            if (placed > 0) {
                complete();
            } else {
                fail("shelter_timeout");
            }
            return;
        }
        OptionalInt blockSlot = findShelterBlockSlot(bot);
        if (blockSlot.isEmpty()) {
            fail("missing shelter_block");
            return;
        }
        while (!targets.isEmpty() && !bot.serverLevel().getBlockState(targets.peek()).isAir()) {
            targets.poll();
        }
        if (targets.isEmpty()) {
            complete();
            return;
        }
        if (InventoryAction.equipFromSlot(bot, blockSlot.getAsInt()) < 0) {
            fail("cannot_equip_shelter_block");
            return;
        }
        ActionResult result = BuildAction.placeBlockAt(bot, targets.poll());
        if (result.isSuccess()) {
            placed++;
        }
        if (targets.isEmpty()) {
            complete();
        }
    }

    private static OptionalInt findShelterBlockSlot(AIPlayerEntity bot) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            var stack = inventory.items.get(slot);
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            if (stack.is(Items.TORCH) || blockItem.getBlock() instanceof BedBlock) {
                continue;
            }
            if (!blockItem.getBlock().defaultBlockState().isAir()) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    public static boolean hasShelterBlock(AIPlayerEntity bot) {
        return findShelterBlockSlot(bot).isPresent();
    }
}
