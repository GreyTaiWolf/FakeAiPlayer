package io.github.greytaiwolf.fakeaiplayer.action;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class FarmAction {
    private FarmAction() {
    }

    public static ActionResult till(AIPlayerEntity bot, BlockPos ground) {
        ServerLevel world = bot.serverLevel();
        if (!isTillable(world.getBlockState(ground)) || !world.getBlockState(ground.above()).isAir()) {
            return ActionResult.failed("not_tillable");
        }
        OptionalInt hoeSlot = findHoeSlot(bot);
        if (hoeSlot.isEmpty()) {
            return ActionResult.failed("missing_hoe");
        }
        InventoryAction.equipFromSlot(bot, hoeSlot.getAsInt());
        world.setBlock(ground, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
        BotLog.action(bot, "till", "pos", ground);
        return ActionResult.SUCCESS;
    }

    public static ActionResult plant(AIPlayerEntity bot, BlockPos farmland, Item seed, Block crop) {
        ServerLevel world = bot.serverLevel();
        if (!world.getBlockState(farmland).is(Blocks.FARMLAND) || !world.getBlockState(farmland.above()).isAir()) {
            return ActionResult.failed("not_empty_farmland");
        }
        if (!InventoryAction.removeItems(bot, seed, 1)) {
            return ActionResult.failed("missing " + seed + " x1");
        }
        world.setBlock(farmland.above(), crop.defaultBlockState(), Block.UPDATE_ALL);
        BotLog.action(bot, "plant", "pos", farmland.above(), "seed", seed, "crop", crop);
        return ActionResult.SUCCESS;
    }

    public static boolean isMature(ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof CropBlock cropBlock && cropBlock.isMaxAge(state);
    }

    public static ActionResult harvest(AIPlayerEntity bot, BlockPos cropPos) {
        ServerLevel world = bot.serverLevel();
        if (!isMature(world, cropPos)) {
            return ActionResult.failed("not_mature");
        }
        world.destroyBlock(cropPos, true, bot);
        BotLog.action(bot, "harvest", "pos", cropPos);
        return ActionResult.SUCCESS;
    }

    // 灌溉:用水桶在 pos 放一个水源(简化:直接 setBlockState WATER 源 + 背包 WATER_BUCKET→BUCKET)。
    public static ActionResult placeWater(AIPlayerEntity bot, BlockPos pos) {
        ServerLevel world = bot.serverLevel();
        BlockState at = world.getBlockState(pos);
        if (!at.isAir() && !at.is(Blocks.WATER) && world.getFluidState(pos).isEmpty()) {
            return ActionResult.failed("not_empty"); // 目标被实心方块占,放不了水
        }
        if (!InventoryAction.removeItems(bot, Items.WATER_BUCKET, 1)) {
            return ActionResult.failed("missing_water_bucket");
        }
        world.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET, 1));
        BotLog.action(bot, "place_water", "pos", pos);
        return ActionResult.SUCCESS;
    }

    // 灌溉:从 pos 的水源舀水进空桶(无限水源的"可再生"凭此验证:舀走一格,邻格的源会回填)。
    public static ActionResult fillBucket(AIPlayerEntity bot, BlockPos pos) {
        ServerLevel world = bot.serverLevel();
        if (!isWaterSource(world, pos)) {
            return ActionResult.failed("not_water_source");
        }
        if (!InventoryAction.removeItems(bot, Items.BUCKET, 1)) {
            return ActionResult.failed("missing_bucket");
        }
        world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.WATER_BUCKET, 1));
        BotLog.action(bot, "fill_bucket", "pos", pos);
        return ActionResult.SUCCESS;
    }

    public static boolean isWaterSource(ServerLevel world, BlockPos pos) {
        net.minecraft.world.level.material.FluidState fluid = world.getFluidState(pos);
        return fluid.is(net.minecraft.tags.FluidTags.WATER) && fluid.isSource();
    }

    public static CropSpec cropSpec(String cropName) {
        return switch (cropName) {
            case "wheat", "minecraft:wheat" -> new CropSpec(Items.WHEAT_SEEDS, Blocks.WHEAT, "wheat");
            case "carrot", "carrots", "minecraft:carrot", "minecraft:carrots" -> new CropSpec(Items.CARROT, Blocks.CARROTS, "carrot");
            case "potato", "potatoes", "minecraft:potato", "minecraft:potatoes" -> new CropSpec(Items.POTATO, Blocks.POTATOES, "potato");
            default -> throw new IllegalArgumentException("unknown_crop: " + cropName);
        };
    }

    public static boolean isSupportedCrop(Block block) {
        return block == Blocks.WHEAT || block == Blocks.CARROTS || block == Blocks.POTATOES;
    }

    public static Item seedFor(Block crop) {
        if (crop == Blocks.WHEAT) {
            return Items.WHEAT_SEEDS;
        }
        if (crop == Blocks.CARROTS) {
            return Items.CARROT;
        }
        if (crop == Blocks.POTATOES) {
            return Items.POTATO;
        }
        throw new IllegalArgumentException("unknown_crop_block: " + crop);
    }

    public static boolean isTillable(BlockState state) {
        return state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT);
    }

    private static OptionalInt findHoeSlot(AIPlayerEntity bot) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            if (stack.getItem() instanceof HoeItem) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    public record CropSpec(Item seed, Block crop, String name) {
    }
}
