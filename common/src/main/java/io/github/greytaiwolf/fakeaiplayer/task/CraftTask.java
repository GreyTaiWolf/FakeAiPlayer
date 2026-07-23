package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.action.BuildAction;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.craft.CraftingHelper;
import io.github.greytaiwolf.fakeaiplayer.craft.RecipeRegistry;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class CraftTask extends AbstractTask {
    private enum Phase {
        PLANNING,
        ENSURING_TABLE,
        CRAFTING
    }

    private final Item target;
    private final int targetCount;
    private Phase phase = Phase.PLANNING;
    private CraftingHelper.CraftPlan plan;
    private int nextStep;
    private int craftedCount;

    public CraftTask(Item target, int targetCount) {
        this.target = target;
        this.targetCount = Math.max(1, targetCount);
    }

    @Override
    public String name() {
        return "craft";
    }

    @Override
    public String describe() {
        return "Crafting " + BuiltInRegistries.ITEM.getKey(target) + " x" + targetCount + " phase=" + phase;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (plan == null || plan.steps().isEmpty()) {
            return 0.0D;
        }
        return Math.min(0.95D, (double) nextStep / plan.steps().size());
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.PLANNING;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 400) {
            fail("craft_timeout");
            return;
        }
        switch (phase) {
            case PLANNING -> plan(bot);
            case ENSURING_TABLE -> ensureTable(bot);
            case CRAFTING -> craftNext(bot);
        }
    }

    private void plan(AIPlayerEntity bot) {
        // 幂等短路:工作台/熔炉这类功能方块,若附近已有(够得着)或背包已有,直接完成,不浪费材料重复制造。
        if (utilityAlreadyAvailable(bot, target, targetCount)) {
            BotLog.action(bot, "craft_skipped_already_available", "item", BuiltInRegistries.ITEM.getKey(target).toString());
            complete();
            return;
        }
        plan = CraftingHelper.plan(bot, target, targetCount);
        if (!plan.success()) {
            fail("need: " + plan.missingDescription());
            return;
        }
        if (plan.needsCraftingTable() && nearbyCraftingTable(bot) == null && InventoryAction.findItem(bot, Items.CRAFTING_TABLE).isEmpty()) {
            CraftingHelper.CraftPlan tablePlan = CraftingHelper.plan(bot, Items.CRAFTING_TABLE, 1);
            if (!tablePlan.success()) {
                fail("need: minecraft:crafting_table x1 (" + tablePlan.missingDescription() + ")");
                return;
            }
            List<CraftingHelper.CraftStep> steps = new ArrayList<>(tablePlan.steps());
            steps.addAll(plan.steps());
            plan = new CraftingHelper.CraftPlan(target, targetCount, List.copyOf(steps), plan.missing(), true);
        }
        if (plan.steps().isEmpty()) {
            complete();
            return;
        }
        phase = Phase.CRAFTING;
    }

    private void ensureTable(AIPlayerEntity bot) {
        if (nearbyCraftingTable(bot) != null) {
            phase = Phase.CRAFTING;
            return;
        }
        OptionalInt tableSlot = InventoryAction.findItem(bot, Items.CRAFTING_TABLE);
        if (tableSlot.isEmpty()) {
            fail("need: minecraft:crafting_table x1");
            return;
        }
        BlockPos placePos = adjacentAir(bot);
        if (placePos == null) {
            fail("no_place_for_crafting_table");
            return;
        }
        InventoryAction.equipFromSlot(bot, tableSlot.getAsInt());
        ActionResult result = BuildAction.placeBlockAt(bot, placePos);
        if (result.isFailed()) {
            fail("place_crafting_table_failed: " + result.reason());
        }
    }

    private void craftNext(AIPlayerEntity bot) {
        if (nextStep >= plan.steps().size()) {
            complete();
            return;
        }
        CraftingHelper.CraftStep step = plan.steps().get(nextStep);
        RecipeRegistry.Recipe recipe = step.recipe();
        // 工作台"持有即可":附近有工作台方块 或 背包里有工作台物品,都允许 3x3 合成(直接背包变换)。
        // 避免"放下→走远→找不到→重造"的循环,也不在世界里留一地工作台。
        if (recipe.needsCraftingTable()
                && nearbyCraftingTable(bot) == null
                && InventoryAction.findItem(bot, Items.CRAFTING_TABLE).isEmpty()) {
            phase = Phase.ENSURING_TABLE;
            return;
        }
        for (RecipeRegistry.Ingredient ingredient : recipe.ingredients()) {
            if (!removeIngredient(bot, ingredient, ingredient.count() * step.crafts())) {
                fail("need: " + describeIngredient(ingredient, ingredient.count() * step.crafts()));
                return;
            }
        }
        ActionResult result = InventoryAction.giveItem(bot, new ItemStack(recipe.output(), step.outputCount()));
        if (result.isFailed()) {
            fail(result.reason());
            return;
        }
        if (recipe.output() == target) {
            craftedCount += step.outputCount();
        }
        nextStep++;
    }

    private static boolean removeIngredient(AIPlayerEntity bot, RecipeRegistry.Ingredient ingredient, int count) {
        int total = 0;
        for (Item item : ingredient.anyOf()) {
            total += InventoryAction.countItem(bot, item);
        }
        if (total < count) {
            return false;
        }
        int remaining = count;
        for (Item item : ingredient.anyOf()) {
            if (remaining <= 0) {
                return true;
            }
            int take = Math.min(remaining, InventoryAction.countItem(bot, item));
            if (take > 0 && !InventoryAction.removeItems(bot, item, take)) {
                return false;
            }
            remaining -= take;
        }
        return remaining == 0;
    }

    private static BlockPos nearbyCraftingTable(AIPlayerEntity bot) {
        BlockPos origin = bot.blockPosition();
        return BlockPos.betweenClosedStream(origin.offset(-8, -2, -8), origin.offset(8, 3, 8))
                .filter(pos -> io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> bot.serverLevel().getBlockState(pos).is(Blocks.CRAFTING_TABLE))
                .map(BlockPos::immutable)
                .findFirst()
                .orElse(null);
    }

    private static BlockPos adjacentAir(AIPlayerEntity bot) {
        BlockPos origin = bot.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = origin.relative(direction);
            if (bot.serverLevel().getBlockState(candidate).isAir()) {
                return candidate.immutable();
            }
        }
        BlockPos above = origin.above();
        return bot.serverLevel().getBlockState(above).isAir() ? above.immutable() : null;
    }

    private static String describeIngredient(RecipeRegistry.Ingredient ingredient, int count) {
        List<String> ids = ingredient.anyOf().stream()
                .map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
                .toList();
        return String.join("|", ids) + " x" + count;
    }

    /**
     * Shared Task/Mission definition of a crafted utility being available. A world block can
     * satisfy only a single-unit utility request; larger crafting quotas still require inventory.
     */
    public static boolean utilityAlreadyAvailable(AIPlayerEntity bot, Item target, int targetCount) {
        int required = Math.max(1, targetCount);
        int inventoryCount = InventoryAction.countItem(bot, target);
        boolean nearbyUtility = false;
        if (target == Items.CRAFTING_TABLE) {
            nearbyUtility = nearbyCraftingTable(bot) != null;
        } else if (target == Items.FURNACE) {
            nearbyUtility = nearbyBlock(bot, Blocks.FURNACE) != null;
        }
        return utilityRequestSatisfied(inventoryCount, required, nearbyUtility);
    }

    static boolean utilityRequestSatisfied(int inventoryCount,
                                           int targetCount,
                                           boolean nearbyUtility) {
        int required = Math.max(1, targetCount);
        return Math.max(0, inventoryCount) >= required
                || required == 1 && nearbyUtility;
    }

    private static BlockPos nearbyBlock(AIPlayerEntity bot, net.minecraft.world.level.block.Block block) {
        BlockPos origin = bot.blockPosition();
        return BlockPos.betweenClosedStream(origin.offset(-8, -2, -8), origin.offset(8, 3, 8))
                .filter(pos -> io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> bot.serverLevel().getBlockState(pos).is(block))
                .map(BlockPos::immutable)
                .findFirst()
                .orElse(null);
    }
}
