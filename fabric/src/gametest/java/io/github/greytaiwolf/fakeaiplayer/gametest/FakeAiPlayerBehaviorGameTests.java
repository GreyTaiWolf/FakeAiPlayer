package io.github.greytaiwolf.fakeaiplayer.gametest;

import com.mojang.authlib.GameProfile;
import io.github.greytaiwolf.fakeaiplayer.coordination.IdleCoordinator;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventoryMenu;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventorySessionManager;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.network.FakeClientConnection;
import io.github.greytaiwolf.fakeaiplayer.runtime.PauseOwner;
import io.github.greytaiwolf.fakeaiplayer.runtime.TaskOrigin;
import io.github.greytaiwolf.fakeaiplayer.task.HoldTask;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** World-backed contracts for ambient control ownership and the authoritative bot inventory. */
public final class FakeAiPlayerBehaviorGameTests implements FabricGameTest {
    private static final int AMBIENT_TEST_RADIUS = 15;

    @GameTest(
            template = FabricGameTest.EMPTY_STRUCTURE,
            batch = "fakeaiplayer_inventory_permission",
            timeoutTicks = 20)
    public void nonOwnerCannotOpenInventoryWithEmptyHand(GameTestHelper context) {
        BlockPos botPos = context.absolutePos(new BlockPos(1, 2, 1));
        prepareFlat(context, botPos, 2);
        ServerPlayer outsider = spawnViewer(
                context, "InvOutsider01", Vec3.atBottomCenterOf(botPos.offset(2, 0, 0)));
        String botName = "InvPrivate01";
        AIPlayerEntity bot = spawnBot(context, botName, botPos, UUID.randomUUID());
        try {
            require(outsider.getMainHandItem().isEmpty(), "permission fixture main hand was not empty");
            require(!outsider.hasPermissions(2), "permission fixture unexpectedly had OP access");
            bot.interact(outsider, InteractionHand.MAIN_HAND);
            require(!BotInventorySessionManager.INSTANCE.isOpen(bot),
                    "non-owner/non-OP player opened a private bot inventory");
            require(!(outsider.containerMenu instanceof BotInventoryMenu),
                    "unauthorized viewer received a bot inventory menu");
            finishSuccess(context, bot, outsider, botName, botPos, 2);
        } catch (RuntimeException | AssertionError failure) {
            finishFailure(context, bot, outsider, botName, botPos, 2, failure);
        }
    }

    @GameTest(
            template = FabricGameTest.EMPTY_STRUCTURE,
            batch = "fakeaiplayer_idle_control",
            timeoutTicks = 115)
    public void userPauseBlocksAmbientUntilTheSameOwnerResumes(GameTestHelper context) {
        BlockPos anchor = context.absolutePos(new BlockPos(1, 2, 1));
        prepareFlat(context, anchor, AMBIENT_TEST_RADIUS);
        Map<BlockPos, BlockState> initialBlocks = snapshot(context, anchor, AMBIENT_TEST_RADIUS);
        String botName = "IdlePause01";
        AIPlayerEntity bot = spawnBot(context, botName, anchor, null);
        Vec3 initialPosition = bot.position();
        TaskManager.INSTANCE.pauseUserIntent(bot, "gametest_idle_gate");

        context.runAtTickTime(52, () -> {
            try {
                require(TaskManager.INSTANCE.isPausedBy(bot, PauseOwner.USER),
                        "Danger/background processing released the USER pause");
                require(!IdleCoordinator.INSTANCE.ownsAmbientAction(bot),
                        "ambient movement acquired control while USER pause was active");
                require(bot.position().distanceToSqr(initialPosition) < 1.0E-4D,
                        "paused bot moved before resume");
                TaskManager.INSTANCE.resumeUserIntent(bot, "gametest_idle_gate");
            } catch (RuntimeException | AssertionError failure) {
                finishFailure(context, bot, null, botName, anchor, AMBIENT_TEST_RADIUS, failure);
            }
        });

        context.runAtTickTime(102, () -> {
            try {
                require(!TaskManager.INSTANCE.isPausedBy(bot, PauseOwner.USER),
                        "USER pause remained after its owner resumed it");
                require(IdleCoordinator.INSTANCE.ownsAmbientAction(bot)
                                || bot.position().distanceToSqr(initialPosition) >= 0.04D,
                        "ambient behavior did not begin after the 40 tick warmup");
                int dx = bot.blockPosition().getX() - anchor.getX();
                int dz = bot.blockPosition().getZ() - anchor.getZ();
                require(dx * dx + dz * dz <= 11 * 11,
                        "ambient movement escaped its anchor radius");
                require(context.getLevel().getFluidState(bot.blockPosition()).isEmpty()
                                && context.getLevel().getFluidState(bot.blockPosition().above()).isEmpty()
                                && context.getLevel().getFluidState(bot.blockPosition().below()).isEmpty(),
                        "ambient movement entered fluid");
                require(initialBlocks.equals(snapshot(context, anchor, AMBIENT_TEST_RADIUS)),
                        "ambient behavior changed world blocks");
                finishSuccess(context, bot, null, botName, anchor, AMBIENT_TEST_RADIUS);
            } catch (RuntimeException | AssertionError failure) {
                finishFailure(context, bot, null, botName, anchor, AMBIENT_TEST_RADIUS, failure);
            }
        });
    }

