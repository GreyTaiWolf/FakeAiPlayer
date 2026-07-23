package io.github.greytaiwolf.fakeaiplayer.gametest;

import com.mojang.authlib.GameProfile;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventoryMenu;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventorySessionManager;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.network.FakeClientConnection;
import io.github.greytaiwolf.fakeaiplayer.runtime.PauseOwner;
import io.github.greytaiwolf.fakeaiplayer.runtime.TaskOrigin;
import io.github.greytaiwolf.fakeaiplayer.task.DangerWatcher;
import io.github.greytaiwolf.fakeaiplayer.task.HoldTask;
import io.github.greytaiwolf.fakeaiplayer.task.MoveTask;
import io.github.greytaiwolf.fakeaiplayer.task.NavSafetyNet;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import io.github.greytaiwolf.fakeaiplayer.task.TaskState;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** World-backed contracts at the inventory, safety-preemption and persistent-pause boundary. */
public final class FakeAiPlayerSafetyControlGameTests implements FabricGameTest {
    private static final int FIXTURE_RADIUS = 3;

    @GameTest(
            template = FabricGameTest.EMPTY_STRUCTURE,
            batch = "fakeaiplayer_safety_control",
            timeoutTicks = 30)
    public void lavaClosesInventoryAndResumesTheSamePausedTask(GameTestHelper context) {
        BlockPos botPos = context.absolutePos(new BlockPos(1, 2, 1));
        prepareFlat(context, botPos);
        ServerPlayer owner = spawnViewer(
                context, "SafeOwner01", Vec3.atBottomCenterOf(botPos.offset(2, 0, 0)));
        String botName = "SafeBot01";
        AIPlayerEntity bot = spawnBot(context, botName, botPos, owner.getUUID());
        HoldTask originalTask = new HoldTask();

        try {
            TaskManager.INSTANCE.assign(bot, originalTask,
                    TaskOrigin.of(TaskOrigin.Kind.PLAYER_COMMAND, "gametest_safety_resume"));
            owner.getInventory().setItem(owner.getInventory().selected, ItemStack.EMPTY);
            bot.interact(owner, InteractionHand.MAIN_HAND);
            require(BotInventorySessionManager.INSTANCE.isOpen(bot),
                    "authoritative bot inventory did not open");
            require(owner.containerMenu instanceof BotInventoryMenu,
                    "owner did not receive BotInventoryMenu");
            require(TaskManager.INSTANCE.isPausedBy(bot, PauseOwner.INVENTORY)
                            && originalTask.state() == TaskState.PAUSED,
                    "inventory did not pause the original HoldTask");
            int elapsedAtOpen = originalTask.elapsedTicks();

            context.getLevel().setBlockAndUpdate(botPos, Blocks.LAVA.defaultBlockState());
            boolean safetyHandled = NavSafetyNet.INSTANCE.tickBot(
                    context.getLevel().getServer(), bot);
            require(safetyHandled, "lava fixture did not trigger NavSafetyNet");
            require(!BotInventorySessionManager.INSTANCE.isOpen(bot)
                            && !(owner.containerMenu instanceof BotInventoryMenu),
                    "immediate lava safety did not force-close the inventory menu");
            require(!TaskManager.INSTANCE.isPausedBy(bot, PauseOwner.INVENTORY),
                    "forced menu close retained the INVENTORY pause lock");
            require(TaskManager.INSTANCE.getActive(bot).isEmpty()
                            && TaskManager.INSTANCE.pausedDepth(bot) == 1
                            && originalTask.state() == TaskState.PAUSED,
                    "lava safety did not take control by pausing the original task");

            context.getLevel().setBlockAndUpdate(botPos, Blocks.AIR.defaultBlockState());
            boolean stillHandled = NavSafetyNet.INSTANCE.tickBot(
                    context.getLevel().getServer(), bot);
            require(!stillHandled, "lava safety remained active after the hazard was removed");
            require(TaskManager.INSTANCE.getActive(bot).orElse(null) == originalTask,
                    "hazard release did not restore the exact HoldTask instance");
            require(originalTask.state() == TaskState.RUNNING
                            && originalTask.elapsedTicks() == elapsedAtOpen,
                    "hazard release restarted or advanced the paused HoldTask step");
            require(TaskManager.INSTANCE.pausedDepth(bot) == 0,
                    "safety pause frame remained after hazard release");

            // Mission Tasks are different from direct player Tasks: their onResume hooks may
            // restart paths immediately, so the safety unwind must wait for GoalExecutor's bound
            // RESUME/REPLAN/CANCEL decision.
            TaskManager.INSTANCE.cancelIntentTasks(bot, "prepare_mission_resume_handoff");
            UUID missionId = UUID.randomUUID();
            MoveTask missionTask = new MoveTask(bot, botPos.offset(2, 0, 0));
            TaskManager.INSTANCE.assign(
                    bot,
                    missionTask,
                    TaskOrigin.mission(missionId, "gametest_mission_resume_handoff"));
            context.getLevel().setBlockAndUpdate(botPos, Blocks.LAVA.defaultBlockState());
            require(NavSafetyNet.INSTANCE.tickBot(context.getLevel().getServer(), bot),
                    "Mission lava fixture did not trigger NavSafetyNet");
            context.getLevel().setBlockAndUpdate(botPos, Blocks.AIR.defaultBlockState());
            require(!NavSafetyNet.INSTANCE.tickBot(context.getLevel().getServer(), bot),
                    "Mission safety did not release after the hazard was removed");
            require(missionTask.state() == TaskState.PAUSED
                            && TaskManager.INSTANCE.getActive(bot).isEmpty(),
                    "generic safety unwind invoked Mission onResume before policy handoff");
            require(TaskManager.INSTANCE.hasMissionInterruption(bot, missionId),
                    "Mission interruption latch disappeared before policy handoff");
            require(TaskManager.INSTANCE.resumeMissionAfterInterruption(bot, missionId)
                            && missionTask.state() == TaskState.RUNNING
                            && TaskManager.INSTANCE.getActive(bot).orElse(null) == missionTask,
                    "explicit RESUME policy handoff did not restore the exact Mission Task");
            require(TaskManager.INSTANCE.consumeMissionInterruption(bot, missionId),
                    "Mission interruption latch was not consumable after policy handoff");

            TaskManager.INSTANCE.cancelIntentTasks(bot, "prepare_recovery_inventory_gate");
            TaskManager.INSTANCE.enterRuntimeRecoveryMode("gametest_recovery_inventory_gate");
            try {
                owner.getInventory().setItem(owner.getInventory().selected, ItemStack.EMPTY);
                bot.interact(owner, InteractionHand.MAIN_HAND);
                require(!BotInventorySessionManager.INSTANCE.isOpen(bot)
                                && !(owner.containerMenu instanceof BotInventoryMenu),
                        "read-only recovery allowed a Bot inventory session");
                require(AIPlayerManager.INSTANCE.spawn(
                                context.getLevel().getServer(),
                                "BlockedRecoveryBot01",
                                context.getLevel(),
                                Vec3.atBottomCenterOf(botPos.offset(1, 0, 1)),
                                0.0F,
                                0.0F,
                                GameType.SURVIVAL,
                                null).isEmpty(),
                        "read-only recovery allowed a newly spawned Bot");
            } finally {
                TaskManager.INSTANCE.beginRuntimeSession();
            }
            finishSuccess(context, bot, owner, botName, botPos);
        } catch (RuntimeException | AssertionError failure) {
            finishFailure(context, bot, owner, botName, botPos, failure);
        }
    }

