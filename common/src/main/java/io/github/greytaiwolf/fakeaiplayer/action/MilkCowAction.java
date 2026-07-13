package io.github.greytaiwolf.fakeaiplayer.action;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import java.util.Comparator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 挤奶:用一个空桶在最近的成年牛身上挤一桶奶。简化:不模拟右键交互,直接换物 + 日志(与 till/placeWater 同风格)。 */
public final class MilkCowAction {
    public static final double REACH = 4.0D;

    private MilkCowAction() {
    }

    public static Cow nearestCow(AIPlayerEntity bot, double radius) {
        ServerLevel world = bot.serverLevel();
        return world.getEntitiesOfClass(Cow.class, bot.getBoundingBox().inflate(radius),
                        cow -> cow.isAlive() && !cow.isBaby())
                .stream()
                .filter(cow -> io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveEntity(bot, cow))
                .min(Comparator.comparingDouble(bot::distanceToSqr))
                .orElse(null);
    }

    public static ActionResult milk(AIPlayerEntity bot) {
        if (InventoryAction.countItem(bot, Items.BUCKET) <= 0) {
            return ActionResult.failed("missing_bucket");
        }
        Cow cow = nearestCow(bot, REACH);
        if (cow == null) {
            return ActionResult.failed("no_cow_in_range");
        }
        if (!InventoryAction.removeItems(bot, Items.BUCKET, 1)) {
            return ActionResult.failed("missing_bucket");
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.MILK_BUCKET, 1));
        BotLog.action(bot, "milk_cow", "pos", cow.blockPosition());
        return ActionResult.SUCCESS;
    }
}
