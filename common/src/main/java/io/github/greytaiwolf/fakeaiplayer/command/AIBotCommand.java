package io.github.greytaiwolf.fakeaiplayer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationGate;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class AIBotCommand {
    private AIBotCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(literal("fakeaiplayer")
                .then(literal("spawn")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> spawn(context.getSource(), StringArgumentType.getString(context, "name"), "worker"))
                                .then(argument("role", StringArgumentType.word())
                                        .executes(context -> spawn(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "role"))))))
                .then(literal("role")
                        .then(argument("name", StringArgumentType.word())
                                .then(argument("role", StringArgumentType.word())
                                        .executes(context -> role(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "role"))))))
                .then(literal("despawn")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> despawn(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("list")
                        .executes(context -> list(context.getSource())))
                .then(AIBotBrainSubcommand.build())
                .then(AIBotAiSubcommand.build())
                .then(AIBotLogSubcommand.build())
                .then(AIBotPersistSubcommand.build())
                .then(AIBotJobSubcommand.build())
                .then(AIBotMemorySubcommand.build())
                .then(AIBotObserveSubcommand.profile())
                .then(AIBotObserveSubcommand.replay())
                .then(AIBotObserveSubcommand.tps())
                .then(AIBotTaskSubcommand.build())
                .then(AIBotBuildingSubcommand.build())
                .then(AIBotDeplintSubcommand.build())
                .then(AIBotSnapshotSubcommand.build()));
    }

    private static int spawn(CommandSourceStack source, String name, String role) {
        if (!BotAuthorizationGate.INSTANCE.canProvisionPersonalBot(source, "command:spawn")) {
            return 0;
        }
        ServerPlayer executor = source.getPlayer();
        GameType gameMode = executor == null ? GameType.SURVIVAL : executor.gameMode.getGameModeForPlayer();
        UUID ownerUuid = executor == null ? null : executor.getUUID();
        if (ownerUuid != null && AIPlayerManager.INSTANCE.botOf(ownerUuid).isPresent()) {
            source.sendFailure(Component.literal("[FakeAiPlayer] 你已经有一个 AI 助手了,请先 /fakeaiplayer despawn <名字>"));
            return 0;
        }
        var rotation = source.getRotation();
        var spawned = AIPlayerManager.INSTANCE.spawn(
                source.getServer(),
                name,
                source.getLevel(),
                source.getPosition(),
                rotation.y,
                rotation.x,
                gameMode,
                ownerUuid);

        if (spawned.isPresent()) {
            AIPlayerManager.INSTANCE.setRole(spawned.get(), role);
            source.sendSuccess(() -> Component.literal("[FakeAiPlayer] Spawned " + name + " role=" + AIPlayerManager.INSTANCE.role(spawned.get())), true);
            return 1;
        }

        source.sendFailure(Component.literal("[FakeAiPlayer] 无法生成 " + name + " (名称已存在或已达到限制)"));
        return 0;
    }

    private static int role(CommandSourceStack source, String name, String role) {
        var bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                source, name, BotAuthorizationPolicy.Operation.ADMIN, "command:role");
        if (bot.isEmpty()) {
            return 0;
        }
        if (io.github.greytaiwolf.fakeaiplayer.task.TaskManager.INSTANCE
                .hasRuntimeRecoveryLock(bot.get())) {
            source.sendFailure(Component.literal(
                    "[FakeAiPlayer] role change rejected: runtime recovery is read-only"));
            return 0;
        }
        AIPlayerManager.INSTANCE.setRole(bot.get(), role);
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] " + name + " role=" + AIPlayerManager.INSTANCE.role(bot.get())), false);
        return 1;
    }

    private static int despawn(CommandSourceStack source, String name) {
        var bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                source, name, BotAuthorizationPolicy.Operation.ADMIN, "command:despawn");
        if (bot.isEmpty()) {
            return 0;
        }
        if (io.github.greytaiwolf.fakeaiplayer.task.TaskManager.INSTANCE
                .hasRuntimeRecoveryLock(bot.get())) {
            source.sendFailure(Component.literal(
                    "[FakeAiPlayer] 运行时存档处于只读恢复保护中，无法删除 Bot；修复 runtime.json 后请重启服务器"));
            return 0;
        }
        boolean removed = AIPlayerManager.INSTANCE.despawn(source.getServer(), name);
        if (removed) {
            source.sendSuccess(() -> Component.literal("[FakeAiPlayer] 已删除 " + name), true);
            return 1;
        }

        source.sendFailure(Component.literal("[FakeAiPlayer] 找不到 Bot: " + name));
        return 0;
    }

    private static int list(CommandSourceStack source) {
        var bots = AIPlayerManager.INSTANCE.all().stream()
                .filter(bot -> BotAuthorizationGate.INSTANCE.canView(source, bot))
                .toList();
        String names = bots.stream()
                .map(player -> player.getGameProfile().getName() + "(" + AIPlayerManager.INSTANCE.role(player) + ")")
                .collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] " + bots.size() + " bot(s): " + names), false);
        return bots.size();
    }
}