    @GameTest(
            template = FabricGameTest.EMPTY_STRUCTURE,
            batch = "fakeaiplayer_safety_control",
            timeoutTicks = 30)
    public void userPauseSurvivesSafetyMenuCloseAndDangerScan(GameTestHelper context) {
        BlockPos botPos = context.absolutePos(new BlockPos(1, 2, 1));
        prepareFlat(context, botPos);
        ServerPlayer owner = spawnViewer(
                context, "PauseOwner01", Vec3.atBottomCenterOf(botPos.offset(2, 0, 0)));
        String botName = "PauseBot01";
        AIPlayerEntity bot = spawnBot(context, botName, botPos, owner.getUUID());
        HoldTask originalTask = new HoldTask();

        try {
            bot.getFoodData().setFoodLevel(20);
            TaskManager.INSTANCE.assign(bot, originalTask,
                    TaskOrigin.of(TaskOrigin.Kind.PLAYER_COMMAND, "gametest_user_pause_safety"));
            TaskManager.INSTANCE.pauseUserIntent(bot, "gametest_user_pause_safety");
            owner.getInventory().setItem(owner.getInventory().selected, ItemStack.EMPTY);
            bot.interact(owner, InteractionHand.MAIN_HAND);
            require(BotInventorySessionManager.INSTANCE.isOpen(bot),
                    "inventory did not open over an existing USER pause");
            require(TaskManager.INSTANCE.isPausedBy(bot, PauseOwner.USER)
                            && TaskManager.INSTANCE.isPausedBy(bot, PauseOwner.INVENTORY),
                    "USER and INVENTORY locks were not retained independently");

            context.getLevel().setBlockAndUpdate(botPos, Blocks.LAVA.defaultBlockState());
            require(NavSafetyNet.INSTANCE.tickBot(context.getLevel().getServer(), bot),
                    "lava fixture did not trigger safety handling");
            require(!BotInventorySessionManager.INSTANCE.isOpen(bot)
                            && !TaskManager.INSTANCE.isPausedBy(bot, PauseOwner.INVENTORY),
                    "safety did not close and release the inventory session");
            require(TaskManager.INSTANCE.isPausedBy(bot, PauseOwner.USER),
                    "safety menu close incorrectly released the USER pause");
            require(TaskManager.INSTANCE.getActive(bot).isEmpty()
                            && TaskManager.INSTANCE.pausedDepth(bot) == 1
                            && originalTask.state() == TaskState.PAUSED,
                    "USER-owned HoldTask resumed during safety takeover");

            context.getLevel().setBlockAndUpdate(botPos, Blocks.AIR.defaultBlockState());
            NavSafetyNet.INSTANCE.tickBot(context.getLevel().getServer(), bot);
            // Remove the one-tick escape input so this assertion reaches DangerWatcher's resume
            // branch for the intended reason, rather than passing because an action is still live.
            bot.getActionPack().stopMovement();
            DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
            require(TaskManager.INSTANCE.isPausedBy(bot, PauseOwner.USER),
                    "DangerWatcher incorrectly released the USER pause");
            require(TaskManager.INSTANCE.getActive(bot).isEmpty()
                            && originalTask.state() == TaskState.PAUSED,
                    "DangerWatcher resumed a USER-owned task");

            TaskManager.INSTANCE.resumeUserIntent(bot, "gametest_user_pause_safety");
            require(TaskManager.INSTANCE.getActive(bot).orElse(null) == originalTask
                            && originalTask.state() == TaskState.RUNNING,
                    "the USER owner could not resume the exact original HoldTask");
            finishSuccess(context, bot, owner, botName, botPos);
        } catch (RuntimeException | AssertionError failure) {
            finishFailure(context, bot, owner, botName, botPos, failure);
        }
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

    private static void prepareFlat(GameTestHelper context, BlockPos center) {
        for (int dx = -FIXTURE_RADIUS; dx <= FIXTURE_RADIUS; dx++) {
            for (int dz = -FIXTURE_RADIUS; dz <= FIXTURE_RADIUS; dz++) {
                BlockPos feet = center.offset(dx, 0, dz);
                context.getLevel().setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
                context.getLevel().setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
                context.getLevel().setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static void clearFlat(GameTestHelper context, BlockPos center) {
        for (int dx = -FIXTURE_RADIUS; dx <= FIXTURE_RADIUS; dx++) {
            for (int dz = -FIXTURE_RADIUS; dz <= FIXTURE_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    context.getLevel().setBlockAndUpdate(
                            center.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static void finishSuccess(GameTestHelper context,
                                      AIPlayerEntity bot,
                                      ServerPlayer owner,
                                      String botName,
                                      BlockPos center) {
        cleanup(context, bot, owner, botName, center);
        context.succeed();
    }

    private static void finishFailure(GameTestHelper context,
                                      AIPlayerEntity bot,
                                      ServerPlayer owner,
                                      String botName,
                                      BlockPos center,
                                      Throwable failure) {
        cleanup(context, bot, owner, botName, center);
        context.fail(failure.getMessage() == null ? failure.toString() : failure.getMessage());
    }

    private static void cleanup(GameTestHelper context,
                                AIPlayerEntity bot,
                                ServerPlayer owner,
                                String botName,
                                BlockPos center) {
        context.getLevel().setBlockAndUpdate(center, Blocks.AIR.defaultBlockState());
        if (owner.containerMenu instanceof BotInventoryMenu) {
            owner.closeContainer();
        }
        NavSafetyNet.INSTANCE.clear(bot);
        TaskManager.INSTANCE.resumeOwnedPause(bot, PauseOwner.INVENTORY);
        TaskManager.INSTANCE.resumeUserIntent(bot, "gametest_cleanup");
        AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), botName);
        if (owner.connection != null) {
            owner.connection.disconnect(new DisconnectionDetails(Component.literal("GameTest cleanup")));
        } else {
            context.getLevel().getServer().getPlayerList().remove(owner);
        }
        clearFlat(context, center);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
