package io.github.greytaiwolf.fakeaiplayer.gametest;

import com.mojang.authlib.GameProfile;
import io.github.greytaiwolf.fakeaiplayer.coordination.IdleCoordinator;
import io.github.greytaiwolf.fakeaiplayer.coordination.Job;
import io.github.greytaiwolf.fakeaiplayer.coordination.TaskBoard;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.goal.GoalExecutor;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventoryMenu;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventorySessionManager;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.network.FakeClientConnection;
import io.github.greytaiwolf.fakeaiplayer.runtime.PauseOwner;
import io.github.greytaiwolf.fakeaiplayer.runtime.TaskOrigin;
import io.github.greytaiwolf.fakeaiplayer.task.AbstractTask;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Additional conflict contracts kept separate from the baseline behavior suite. */
public final class FakeAiPlayerConflictGameTests implements FabricGameTest {
    @GameTest(
            template = FabricGameTest.EMPTY_STRUCTURE,
            batch = "fakeaiplayer_inventory_shift_components",
            timeoutTicks = 20)
    public void offhandAndStorageQuickMovePreserveComponents(GameTestHelper context) {
        BlockPos botPos = context.absolutePos(new BlockPos(1, 2, 1));
        prepareFlat(context, botPos, 3);
        ServerPlayer owner = spawnViewer(
                context, "InvShiftOwner01", Vec3.atBottomCenterOf(botPos.offset(2, 0, 0)));
        String botName = "InvShiftBot01";
        AIPlayerEntity bot = spawnBot(context, botName, botPos, owner.getUUID());
        try {
            openMenu(bot, owner);
            BotInventoryMenu menu = requireMenu(bot, owner);

            ItemStack expectedShield = new ItemStack(Items.SHIELD);
            expectedShield.setDamageValue(31);
            expectedShield.set(DataComponents.CUSTOM_NAME, Component.literal("Shift Shield"));
            owner.getInventory().setItem(9, expectedShield.copy());

            ItemStack shieldMoved = menu.quickMoveStack(owner, BotInventoryMenu.VIEWER_MAIN_START);
            require(!shieldMoved.isEmpty(), "viewer shield Shift-click was rejected");
            require(ItemStack.matches(expectedShield, bot.getInventory().getItem(40)),
                    "offhand Shift-click lost damage or data components");
            require(owner.getInventory().getItem(9).isEmpty(),
                    "offhand Shift-click did not empty its viewer source slot");
            require(totalCount(owner.getInventory(), bot.getInventory(), Items.SHIELD) == 1,
                    "offhand Shift-click duplicated or lost the shield");

            ItemStack shieldReturned = menu.quickMoveStack(owner, BotInventoryMenu.BOT_OFFHAND_SLOT);
            require(!shieldReturned.isEmpty() && bot.getInventory().getItem(40).isEmpty(),
                    "reverse offhand Shift-click did not empty the bot slot");
            require(containsMatching(owner.getInventory(), expectedShield),
                    "reverse offhand Shift-click lost damage or data components");
            require(totalCount(owner.getInventory(), bot.getInventory(), Items.SHIELD) == 1,
                    "reverse offhand Shift-click duplicated or lost the shield");

            ItemStack expectedCargo = new ItemStack(Items.GOLD_INGOT, 23);
            expectedCargo.set(DataComponents.CUSTOM_NAME, Component.literal("Shift Cargo"));
            owner.getInventory().setItem(10, expectedCargo.copy());
            int viewerCargoSlot = BotInventoryMenu.VIEWER_MAIN_START + 1;
            ItemStack cargoMoved = menu.quickMoveStack(owner, viewerCargoSlot);
            require(!cargoMoved.isEmpty(), "viewer cargo Shift-click was rejected");
            require(owner.getInventory().getItem(10).isEmpty(),
                    "cargo Shift-click did not empty its viewer source slot");
            int botCargoSlot = findMatchingMenuSlot(
                    menu, BotInventoryMenu.BOT_STORAGE_START, BotInventoryMenu.BOT_HOTBAR_END, expectedCargo);
            require(botCargoSlot >= 0, "cargo count or data components changed in bot storage");
            require(totalCount(owner.getInventory(), bot.getInventory(), Items.GOLD_INGOT)
                            == expectedCargo.getCount(),
                    "cargo Shift-click duplicated or lost items");

            ItemStack cargoReturned = menu.quickMoveStack(owner, botCargoSlot);
            require(!cargoReturned.isEmpty(), "reverse storage Shift-click was rejected");
            require(containsMatching(owner.getInventory(), expectedCargo),
                    "reverse storage Shift-click lost count or data components");
            require(totalCount(owner.getInventory(), bot.getInventory(), Items.GOLD_INGOT)
                            == expectedCargo.getCount(),
                    "reverse storage Shift-click duplicated or lost items");

            finishSuccess(context, bot, owner, botName, botPos, 3);
        } catch (RuntimeException | AssertionError failure) {
            finishFailure(context, bot, owner, botName, botPos, 3, failure);
        }
    }

