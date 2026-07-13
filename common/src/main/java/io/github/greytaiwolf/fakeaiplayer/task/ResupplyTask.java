package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.action.ContainerAction;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.memory.BotMemoryStore;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ResupplyTask extends AbstractTask {
    private static final int BASE_RADIUS = 8;
    private static final double REACH_SQUARED = 20.25D;
    private static final double LOW_DURABILITY_FRACTION = 0.10D;

    public enum Need {
        TOOL,
        FOOD
    }

    private enum Phase {
        FIND_BASE,
        FIND_CONTAINER,
        GOTO_BASE,
        WALKING,
        WITHDRAWING,
        CRAFTING,
        EATING,
        DONE
    }

    private final Need need;
    private final Item requestedItem;
    private final List<BlockPos> containers = new ArrayList<>();
    private Phase phase = Phase.FIND_BASE;
    private BlockPos basePos;
    private BlockPos containerPos;
    private int containerIndex;
    private CraftTask craftTask;
    private EatTask eatTask;
    private String note = "";

    public static ResupplyTask tool(Item item) {
        return new ResupplyTask(Need.TOOL, item);
    }

    public static ResupplyTask food() {
        return new ResupplyTask(Need.FOOD, null);
    }

    private ResupplyTask(Need need, Item requestedItem) {
        this.need = need;
        this.requestedItem = requestedItem;
    }

    @Override
    public String name() {
        return "resupply";
    }

    @Override
    public String describe() {
        String target = requestedItem == null ? need.name().toLowerCase(java.util.Locale.ROOT) : BuiltInRegistries.ITEM.getKey(requestedItem).toString();
        return "Resupplying " + target + " phase=" + phase + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case FIND_BASE -> 0.0D;
            case FIND_CONTAINER -> 0.15D;
            case GOTO_BASE -> 0.25D;
            case WALKING -> 0.35D;
            case WITHDRAWING -> 0.55D;
            case CRAFTING -> 0.75D;
            case EATING -> 0.9D;
            case DONE -> 1.0D;
        };
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FIND_BASE;
        containers.clear();
        containerIndex = 0;
        craftTask = null;
        eatTask = null;
        note = "";
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 1800) {
            fail("resupply_timeout");
            return;
        }
        switch (phase) {
            case FIND_BASE -> findBase(bot);
            case FIND_CONTAINER -> findContainer(bot);
            case GOTO_BASE -> goToBase(bot);
            case WALKING -> walk(bot);
            case WITHDRAWING -> withdraw(bot);
            case CRAFTING -> craft(bot);
            case EATING -> eat(bot);
            case DONE -> complete();
        }
    }

    private void findBase(AIPlayerEntity bot) {
        if (alreadySatisfied(bot)) {
            phase = afterSupplyPhase(bot);
            return;
        }
        basePos = BotMemoryStore.INSTANCE.of(bot.getUUID())
                .placeIn(bot.serverLevel(), "base")
                .orElse(null);
        if (basePos == null) {
            // 没有基地(深处挖矿/野外远征):别死在 no_base——直接用背包料就地合(stone_pickaxe=圆石+棍+随身工作台;
            // iron_pickaxe=备用铁锭+棍)。深处磨穿镐时背包常有充足圆石/备料,合不出(缺料)CraftTask 自会 no_supply
            // 诚实失败。治 real_armor:挖26铁石镐磨穿→resupply→FIND_BASE→no_base 死锁(bot有78圆石+表却去找基地)。
            startCrafting(bot);
            return;
        }
        phase = Phase.FIND_CONTAINER;
    }

    private void findContainer(AIPlayerEntity bot) {
        containers.clear();
        BlockPos.betweenClosedStream(basePos.offset(-BASE_RADIUS, -3, -BASE_RADIUS), basePos.offset(BASE_RADIUS, 4, BASE_RADIUS))
                .map(BlockPos::immutable)
                .filter(pos -> io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> ContainerAction.resolve(bot, pos).isPresent())
                .forEach(containers::add);
        containers.sort(Comparator
                .comparing((BlockPos pos) -> !containsSupply(bot, pos))
                .thenComparingDouble(pos -> pos.distSqr(bot.blockPosition())));
        containerIndex = 0;
        if (containers.isEmpty()) {
            walkToBaseOrCraft(bot);
            return;
        }
        selectNextContainer(bot);
    }

    private void selectNextContainer(AIPlayerEntity bot) {
        if (alreadySatisfied(bot)) {
            phase = afterSupplyPhase(bot);
            return;
        }
        if (containerIndex >= containers.size()) {
            walkToBaseOrCraft(bot);
            return;
        }
        containerPos = containers.get(containerIndex++);
        if (bot.getEyePosition().distanceToSqr(containerPos.getCenter()) <= REACH_SQUARED) {
            phase = Phase.WITHDRAWING;
            return;
        }
        BlockPos stand = adjacentStand(bot, containerPos);
        if (stand == null) {
            selectNextContainer(bot);
            return;
        }
        ActionResult result = bot.getActionPack().startPathTo(stand);
        if (result.isFailed()) {
            note = result.reason();
            selectNextContainer(bot);
            return;
        }
        phase = Phase.WALKING;
    }

    private void walkToBaseOrCraft(AIPlayerEntity bot) {
        if (bot.getEyePosition().distanceToSqr(basePos.getCenter()) <= REACH_SQUARED) {
            startCrafting(bot);
            return;
        }
        BlockPos stand = adjacentStand(bot, basePos);
        if (stand == null) {
            stand = basePos;
        }
        ActionResult result = bot.getActionPack().startPathTo(stand);
        if (result.isFailed()) {
            note = result.reason();
            startCrafting(bot);
            return;
        }
        phase = Phase.GOTO_BASE;
    }

    private void goToBase(AIPlayerEntity bot) {
        if (bot.getEyePosition().distanceToSqr(basePos.getCenter()) <= REACH_SQUARED) {
            bot.getActionPack().stopAll();
            startCrafting(bot);
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            startCrafting(bot);
        }
    }

    private void walk(AIPlayerEntity bot) {
        if (containerPos == null || ContainerAction.resolve(bot, containerPos).isEmpty()) {
            phase = Phase.FIND_CONTAINER;
            return;
        }
        if (bot.getEyePosition().distanceToSqr(containerPos.getCenter()) <= REACH_SQUARED) {
            bot.getActionPack().stopAll();
            phase = Phase.WITHDRAWING;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            selectNextContainer(bot);
        }
    }

    private void withdraw(AIPlayerEntity bot) {
        if (containerPos == null
                || bot.getEyePosition().distanceToSqr(containerPos.getCenter()) > REACH_SQUARED
                || !io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, containerPos)) {
            phase = Phase.FIND_CONTAINER;
            return;
        }
        Container container = ContainerAction.resolve(bot, containerPos).orElse(null);
        if (container == null) {
            selectNextContainer(bot);
            return;
        }
        boolean moved = switch (need) {
            case TOOL -> withdrawTool(container, bot);
            case FOOD -> withdrawFood(container, bot);
        };
        if (moved) {
            phase = afterSupplyPhase(bot);
            return;
        }
        selectNextContainer(bot);
    }

    private boolean withdrawTool(Container container, AIPlayerEntity bot) {
        if (requestedItem == null) {
            return false;
        }
        ContainerAction.TransferResult result = ContainerAction.withdrawOne(container, bot, requestedItem, 1);
        if (!result.movedAny()) {
            note = result.reason();
            return false;
        }
        return equipUsableTool(bot);
    }

    private boolean withdrawFood(Container container, AIPlayerEntity bot) {
        Item food = firstFood(container);
        if (food == null) {
            return false;
        }
        ContainerAction.TransferResult result = ContainerAction.withdrawOne(container, bot, food, 16);
        if (!result.movedAny()) {
            note = result.reason();
            return false;
        }
        return InventoryAction.findFoodSlot(bot) >= 0;
    }

    private void startCrafting(AIPlayerEntity bot) {
        Item craftTarget = need == Need.FOOD ? Items.BREAD : requestedItem;
        if (craftTarget == null) {
            fail("no_supply");
            return;
        }
        int desiredCount = need == Need.TOOL ? InventoryAction.countItem(bot, craftTarget) + 1 : 1;
        craftTask = new CraftTask(craftTarget, desiredCount);
        craftTask.start(bot);
        phase = Phase.CRAFTING;
    }

    private void craft(AIPlayerEntity bot) {
        if (craftTask == null) {
            startCrafting(bot);
            return;
        }
        craftTask.tick(bot);
        if (craftTask.state() == TaskState.COMPLETED) {
            craftTask = null;
            if (need == Need.TOOL && !equipUsableTool(bot)) {
                fail("no_supply");
                return;
            }
            phase = afterSupplyPhase(bot);
            return;
        }
        if (craftTask.state() == TaskState.FAILED) {
            String reason = craftTask.failureReason();
            fail(reason == null || reason.isBlank() ? "no_supply" : "no_supply: " + reason);
        }
    }

    private void eat(AIPlayerEntity bot) {
        if (bot.getFoodData().getFoodLevel() >= 20) {
            phase = Phase.DONE;
            return;
        }
        if (eatTask == null) {
            if (InventoryAction.findFoodSlot(bot) < 0) {
                fail("no_supply");
                return;
            }
            eatTask = new EatTask();
            eatTask.start(bot);
        }
        eatTask.tick(bot);
        if (eatTask.state() == TaskState.COMPLETED) {
            phase = Phase.DONE;
            eatTask = null;
        } else if (eatTask.state() == TaskState.FAILED) {
            String reason = eatTask.failureReason();
            fail(reason == null || reason.isBlank() ? "no_supply" : reason);
        }
    }

    private Phase afterSupplyPhase(AIPlayerEntity bot) {
        if (need == Need.FOOD && bot.getFoodData().getFoodLevel() < 20) {
            return Phase.EATING;
        }
        return Phase.DONE;
    }

    private boolean alreadySatisfied(AIPlayerEntity bot) {
        return switch (need) {
            case TOOL -> equipUsableTool(bot);
            case FOOD -> InventoryAction.findFoodSlot(bot) >= 0;
        };
    }

    private boolean equipUsableTool(AIPlayerEntity bot) {
        if (requestedItem == null) {
            return false;
        }
        int bestSlot = -1;
        int bestRemaining = -1;
        for (int slot = 0; slot < bot.getInventory().items.size(); slot++) {
            ItemStack stack = bot.getInventory().items.get(slot);
            if (!stack.is(requestedItem) || !isUsable(stack)) {
                continue;
            }
            int remaining = stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
            if (remaining > bestRemaining) {
                bestRemaining = remaining;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) {
            return false;
        }
        InventoryAction.equipFromSlot(bot, bestSlot);
        return true;
    }

    private boolean containsSupply(AIPlayerEntity bot, BlockPos pos) {
        Container inventory = ContainerAction.resolve(bot, pos).orElse(null);
        if (inventory == null) {
            return false;
        }
        return switch (need) {
            case TOOL -> requestedItem != null && containsItem(inventory, requestedItem);
            case FOOD -> firstFood(inventory) != null;
        };
    }

    private static boolean containsItem(Container inventory, Item item) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    private static Item firstFood(Container inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.has(DataComponents.FOOD)) {
                return stack.getItem();
            }
        }
        return null;
    }

    private static boolean isUsable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (!stack.isDamageableItem()) {
            return true;
        }
        int max = stack.getMaxDamage();
        if (max <= 0) {
            return true;
        }
        return stack.getMaxDamage() - stack.getDamageValue() > max * LOW_DURABILITY_FRACTION;
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
}
