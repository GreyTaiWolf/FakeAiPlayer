package io.github.greytaiwolf.fakeaiplayer.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.greytaiwolf.fakeaiplayer.action.LookAction;
import io.github.greytaiwolf.fakeaiplayer.brain.ToolDefinition;
import io.github.greytaiwolf.fakeaiplayer.brain.ToolRegistry;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.perception.focus.FocusKind;
import io.github.greytaiwolf.fakeaiplayer.perception.focus.FocusResolver;
import io.github.greytaiwolf.fakeaiplayer.perception.focus.FocusSnapshot;
import io.github.greytaiwolf.fakeaiplayer.perception.focus.FocusTracker;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

/** World-backed contracts for the deterministic semantic-gaze resolver. */
public final class FakeAiPlayerFocusGameTests implements FabricGameTest {
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 40)
    public void semanticGazeReadsTargetedBlock(GameTestHelper context) {
        BlockPos botPos = new BlockPos(1, 1, 1);
        BlockPos targetPos = new BlockPos(1, 2, 3);
        context.setBlock(botPos.below(), Blocks.STONE);
        context.setBlock(targetPos, Blocks.IRON_ORE);
        AIPlayerEntity bot = spawnBot(context, "FocusBlock01", botPos);
        try {
            BlockPos target = context.absolutePos(targetPos);
            LookAction.lookAtBlock(bot, target, Direction.NORTH);
            FocusSnapshot focus = FocusResolver.inspectNow(bot);
            if (focus.kind() != FocusKind.BLOCK || !focus.id().equals("minecraft:iron_ore")) {
                context.fail("Expected focused iron ore, got " + abbreviate(focus.toSummaryJson()));
            }
            context.succeed();
        } finally {
            AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), "FocusBlock01");
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 40)
    public void nearerEntityWinsOverBlockBehindIt(GameTestHelper context) {
        BlockPos botPos = new BlockPos(1, 1, 1);
        context.setBlock(botPos.below(), Blocks.STONE);
        context.setBlock(1, 0, 3, Blocks.STONE);
        context.setBlock(1, 1, 4, Blocks.STONE);
        context.setBlock(1, 2, 4, Blocks.STONE);
        AIPlayerEntity bot = spawnBot(context, "FocusEntity01", botPos);
        Cow cow = context.spawnWithNoFreeWill(EntityType.COW, new Vec3(1.5D, 1.0D, 3.0D));
        try {
            LookAction.lookAt(bot, cow.getBoundingBox().getCenter());
            FocusSnapshot focus = FocusResolver.inspectNow(bot);
            if (focus.kind() != FocusKind.LIVING_ENTITY || !focus.id().equals("minecraft:cow")) {
                context.fail("Expected focused cow before wall, got " + abbreviate(focus.toSummaryJson()));
            }
            context.succeed();
        } finally {
            cow.discard();
            AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), "FocusEntity01");
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 40)
    public void wallOccludesEntityBehindIt(GameTestHelper context) {
        BlockPos botPos = new BlockPos(1, 1, 1);
        BlockPos wallPos = new BlockPos(1, 2, 3);
        context.setBlock(botPos.below(), Blocks.STONE);
        context.setBlock(1, 1, 3, Blocks.STONE);
        context.setBlock(wallPos, Blocks.STONE);
        context.setBlock(1, 0, 5, Blocks.STONE);
        AIPlayerEntity bot = spawnBot(context, "FocusWall01", botPos);
        Cow cow = context.spawnWithNoFreeWill(EntityType.COW, new Vec3(1.5D, 1.0D, 5.0D));
        try {
            LookAction.lookAt(bot, cow.getBoundingBox().getCenter());
            FocusSnapshot focus = FocusResolver.inspectNow(bot);
            if (focus.kind() != FocusKind.BLOCK
                    || !focus.id().equals("minecraft:stone")
                    || focus.block() == null
                    || focus.block().x() != context.absolutePos(wallPos).getX()
                    || focus.block().y() != context.absolutePos(wallPos).getY()
                    || focus.block().z() != context.absolutePos(wallPos).getZ()) {
                context.fail("Expected wall to occlude cow, got " + abbreviate(focus.toSummaryJson()));
            }
            context.succeed();
        } finally {
            cow.discard();
            AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), "FocusWall01");
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 40)
    public void semanticGazeIncludesDroppedItems(GameTestHelper context) {
        BlockPos botPos = new BlockPos(1, 1, 1);
        context.setBlock(botPos.below(), Blocks.STONE);
        context.setBlock(1, 0, 3, Blocks.STONE);
        AIPlayerEntity bot = spawnBot(context, "FocusItem01", botPos);
        ItemEntity item = context.spawnItem(Items.DIAMOND, new Vec3(1.5D, 1.1D, 3.0D));
        try {
            LookAction.lookAt(bot, item.getBoundingBox().getCenter());
            FocusSnapshot focus = FocusResolver.inspectNow(bot);
            if (focus.kind() != FocusKind.ITEM_ENTITY
                    || focus.item() == null
                    || !focus.item().itemId().equals("minecraft:diamond")
                    || focus.item().count() != 1) {
                context.fail("Expected focused diamond item, got " + abbreviate(focus.toJson()));
            }
            context.succeed();
        } finally {
            item.discard();
            AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), "FocusItem01");
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 40)
    public void inspectFocusDetectsSameTickTargetReplacement(GameTestHelper context) {
        BlockPos botPos = new BlockPos(2, 1, 2);
        BlockPos targetPos = new BlockPos(2, 2, 5);
        context.setBlock(botPos.below(), Blocks.STONE);
        context.setBlock(targetPos, Blocks.IRON_ORE);
        AIPlayerEntity bot = spawnBot(context, "FocusDrift01", botPos);
        try {
            LookAction.lookAt(bot, context.absolutePos(targetPos).getCenter());
            FocusSnapshot first = FocusTracker.INSTANCE.inspectNow(bot);

            context.setBlock(targetPos, Blocks.DIAMOND_ORE);
            JsonObject args = new JsonObject();
            args.addProperty("detail", "full");
            args.addProperty("expected_target_token", first.targetToken());
            ToolDefinition.ToolResult result = new ToolRegistry().get("inspect_focus")
                    .orElseThrow()
                    .handler()
                    .invoke(bot, args);
            JsonObject response = JsonParser.parseString(result.message()).getAsJsonObject();
            JsonObject focus = response.getAsJsonObject("focus");
            if (!result.ok()
                    || !response.get("targetChanged").getAsBoolean()
                    || !focus.get("id").getAsString().equals("minecraft:diamond_ore")) {
                context.fail("Expected same-position replacement to change target token, got "
                        + abbreviate(result.message()));
            }

            String diamondToken = response.get("currentTargetToken").getAsString();
            context.setBlock(targetPos, Blocks.AIR);
            BlockPos eyeColumn = BlockPos.containing(bot.getEyePosition());
            for (int offset = 0; offset <= 10; offset++) {
                bot.serverLevel().setBlockAndUpdate(eyeColumn.above(offset), Blocks.AIR.defaultBlockState());
            }
            LookAction.lookAt(bot, bot.getEyePosition().add(0.0D, 20.0D, 0.0D));
            JsonObject missArgs = new JsonObject();
            missArgs.addProperty("detail", "full");
            missArgs.addProperty("expected_target_token", diamondToken);
            ToolDefinition.ToolResult missResult = new ToolRegistry().get("inspect_focus")
                    .orElseThrow()
                    .handler()
                    .invoke(bot, missArgs);
            JsonObject missResponse = JsonParser.parseString(missResult.message()).getAsJsonObject();
            if (!missResult.ok()
                    || !missResponse.get("targetChanged").getAsBoolean()
                    || !missResponse.getAsJsonObject("focus").get("kind").getAsString().equals("MISS")) {
                context.fail("Expected fresh MISS to bypass tracking grace, got "
                        + abbreviate(missResult.message()));
            }
            context.succeed();
        } finally {
            FocusTracker.INSTANCE.clear(bot);
            AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), "FocusDrift01");
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 40)
    public void detailedInspectUpgradesLightweightContainerSnapshot(GameTestHelper context) {
        BlockPos botPos = new BlockPos(1, 1, 1);
        BlockPos chestPos = new BlockPos(1, 2, 3);
        context.setBlock(botPos.below(), Blocks.STONE);
        context.setBlock(chestPos, Blocks.CHEST);
        ChestBlockEntity chest = context.getBlockEntity(chestPos);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
        AIPlayerEntity bot = spawnBot(context, "FocusChest01", botPos);
        try {
            LookAction.lookAt(bot, context.absolutePos(chestPos).getCenter());
            FocusSnapshot lightweight = FocusTracker.INSTANCE.observeNow(bot);
            FocusSnapshot detailed = FocusTracker.INSTANCE.inspectNow(bot);

            if (lightweight.block() == null
                    || lightweight.block().blockEntity() == null
                    || lightweight.block().blockEntity().contentsReadable()
                    || !lightweight.block().blockEntity().itemCounts().isEmpty()) {
                context.fail("Expected bounded lightweight chest snapshot, got "
                        + abbreviate(lightweight.toJson()));
            }
            if (detailed.block() == null
                    || detailed.block().blockEntity() == null
                    || !detailed.block().blockEntity().contentsReadable()
                    || detailed.block().blockEntity().itemCounts().getOrDefault("minecraft:diamond", 0) != 3) {
                context.fail("Expected detailed chest contents, got " + abbreviate(detailed.toJson()));
            }
            context.succeed();
        } finally {
            FocusTracker.INSTANCE.clear(bot);
            AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), "FocusChest01");
        }
    }

    private static AIPlayerEntity spawnBot(GameTestHelper context, String name, BlockPos relativePos) {
        Vec3 position = Vec3.atBottomCenterOf(context.absolutePos(relativePos));
        return AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(),
                        name,
                        context.getLevel(),
                        position,
                        0.0F,
                        0.0F,
                        GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("Could not spawn GameTest Bot " + name));
    }

    private static String abbreviate(String value) {
        return value.length() <= 400 ? value : value.substring(0, 400) + "...";
    }
}