    @GameTest(
            template = FabricGameTest.EMPTY_STRUCTURE,
            batch = "fakeaiplayer_inventory_binding_curse",
            timeoutTicks = 20)
    public void bindingCurseBlocksSurvivalArmorRemoval(GameTestHelper context) {
        BlockPos botPos = context.absolutePos(new BlockPos(1, 2, 1));
        prepareFlat(context, botPos, 3);
        ServerPlayer owner = spawnViewer(
                context, "InvCurseOwner01", Vec3.atBottomCenterOf(botPos.offset(2, 0, 0)));
        String botName = "InvCurseBot01";
        AIPlayerEntity bot = spawnBot(context, botName, botPos, owner.getUUID());
        try {
            ItemStack cursedHelmet = new ItemStack(Items.DIAMOND_HELMET);
            cursedHelmet.setDamageValue(9);
            cursedHelmet.set(DataComponents.CUSTOM_NAME, Component.literal("Bound Helmet"));
            cursedHelmet.enchant(context.getLevel().registryAccess()
                    .lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.BINDING_CURSE), 1);
            bot.getInventory().setItem(39, cursedHelmet.copy());

            openMenu(bot, owner);
            BotInventoryMenu menu = requireMenu(bot, owner);
            require(!owner.isCreative(), "binding-curse fixture unexpectedly used creative mode");
            require(!menu.getSlot(BotInventoryMenu.BOT_ARMOR_START).mayPickup(owner),
                    "survival player was allowed to pick up binding-cursed armor");
            require(menu.quickMoveStack(owner, BotInventoryMenu.BOT_ARMOR_START).isEmpty(),
                    "Shift-click bypassed the binding-curse pickup restriction");
            require(ItemStack.matches(cursedHelmet, bot.getInventory().getItem(39)),
                    "failed cursed-armor removal still changed the item");
            require(totalCount(owner.getInventory(), bot.getInventory(), Items.DIAMOND_HELMET) == 1,
                    "failed cursed-armor removal duplicated or lost the helmet");

            finishSuccess(context, bot, owner, botName, botPos, 3);
        } catch (RuntimeException | AssertionError failure) {
            finishFailure(context, bot, owner, botName, botPos, 3, failure);
        }
    }

    @GameTest(
            template = FabricGameTest.EMPTY_STRUCTURE,
            batch = "fakeaiplayer_task_inventory_continuity",
            timeoutTicks = 65)
    public void activeTaskSurvivesInventoryPauseWithoutAmbient(GameTestHelper context) {
        BlockPos botPos = context.absolutePos(new BlockPos(1, 2, 1));
        prepareFlat(context, botPos, 4);
        ServerPlayer owner = spawnViewer(
                context, "TaskPauseOwner01", Vec3.atBottomCenterOf(botPos.offset(2, 0, 0)));
        String botName = "TaskPauseBot01";
        AIPlayerEntity bot = spawnBot(context, botName, botPos, owner.getUUID());
        TrackingTask task = new TrackingTask();
        Vec3 initialPosition = bot.position();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.PLAYER_COMMAND, "gametest_same_task_step"));
        int[] ticksBeforeMenu = {-1};

        context.runAtTickTime(5, () -> {
            try {
                ticksBeforeMenu[0] = task.tickCount;
                openMenu(bot, owner);
                requireMenu(bot, owner);
                require(TaskManager.INSTANCE.getActive(bot).isEmpty()
                                && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                        "inventory did not move the active task onto the pause stack");
                require(TaskManager.INSTANCE.isPausedBy(bot, PauseOwner.INVENTORY),
                        "inventory pause owner was not recorded");
                require(!IdleCoordinator.INSTANCE.ownsAmbientAction(bot),
                        "ambient action started while inventory owned the task");
            } catch (RuntimeException | AssertionError failure) {
                finishFailure(context, bot, owner, botName, botPos, 4, failure);
            }
        });

        context.runAtTickTime(12, () -> {
            try {
                require(task.tickCount == ticksBeforeMenu[0],
                        "paused task advanced while the inventory menu was open");
                require(task.pauseCount == 1 && task.resumeCount == 0,
                        "task pause callbacks were not owned exactly once by the menu");
                owner.closeContainer();
                require(TaskManager.INSTANCE.getActive(bot).orElse(null) == task,
                        "closing the menu did not restore the same task instance");
                require(task.startCount == 1 && task.resumeCount == 1,
                        "menu close restarted the task instead of resuming its current step");
            } catch (RuntimeException | AssertionError failure) {
                finishFailure(context, bot, owner, botName, botPos, 4, failure);
            }
        });

        context.runAtTickTime(55, () -> {
            try {
                require(TaskManager.INSTANCE.getActive(bot).orElse(null) == task,
                        "formal task was replaced after inventory resume");
                require(task.startCount == 1 && task.resumeCount == 1
                                && task.tickCount > ticksBeforeMenu[0],
                        "the same task step did not continue after inventory close");
                require(!IdleCoordinator.INSTANCE.ownsAmbientAction(bot),
                        "ambient behavior acquired control during a formal task");
                require(bot.position().distanceToSqr(initialPosition) < 1.0E-4D,
                        "ambient movement displaced the bot during the formal task");
                finishSuccess(context, bot, owner, botName, botPos, 4);
            } catch (RuntimeException | AssertionError failure) {
                finishFailure(context, bot, owner, botName, botPos, 4, failure);
            }
        });
    }

    @GameTest(
            template = FabricGameTest.EMPTY_STRUCTURE,
            batch = "fakeaiplayer_task_board_gate",
            timeoutTicks = 20)
    public void taskBoardClaimPreemptsAmbient(GameTestHelper context) {
        BlockPos botPos = context.absolutePos(new BlockPos(1, 2, 1));
        prepareFlat(context, botPos, 5);
        UUID ownerId = UUID.randomUUID();
        String botName = "BoardGateBot01";
        AIPlayerEntity bot = spawnBot(context, botName, botPos, ownerId);
        BlockPos destination = botPos.offset(3, 0, 0);
        UUID jobId = TaskBoard.INSTANCE.postForOwner(ownerId, "move", Map.of(
                "x", Integer.toString(destination.getX()),
                "y", Integer.toString(destination.getY()),
                "z", Integer.toString(destination.getZ())), "");
        try {
            require(IdleCoordinator.INSTANCE.tickBot(bot, true),
                    "IdleCoordinator did not claim the matching TaskBoard job");
            require(TaskManager.INSTANCE.getActive(bot).isPresent(),
                    "claimed TaskBoard job did not create a formal task");
            TaskOrigin origin = TaskManager.INSTANCE.activeOrigin(bot).orElseThrow();
            require(origin.kind() == TaskOrigin.Kind.JOB && jobId.equals(origin.jobId()),
                    "claimed work did not retain its TaskBoard ownership token");
            Job claimed = TaskBoard.INSTANCE.snapshot().stream()
                    .filter(job -> job.id().equals(jobId))
                    .findFirst()
                    .orElseThrow();
            require(claimed.status() == Job.Status.CLAIMED
                            && bot.getUUID().equals(claimed.claimant()),
                    "TaskBoard did not record the bot's claim");
            require(!IdleCoordinator.INSTANCE.ownsAmbientAction(bot),
                    "ambient action survived TaskBoard work assignment");

            TaskBoard.INSTANCE.markFailed(jobId, "gametest_cleanup");
            finishSuccess(context, bot, null, botName, botPos, 5);
        } catch (RuntimeException | AssertionError failure) {
            TaskBoard.INSTANCE.markFailed(jobId, "gametest_failure_cleanup");
            finishFailure(context, bot, null, botName, botPos, 5, failure);
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

    private static void openMenu(AIPlayerEntity bot, ServerPlayer owner) {
        owner.getInventory().setItem(owner.getInventory().selected, ItemStack.EMPTY);
        bot.interact(owner, InteractionHand.MAIN_HAND);
    }

    private static BotInventoryMenu requireMenu(AIPlayerEntity bot, ServerPlayer owner) {
        require(BotInventorySessionManager.INSTANCE.isOpen(bot),
                "authoritative bot inventory session did not open");
        require(owner.containerMenu instanceof BotInventoryMenu,
                "owner did not receive BotInventoryMenu");
        return (BotInventoryMenu) owner.containerMenu;
    }

    private static int findMatchingMenuSlot(BotInventoryMenu menu,
                                            int startInclusive,
                                            int endExclusive,
                                            ItemStack expected) {
        for (int index = startInclusive; index < endExclusive; index++) {
            if (ItemStack.matches(expected, menu.getSlot(index).getItem())) {
                return index;
            }
        }
        return -1;
    }

    private static boolean containsMatching(Inventory inventory, ItemStack expected) {
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            if (ItemStack.matches(expected, inventory.getItem(index))) {
                return true;
            }
        }
        return false;
    }

    private static int totalCount(Inventory first, Inventory second, Item item) {
        return count(first, item) + count(second, item);
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
        GoalExecutor.INSTANCE.clear(bot);
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_cleanup");
        TaskManager.INSTANCE.resumeOwnedPause(bot, PauseOwner.INVENTORY);
        IdleCoordinator.INSTANCE.cancelAmbient(bot, "gametest_cleanup");
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

    private static final class TrackingTask extends AbstractTask {
        private int startCount;
        private int tickCount;
        private int pauseCount;
        private int resumeCount;

        @Override
        public String name() {
            return "gametest_tracking_step";
        }

        @Override
        public String describe() {
            return "Stable GameTest step";
        }

        @Override
        public double progress() {
            return 0.5D;
        }

        @Override
        public boolean isWaiting() {
            return true;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
            startCount++;
            bot.getActionPack().stopAll();
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
            tickCount++;
            bot.getActionPack().stopMovement();
        }

        @Override
        protected void onPause(AIPlayerEntity bot) {
            pauseCount++;
            super.onPause(bot);
        }

        @Override
        protected void onResume(AIPlayerEntity bot) {
            resumeCount++;
        }
    }
}