    @GameTest(
            template = FabricGameTest.EMPTY_STRUCTURE,
            batch = "fakeaiplayer_owner_inventory",
            timeoutTicks = 35)
    public void ownerLookAndInventorySessionPreserveManualPause(GameTestHelper context) {
        BlockPos botPos = context.absolutePos(new BlockPos(1, 2, 1));
        prepareFlat(context, botPos, 6);
        ServerPlayer owner = spawnViewer(context, "InvOwner01", Vec3.atBottomCenterOf(botPos.offset(4, 0, 0)));
        String botName = "InvBot01";
        AIPlayerEntity bot = spawnBot(context, botName, botPos, owner.getUUID());
        HoldTask pausedTask = new HoldTask();

        float desiredYaw = yawToward(bot.getEyePosition(), owner.getEyePosition());
        float initialYaw = Mth.wrapDegrees(desiredYaw + 150.0F);
        bot.setYRot(initialYaw);
        bot.setYHeadRot(initialYaw);
        float initialError = angularError(initialYaw, desiredYaw);

        context.runAtTickTime(8, () -> {
            try {
                require(!owner.hasPermissions(2), "owner fixture unexpectedly had OP access");
                float lookedError = angularError(bot.getYHeadRot(), desiredYaw);
                require(lookedError <= initialError - 20.0F,
                        "idle LOOK did not turn the bot head toward its owner"
                                + " (initialError=" + initialError
                                + ", currentError=" + lookedError
                                + ", headYaw=" + bot.getYHeadRot()
                                + ", bodyYaw=" + bot.getYRot() + ")");

                owner.getInventory().setItem(owner.getInventory().selected, new ItemStack(Items.STONE));
                bot.interact(owner, InteractionHand.MAIN_HAND);
                require(!BotInventorySessionManager.INSTANCE.isOpen(bot),
                        "non-empty main hand unexpectedly opened the bot inventory");
                owner.getInventory().setItem(owner.getInventory().selected, ItemStack.EMPTY);

                TaskManager.INSTANCE.assign(bot, pausedTask,
                        TaskOrigin.of(TaskOrigin.Kind.PLAYER_COMMAND, "gametest_inventory_resume"));
                TaskManager.INSTANCE.pauseUserIntent(bot, "gametest_inventory_ownership");
                ItemStack expectedHelmet = new ItemStack(Items.DIAMOND_HELMET);
                expectedHelmet.setDamageValue(17);
                expectedHelmet.set(DataComponents.CUSTOM_NAME, Component.literal("GameTest Helmet"));
                owner.getInventory().setItem(9, expectedHelmet.copy());

                bot.interact(owner, InteractionHand.MAIN_HAND);
                require(BotInventorySessionManager.INSTANCE.isOpen(bot),
                        "empty main hand did not open the authoritative inventory menu");
                require(owner.containerMenu instanceof BotInventoryMenu,
                        "owner did not receive BotInventoryMenu");
                require(bot.getActionPack().isSuspendedBy(PauseOwner.INVENTORY),
                        "inventory session did not suspend ActionPack");
                require(TaskManager.INSTANCE.isPausedBy(bot, PauseOwner.USER)
                                && TaskManager.INSTANCE.isPausedBy(bot, PauseOwner.INVENTORY),
                        "USER and INVENTORY pause owners were not retained independently");

                BotInventoryMenu menu = (BotInventoryMenu) owner.containerMenu;
                require(menu.getSlot(BotInventoryMenu.BOT_ARMOR_START).mayPlace(expectedHelmet)
                                && !menu.getSlot(BotInventoryMenu.BOT_ARMOR_START)
                                        .mayPlace(new ItemStack(Items.STONE)),
                        "helmet slot did not enforce equipment compatibility");
                ItemStack shifted = menu.quickMoveStack(owner, BotInventoryMenu.VIEWER_MAIN_START);
                ItemStack equipped = bot.getInventory().getItem(39);
                require(!shifted.isEmpty(), "Shift-click did not move the viewer helmet");
                require(ItemStack.matches(expectedHelmet, equipped),
                        "equipment Shift-click lost count, damage or data components");
                require(owner.getInventory().getItem(9).isEmpty(),
                        "viewer source slot was not emptied after Shift-click");
                require(count(owner.getInventory(), Items.DIAMOND_HELMET)
                                + count(bot.getInventory(), Items.DIAMOND_HELMET) == 1,
                        "helmet count was duplicated or lost");

                ItemStack shiftedBack = menu.quickMoveStack(owner, BotInventoryMenu.BOT_ARMOR_START);
                require(!shiftedBack.isEmpty() && bot.getInventory().getItem(39).isEmpty(),
                        "reverse Shift-click did not move armor back to the viewer");
                require(containsMatching(owner.getInventory(), expectedHelmet),
                        "reverse Shift-click lost helmet data components");
                require(count(owner.getInventory(), Items.DIAMOND_HELMET)
                                + count(bot.getInventory(), Items.DIAMOND_HELMET) == 1,
                        "reverse Shift-click duplicated or lost the helmet");

                owner.teleportTo(owner.serverLevel(),
                        bot.getX() + 9.0D, bot.getY(), bot.getZ(),
                        Set.of(), owner.getYRot(), owner.getXRot(), true);
            } catch (RuntimeException | AssertionError failure) {
                finishFailure(context, bot, owner, botName, botPos, 6, failure);
            }
        });

        context.runAtTickTime(12, () -> {
            try {
                require(!BotInventorySessionManager.INSTANCE.isOpen(bot),
                        "inventory session remained open beyond the 8 block range");
                require(!TaskManager.INSTANCE.isPausedBy(bot, PauseOwner.INVENTORY),
                        "distance close did not release the INVENTORY pause");
                require(TaskManager.INSTANCE.isPausedBy(bot, PauseOwner.USER),
                        "inventory close incorrectly released the USER pause");
                require(TaskManager.INSTANCE.getActive(bot).isEmpty()
                                && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                        "the paused task resumed before its USER owner released it");
                require(!bot.getActionPack().isSuspendedBy(PauseOwner.INVENTORY),
                        "distance close did not resume the inventory-owned ActionPack suspension");
                TaskManager.INSTANCE.resumeUserIntent(bot, "gametest_inventory_ownership");
                require(TaskManager.INSTANCE.getActive(bot).orElse(null) == pausedTask,
                        "final persistent lock release did not resume the same task instance");
                finishSuccess(context, bot, owner, botName, botPos, 6);
            } catch (RuntimeException | AssertionError failure) {
                finishFailure(context, bot, owner, botName, botPos, 6, failure);
            }
        });
    }

