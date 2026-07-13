package io.github.greytaiwolf.fakeaiplayer.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationGate;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.action.BuildAction;
import io.github.greytaiwolf.fakeaiplayer.action.InteractAction;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.action.LookAction;
import io.github.greytaiwolf.fakeaiplayer.action.MiningAction;
import io.github.greytaiwolf.fakeaiplayer.action.MovementAction;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.AStarPathfinder;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.PathfindingResult;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class AIBotTestSubcommand {
    private AIBotTestSubcommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build(CommandBuildContext registryAccess) {
        return literal("test")
                .then(literal("look")
                        .then(botName()
                                .then(argument("x", DoubleArgumentType.doubleArg())
                                        .then(argument("y", DoubleArgumentType.doubleArg())
                                                .then(argument("z", DoubleArgumentType.doubleArg())
                                                        .executes(AIBotTestSubcommand::look))))))
                .then(literal("moveto")
                        .then(botName()
                                .then(argument("x", DoubleArgumentType.doubleArg())
                                        .then(argument("y", DoubleArgumentType.doubleArg())
                                                .then(argument("z", DoubleArgumentType.doubleArg())
                                                        .executes(AIBotTestSubcommand::moveTo))))))
                .then(literal("pathfind")
                        .then(botName()
                                .then(argument("x", IntegerArgumentType.integer())
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .executes(AIBotTestSubcommand::pathFind))))))
                .then(literal("pathto")
                        .then(botName()
                                .then(argument("x", IntegerArgumentType.integer())
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .executes(AIBotTestSubcommand::pathTo))))))
                .then(literal("cancelpath")
                        .then(botName()
                                .executes(AIBotTestSubcommand::stop)))
                .then(literal("stop")
                        .then(botName()
                                .executes(AIBotTestSubcommand::stop)))
                .then(literal("jump")
                        .then(botName()
                                .executes(AIBotTestSubcommand::jump)))
                .then(literal("mine")
                        .then(botName()
                                .then(argument("x", IntegerArgumentType.integer())
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .executes(AIBotTestSubcommand::mine))))))
                .then(literal("place")
                        .then(botName()
                                .then(argument("x", IntegerArgumentType.integer())
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .executes(AIBotTestSubcommand::place))))))
                .then(literal("give")
                        .then(botName()
                                .then(argument("item", ItemArgument.item(registryAccess))
                                        .executes(context -> give(context, 64))
                                        .then(argument("count", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> give(context, IntegerArgumentType.getInteger(context, "count")))))))
                .then(literal("select")
                        .then(botName()
                                .then(argument("slot", IntegerArgumentType.integer(0, 8))
                                        .executes(AIBotTestSubcommand::select))))
                .then(literal("inventory")
                        .then(botName()
                                .executes(AIBotTestSubcommand::inventory)))
                .then(literal("attack")
                        .then(botName()
                                .then(argument("target", EntityArgument.entity())
                                        .executes(AIBotTestSubcommand::attack))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> botName() {
        return argument("name", StringArgumentType.word());
    }

    private static int look(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        Vec3 target = getVec3d(context);
        LookAction.lookAt(bot.get(), target);
        context.getSource().sendSuccess(() -> Component.literal("[FakeAiPlayer] look started"), false);
        return 1;
    }

    private static int moveTo(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        Vec3 target = getVec3d(context);
        MovementAction.startWalkTo(bot.get(), target);
        context.getSource().sendSuccess(() -> Component.literal("[FakeAiPlayer] moveto started"), false);
        return 1;
    }

    private static int pathFind(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        AIPlayerEntity player = bot.get();
        PathfindingResult result = new AStarPathfinder(player.serverLevel(), player.blockPosition(), getBlockPos(context)).findPath();
        String message = "[FakeAiPlayer] pathfind success=" + result.success()
                + ", reason=" + result.reason()
                + ", nodes=" + result.nodesExplored()
                + ", ms=" + result.elapsedMs()
                + ", length=" + result.path().size();
        if (result.success()) {
            context.getSource().sendSuccess(() -> Component.literal(message), false);
            return 1;
        }
        context.getSource().sendFailure(Component.literal(message));
        return 0;
    }

    private static int pathTo(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        return sendResult(context.getSource(), "pathto", MovementAction.startPathTo(bot.get(), getBlockPos(context)));
    }

    private static int stop(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        MovementAction.stopAll(bot.get());
        context.getSource().sendSuccess(() -> Component.literal("[FakeAiPlayer] stopped"), false);
        return 1;
    }

    private static int jump(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        MovementAction.jumpOnce(bot.get());
        context.getSource().sendSuccess(() -> Component.literal("[FakeAiPlayer] jump queued"), false);
        return 1;
    }

    private static int mine(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        AIPlayerEntity player = bot.get();
        BlockPos pos = getBlockPos(context);
        MiningAction.startMining(player, pos, faceFromPlayer(player, pos));
        context.getSource().sendSuccess(() -> Component.literal("[FakeAiPlayer] mine started"), false);
        return 1;
    }

    private static int place(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        ActionResult result = BuildAction.placeBlockAt(bot.get(), getBlockPos(context));
        return sendResult(context.getSource(), "place", result);
    }

    private static int give(CommandContext<CommandSourceStack> context, int count) throws CommandSyntaxException {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        ItemInput itemArgument = ItemArgument.getItem(context, "item");
        ItemStack stack = itemArgument.createItemStack(count, false);
        return sendResult(context.getSource(), "give", InventoryAction.giveItem(bot.get(), stack));
    }

    private static int select(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        int slot = IntegerArgumentType.getInteger(context, "slot");
        return sendResult(context.getSource(), "select", InventoryAction.selectHotbar(bot.get(), slot));
    }

    private static int inventory(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        Map<String, Integer> summary = InventoryAction.summarize(bot.get());
        String text = summary.isEmpty()
                ? "(empty)"
                : summary.entrySet().stream()
                .map(entry -> entry.getKey() + " x " + entry.getValue())
                .collect(Collectors.joining(", "));
        context.getSource().sendSuccess(() -> Component.literal("[FakeAiPlayer] inventory: " + text), false);
        return summary.size();
    }

    private static int attack(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        Entity target = EntityArgument.getEntity(context, "target");
        return sendResult(context.getSource(), "attack", InteractAction.attackEntity(bot.get(), target));
    }

    private static Optional<AIPlayerEntity> getBot(CommandContext<CommandSourceStack> context) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(context.getSource(), "command:test")) {
            return Optional.empty();
        }
        String name = StringArgumentType.getString(context, "name");
        return BotAuthorizationGate.INSTANCE.resolveAuthorized(
                context.getSource(), name, BotAuthorizationPolicy.Operation.ADMIN, "command:test_target");
    }

    private static int sendResult(CommandSourceStack source, String action, ActionResult result) {
        if (result.isSuccess() || result.isInProgress()) {
            source.sendSuccess(() -> Component.literal("[FakeAiPlayer] " + action + " " + result.status().name().toLowerCase()), false);
            return 1;
        }
        source.sendFailure(Component.literal("[FakeAiPlayer] " + action + " failed: " + result.reason()));
        return 0;
    }

    private static Vec3 getVec3d(CommandContext<CommandSourceStack> context) {
        return new Vec3(
                DoubleArgumentType.getDouble(context, "x"),
                DoubleArgumentType.getDouble(context, "y"),
                DoubleArgumentType.getDouble(context, "z"));
    }

    private static BlockPos getBlockPos(CommandContext<CommandSourceStack> context) {
        return new BlockPos(
                IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"));
    }

    private static Direction faceFromPlayer(AIPlayerEntity player, BlockPos pos) {
        Vec3 fromBlockToEye = player.getEyePosition().subtract(pos.getCenter());
        return Direction.getApproximateNearest(fromBlockToEye);
    }
}
