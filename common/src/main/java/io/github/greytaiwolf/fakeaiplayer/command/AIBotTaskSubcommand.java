package io.github.greytaiwolf.fakeaiplayer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationGate;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy;
import io.github.greytaiwolf.fakeaiplayer.runtime.TaskOrigin;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.mining.OreScan;
import io.github.greytaiwolf.fakeaiplayer.runtime.IntentController;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintLoader;
import io.github.greytaiwolf.fakeaiplayer.action.FarmAction;
import io.github.greytaiwolf.fakeaiplayer.task.BreedTask;
import io.github.greytaiwolf.fakeaiplayer.task.BuildTask;
import io.github.greytaiwolf.fakeaiplayer.task.CombatTask;
import io.github.greytaiwolf.fakeaiplayer.task.ContainerTask;
import io.github.greytaiwolf.fakeaiplayer.task.CraftTask;
import io.github.greytaiwolf.fakeaiplayer.task.EatTask;
import io.github.greytaiwolf.fakeaiplayer.task.FarmTask;
import io.github.greytaiwolf.fakeaiplayer.task.GatherQuotaTask;
import io.github.greytaiwolf.fakeaiplayer.task.LightAreaTask;
import io.github.greytaiwolf.fakeaiplayer.task.MineTask;
import io.github.greytaiwolf.fakeaiplayer.task.MoveTask;
import io.github.greytaiwolf.fakeaiplayer.task.SleepTask;
import io.github.greytaiwolf.fakeaiplayer.task.SmeltTask;
import io.github.greytaiwolf.fakeaiplayer.task.StockpileTask;
import io.github.greytaiwolf.fakeaiplayer.task.OreDigTask;
import io.github.greytaiwolf.fakeaiplayer.task.StripMineTask;
import io.github.greytaiwolf.fakeaiplayer.task.Task;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import io.github.greytaiwolf.fakeaiplayer.task.TaskStatus;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class AIBotTaskSubcommand {
    private AIBotTaskSubcommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("task")
                .then(literal("assign")
                        .then(botName()
                                .then(literal("move")
                                        .then(blockPosArgs(AIBotTaskSubcommand::assignMove)))
                                .then(literal("forage")
                                        .executes(context -> assignForage(context, 4))
                                        .then(argument("count", IntegerArgumentType.integer(1))
                                                .executes(context -> assignForage(context, IntegerArgumentType.getInteger(context, "count")))))
                                .then(literal("attack")
                                        .then(argument("entity_type", ResourceLocationArgument.id())
                                                .executes(context -> assignAttack(context, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignAttack(context, IntegerArgumentType.getInteger(context, "count"))))))
                                .then(literal("mine")
                                        .then(argument("block", ResourceLocationArgument.id())
                                                .executes(context -> assignMine(context, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignMine(context, IntegerArgumentType.getInteger(context, "count"))))))
                                .then(literal("gather")
                                        .then(argument("item", ResourceLocationArgument.id())
                                                .executes(context -> assignGather(context, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignGather(context, IntegerArgumentType.getInteger(context, "count"))))))
                                .then(literal("strip_mine")
                                        .then(argument("direction", StringArgumentType.word())
                                                .executes(context -> assignStripMine(context, 16, 4, null))
                                                .then(argument("length", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignStripMine(context, IntegerArgumentType.getInteger(context, "length"), 4, null))
                                                        .then(argument("spacing", IntegerArgumentType.integer(0))
                                                                .executes(context -> assignStripMine(context,
                                                                        IntegerArgumentType.getInteger(context, "length"),
                                                                        IntegerArgumentType.getInteger(context, "spacing"),
                                                                        null))
                                                                .then(literal("depot")
                                                                        .then(blockPosArgs(context -> assignStripMine(context,
                                                                                IntegerArgumentType.getInteger(context, "length"),
                                                                                IntegerArgumentType.getInteger(context, "spacing"),
                                                                                getBlockPos(context)))))))))
                                .then(literal("mine_vein")
                                        .executes(context -> assignMineVein(context, null))
                                        .then(argument("ore", ResourceLocationArgument.id())
                                                .executes(context -> assignMineVein(context, BuiltInRegistries.BLOCK.getValue(ResourceLocationArgument.getId(context, "ore"))))))
                                .then(literal("craft")
                                        .then(argument("item", ResourceLocationArgument.id())
                                                .executes(context -> assignCraft(context, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignCraft(context, IntegerArgumentType.getInteger(context, "count"))))))
                                .then(literal("eat")
                                        .executes(AIBotTaskSubcommand::assignEat))
                                .then(literal("sleep")
                                        .executes(AIBotTaskSubcommand::assignSleep))
                                .then(literal("light_area")
                                        .executes(context -> assignLightArea(context, 8, 8))
                                        .then(argument("radius", IntegerArgumentType.integer(2))
                                                .executes(context -> assignLightArea(context, IntegerArgumentType.getInteger(context, "radius"), 8))
                                                .then(argument("max_torches", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignLightArea(context,
                                                                IntegerArgumentType.getInteger(context, "radius"),
                                                                IntegerArgumentType.getInteger(context, "max_torches"))))))
                                .then(literal("farm")
                                        .then(argument("x", IntegerArgumentType.integer())
                                                .then(argument("y", IntegerArgumentType.integer())
                                                        .then(argument("z", IntegerArgumentType.integer())
                                                                .then(argument("radius", IntegerArgumentType.integer(1))
                                                                        .then(argument("crop", ResourceLocationArgument.id())
                                                                                .executes(context -> assignFarm(context, IntegerArgumentType.getInteger(context, "radius"), false))
                                                                                .then(literal("keep_tending")
                                                                                        .executes(context -> assignFarm(context, IntegerArgumentType.getInteger(context, "radius"), true)))))))))
                                .then(literal("harvest")
                                        .then(argument("x", IntegerArgumentType.integer())
                                                .then(argument("y", IntegerArgumentType.integer())
                                                        .then(argument("z", IntegerArgumentType.integer())
                                                                .then(argument("radius", IntegerArgumentType.integer(1))
                                                                        .then(argument("crop", ResourceLocationArgument.id())
                                                                                .executes(context -> assignHarvest(context, IntegerArgumentType.getInteger(context, "radius")))))))))
                                .then(literal("breed")
                                        .then(argument("entity_type", ResourceLocationArgument.id())
                                                .executes(context -> assignBreed(context, 1))
                                                .then(argument("pairs", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignBreed(context, IntegerArgumentType.getInteger(context, "pairs"))))))
                                .then(literal("smelt")
                                        .then(argument("input_item", ResourceLocationArgument.id())
                                                .then(argument("output_item", ResourceLocationArgument.id())
                                                        .executes(context -> assignSmelt(context, 1))
                                                        .then(argument("count", IntegerArgumentType.integer(1))
                                                                .executes(context -> assignSmelt(context, IntegerArgumentType.getInteger(context, "count")))))))
                                .then(literal("deposit")
                                        .executes(context -> assignDeposit(context, null, 0, false, null))
                                        .then(literal("all_except_tools")
                                                .executes(context -> assignDeposit(context, null, 0, true, null)))
                                        .then(literal("item")
                                                .then(argument("item", ResourceLocationArgument.id())
                                                        .executes(context -> assignDeposit(context, requiredItem(context, "item"), 0, false, null))
                                                        .then(argument("count", IntegerArgumentType.integer(1))
                                                                .executes(context -> assignDeposit(context, requiredItem(context, "item"), IntegerArgumentType.getInteger(context, "count"), false, null)))))
                                        .then(literal("at")
                                                .then(blockPosArgs(context -> assignDepositAt(context, null, 0, false)))
                                                .then(argument("x", IntegerArgumentType.integer())
                                                        .then(argument("y", IntegerArgumentType.integer())
                                                                .then(argument("z", IntegerArgumentType.integer())
                                                                        .then(literal("all_except_tools")
                                                                                .executes(context -> assignDeposit(context, null, 0, true, getBlockPos(context))))
                                                                        .then(literal("item")
                                                                                .then(argument("item", ResourceLocationArgument.id())
                                                                                        .executes(context -> assignDeposit(context, requiredItem(context, "item"), 0, false, getBlockPos(context)))
                                                                                        .then(argument("count", IntegerArgumentType.integer(1))
                                                                                                .executes(context -> assignDeposit(context, requiredItem(context, "item"), IntegerArgumentType.getInteger(context, "count"), false, getBlockPos(context)))))))))))
                                .then(literal("stockpile")
                                        .executes(context -> assignStockpile(context, true))
                                        .then(literal("include_tools")
                                                .executes(context -> assignStockpile(context, false))))
                                .then(literal("withdraw")
                                        .then(argument("item", ResourceLocationArgument.id())
                                                .executes(context -> assignWithdraw(context, null, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignWithdraw(context, null, IntegerArgumentType.getInteger(context, "count")))))
                                        .then(literal("at")
                                                .then(argument("x", IntegerArgumentType.integer())
                                                        .then(argument("y", IntegerArgumentType.integer())
                                                                .then(argument("z", IntegerArgumentType.integer())
                                                                        .then(argument("item", ResourceLocationArgument.id())
                                                                                .executes(context -> assignWithdraw(context, getBlockPos(context), 1))
                                                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                                                        .executes(context -> assignWithdraw(context, getBlockPos(context), IntegerArgumentType.getInteger(context, "count"))))))))))
                                .then(literal("build")
                                        .then(argument("blueprint", StringArgumentType.word())
                                                .then(argument("x", IntegerArgumentType.integer())
                                                        .then(argument("y", IntegerArgumentType.integer())
                                                                .then(argument("z", IntegerArgumentType.integer())
                                                                        .executes(context -> assignBuild(context, false, false))
                                                                        .then(literal("flatten")
                                                                                .executes(context -> assignBuild(context, false, true))))))
                                                .then(literal("auto_site")
                                                        .executes(context -> assignBuild(context, true, false))
                                                        .then(literal("flatten")
                                                                .executes(context -> assignBuild(context, true, true))))))))
                .then(literal("status")
                        .then(botName()
                                .executes(AIBotTaskSubcommand::status)))
                .then(literal("pause")
                        .then(botName()
                                .executes(AIBotTaskSubcommand::pause)))
                .then(literal("resume")
                        .then(botName()
                                .executes(AIBotTaskSubcommand::resume)))
                .then(literal("abort")
                        .then(botName()
                                .executes(AIBotTaskSubcommand::abort)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> botName() {
        return argument("name", StringArgumentType.word());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> blockPosArgs(Command<CommandSourceStack> command) {
        return argument("x", IntegerArgumentType.integer())
                .then(argument("y", IntegerArgumentType.integer())
                        .then(argument("z", IntegerArgumentType.integer())
                                .executes(command)));
    }

    private static int assignMove(CommandContext<CommandSourceStack> context) {
        return assign(context, bot -> new MoveTask(bot, getBlockPos(context)));
    }

    private static int assignForage(CommandContext<CommandSourceStack> context, int count) {
        return assign(context, bot -> new GatherQuotaTask(Items.SWEET_BERRIES, count));
    }

    private static int assignAttack(CommandContext<CommandSourceStack> context, int count) {
        return assign(context, bot -> new CombatTask(
                BuiltInRegistries.ENTITY_TYPE.getValue(ResourceLocationArgument.getId(context, "entity_type")),
                count,
                io.github.greytaiwolf.fakeaiplayer.AIBotConfig.get().combat().retreatHp()));
    }

    private static int assignMine(CommandContext<CommandSourceStack> context, int count) {
        return assign(context, bot -> {
            Block block = BuiltInRegistries.BLOCK.getValue(ResourceLocationArgument.getId(context, "block"));
            return OreScan.isOreBlock(block) ? new OreDigTask(OreScan.oreFamily(block), count) : new MineTask(block, count);
        });
    }

    private static int assignGather(CommandContext<CommandSourceStack> context, int count) {
        return assign(context, bot -> new GatherQuotaTask(
                BuiltInRegistries.ITEM.getValue(ResourceLocationArgument.getId(context, "item")),
                count));
    }

    private static int assignStripMine(CommandContext<CommandSourceStack> context, int length, int spacing, BlockPos depot) {
        return assign(context, bot -> new StripMineTask(direction(context), length, spacing, depot, Set.of()));
    }

    private static int assignMineVein(CommandContext<CommandSourceStack> context, Block ore) {
        return assign(context, bot -> StripMineTask.mineNearbyVein(ore == null ? Set.of() : Set.of(ore)));
    }

    private static int assignCraft(CommandContext<CommandSourceStack> context, int count) {
        return assign(context, bot -> new CraftTask(requiredItem(context, "item"), count));
    }

    private static int assignEat(CommandContext<CommandSourceStack> context) {
        return assign(context, bot -> new EatTask());
    }

    private static int assignSleep(CommandContext<CommandSourceStack> context) {
        return assign(context, bot -> new SleepTask());
    }

    private static int assignLightArea(CommandContext<CommandSourceStack> context, int radius, int maxTorches) {
        return assign(context, bot -> new LightAreaTask(radius, maxTorches));
    }

    private static int assignFarm(CommandContext<CommandSourceStack> context, int radius, boolean keepTending) {
        return assign(context, bot -> {
            FarmAction.CropSpec spec = cropSpec(context);
            return new FarmTask(getBlockPos(context), radius, spec.seed(), spec.crop(), keepTending, false);
        });
    }

    private static int assignHarvest(CommandContext<CommandSourceStack> context, int radius) {
        return assign(context, bot -> {
            FarmAction.CropSpec spec = cropSpec(context);
            return new FarmTask(getBlockPos(context), radius, spec.seed(), spec.crop(), false, true);
        });
    }

    private static int assignBreed(CommandContext<CommandSourceStack> context, int pairs) {
        return assign(context, bot -> new BreedTask(
                BuiltInRegistries.ENTITY_TYPE.getValue(ResourceLocationArgument.getId(context, "entity_type")),
                pairs));
    }

    private static int assignSmelt(CommandContext<CommandSourceStack> context, int count) {
        return assign(context, bot -> new SmeltTask(
                requiredItem(context, "input_item"),
                requiredItem(context, "output_item"),
                count));
    }

    private static int assignDepositAt(CommandContext<CommandSourceStack> context, Item item, int count, boolean allExceptTools) {
        return assignDeposit(context, item, count, allExceptTools, getBlockPos(context));
    }

    private static int assignDeposit(CommandContext<CommandSourceStack> context,
                                     Item item,
                                     int count,
                                     boolean allExceptTools,
                                     BlockPos pos) {
        return assign(context, bot -> ContainerTask.deposit(pos, item, count, allExceptTools));
    }

    private static int assignWithdraw(CommandContext<CommandSourceStack> context, BlockPos pos, int count) {
        return assign(context, bot -> ContainerTask.withdraw(pos, requiredItem(context, "item"), count));
    }

    private static int assignStockpile(CommandContext<CommandSourceStack> context, boolean allExceptTools) {
        return assign(context, bot -> new StockpileTask(allExceptTools));
    }

    private static int assignBuild(CommandContext<CommandSourceStack> context, boolean autoSite, boolean flatten) {
        return assign(context, bot -> {
            try {
                return new BuildTask(
                        BlueprintLoader.load(StringArgumentType.getString(context, "blueprint")),
                        autoSite ? null : getBlockPos(context),
                        autoSite,
                        flatten);
            } catch (IOException exception) {
                throw new IllegalArgumentException(exception.getMessage(), exception);
            }
        });
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context, BotAuthorizationPolicy.Operation.VIEW, "status");
        if (bot.isEmpty()) {
            return 0;
        }
        TaskStatus status = TaskManager.INSTANCE.status(bot.get());
        context.getSource().sendSuccess(() -> Component.literal("[FakeAiPlayer] task "
                + status.name()
                + " state=" + status.state()
                + " progress=" + String.format(java.util.Locale.ROOT, "%.2f", status.progress())
                + " elapsed=" + status.elapsedTicks()
                + " desc=" + status.description()
                + (status.failureReason().isBlank() ? "" : " reason=" + status.failureReason())), false);
        return 1;
    }

    private static int abort(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context, BotAuthorizationPolicy.Operation.COMMAND, "abort");
        if (bot.isEmpty()) {
            return 0;
        }
        IntentController.INSTANCE.cancelAll(
                bot.get(), IntentController.ControlOrigin.PLAYER_COMMAND, "command_task_abort");
        context.getSource().sendSuccess(() -> Component.literal("[FakeAiPlayer] task aborted"), false);
        return 1;
    }

    private static int pause(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context, BotAuthorizationPolicy.Operation.COMMAND, "pause");
        if (bot.isEmpty()) {
            return 0;
        }
        IntentController.INSTANCE.pause(
                bot.get(), IntentController.ControlOrigin.PLAYER_COMMAND, "command_task_pause");
        return 1;
    }

    private static int resume(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context, BotAuthorizationPolicy.Operation.COMMAND, "resume");
        if (bot.isEmpty()) {
            return 0;
        }
        IntentController.INSTANCE.resume(
                bot.get(), IntentController.ControlOrigin.PLAYER_COMMAND, "command_task_resume");
        return 1;
    }

    private static int assign(CommandContext<CommandSourceStack> context, TaskFactory factory) {
        Optional<AIPlayerEntity> bot = getBot(context, BotAuthorizationPolicy.Operation.COMMAND, "assign");
        if (bot.isEmpty()) {
            return 0;
        }
        try {
            Task task = factory.create(bot.get());
            IntentController.INSTANCE.replace(
                    bot.get(),
                    IntentController.ControlOrigin.PLAYER_COMMAND,
                    "command_task_assign:" + task.name(),
                    () -> {
                        TaskManager.INSTANCE.assign(bot.get(), task,
                                TaskOrigin.of(TaskOrigin.Kind.PLAYER_COMMAND, "command_task_assign"));
                        return true;
                    });
            context.getSource().sendSuccess(() -> Component.literal("[FakeAiPlayer] task assigned: " + task.name()), false);
            return 1;
        } catch (RuntimeException exception) {
            context.getSource().sendFailure(Component.literal("[FakeAiPlayer] task assign failed: " + exception.getMessage()));
            return 0;
        }
    }

    private static Optional<AIPlayerEntity> getBot(CommandContext<CommandSourceStack> context,
                                                   BotAuthorizationPolicy.Operation operation,
                                                   String action) {
        String name = StringArgumentType.getString(context, "name");
        return BotAuthorizationGate.INSTANCE.resolveAuthorized(
                context.getSource(), name, operation, "command:task_" + action);
    }

    private static BlockPos getBlockPos(CommandContext<CommandSourceStack> context) {
        return new BlockPos(
                IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"));
    }

    private static Item requiredItem(CommandContext<CommandSourceStack> context, String name) {
        ResourceLocation id = ResourceLocationArgument.getId(context, name);
        return BuiltInRegistries.ITEM.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_item: " + id));
    }

    private static FarmAction.CropSpec cropSpec(CommandContext<CommandSourceStack> context) {
        ResourceLocation id = ResourceLocationArgument.getId(context, "crop");
        return FarmAction.cropSpec(id.toString());
    }

    private static Direction direction(CommandContext<CommandSourceStack> context) {
        String value = StringArgumentType.getString(context, "direction").toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "north", "n" -> Direction.NORTH;
            case "south", "s" -> Direction.SOUTH;
            case "east", "e" -> Direction.EAST;
            case "west", "w" -> Direction.WEST;
            case "down", "d", "up", "u" -> Direction.DOWN;
            default -> throw new IllegalArgumentException("unknown_direction: " + value);
        };
    }

    @FunctionalInterface
    private interface TaskFactory {
        Task create(AIPlayerEntity bot);
    }
}