    private static AIPlayerEntity spawnBot(GameTestHelper context,
                                           String name,
                                           BlockPos absolutePos,
                                           UUID ownerUuid) {
        return AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(),
                        name,
                        context.getLevel(),
                        Vec3.atBottomCenterOf(absolutePos),
                        0.0F,
                        0.0F,
                        GameType.SURVIVAL,
                        ownerUuid)
                .orElseThrow(() -> new IllegalStateException("Could not spawn GameTest bot " + name));
    }

    private static ServerPlayer spawnViewer(GameTestHelper context, String name, Vec3 position) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        ClientInformation information = ClientInformation.createDefault();
        ServerPlayer player = new ServerPlayer(
                context.getLevel().getServer(), context.getLevel(), profile, information);
        FakeClientConnection connection = new FakeClientConnection(PacketFlow.SERVERBOUND);
        CommonListenerCookie cookie = new CommonListenerCookie(profile, 0, information, false);
        context.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.teleportTo(context.getLevel(), position.x, position.y, position.z,
                Set.of(), 0.0F, 0.0F, true);
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        return player;
    }

    private static int count(Inventory inventory, Item item) {
        int total = 0;
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            ItemStack stack = inventory.getItem(index);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean containsMatching(Inventory inventory, ItemStack expected) {
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            if (ItemStack.matches(expected, inventory.getItem(index))) {
                return true;
            }
        }
        return false;
    }

    private static float yawToward(Vec3 from, Vec3 target) {
        Vec3 delta = target.subtract(from);
        return Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D));
    }

    private static float angularError(float actual, float expected) {
        return Math.abs(Mth.wrapDegrees(actual - expected));
    }

    private static Map<BlockPos, BlockState> snapshot(GameTestHelper context,
                                                       BlockPos center,
                                                       int radius) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    result.put(pos.immutable(), context.getLevel().getBlockState(pos));
                }
            }
        }
        return result;
    }

    private static void prepareFlat(GameTestHelper context, BlockPos center, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos feet = center.offset(dx, 0, dz);
                context.getLevel().setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
                context.getLevel().setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
                context.getLevel().setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static void clearFlat(GameTestHelper context, BlockPos center, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    context.getLevel().setBlockAndUpdate(
                            center.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static void finishSuccess(GameTestHelper context,
                                      AIPlayerEntity bot,
                                      ServerPlayer viewer,
                                      String botName,
                                      BlockPos center,
                                      int radius) {
        cleanup(context, bot, viewer, botName, center, radius);
        context.succeed();
    }

    private static void finishFailure(GameTestHelper context,
                                      AIPlayerEntity bot,
                                      ServerPlayer viewer,
                                      String botName,
                                      BlockPos center,
                                      int radius,
                                      Throwable failure) {
        cleanup(context, bot, viewer, botName, center, radius);
        context.fail(failure.getMessage() == null ? failure.toString() : failure.getMessage());
    }

    private static void cleanup(GameTestHelper context,
                                AIPlayerEntity bot,
                                ServerPlayer viewer,
                                String botName,
                                BlockPos center,
                                int radius) {
        if (viewer != null && viewer.containerMenu instanceof BotInventoryMenu) {
            viewer.closeContainer();
        }
        TaskManager.INSTANCE.resumeOwnedPause(bot, PauseOwner.INVENTORY);
        TaskManager.INSTANCE.resumeUserIntent(bot, "gametest_cleanup");
        AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), botName);
        if (viewer != null) {
            if (viewer.connection != null) {
                viewer.connection.disconnect(new DisconnectionDetails(Component.literal("GameTest cleanup")));
            } else {
                context.getLevel().getServer().getPlayerList().remove(viewer);
            }
        }
        clearFlat(context, center, radius);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
