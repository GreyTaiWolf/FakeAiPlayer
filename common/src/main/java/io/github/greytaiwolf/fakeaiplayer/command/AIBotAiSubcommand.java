package io.github.greytaiwolf.fakeaiplayer.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationGate;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy;
import io.github.greytaiwolf.fakeaiplayer.brain.BotAiConnectionService;
import io.github.greytaiwolf.fakeaiplayer.brain.BotApiCredentialRegistry;
import io.github.greytaiwolf.fakeaiplayer.brain.BrainCoordinator;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.network.AIBotServerNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** In-game setup and lifecycle commands for a client-owned, per-bot API credential. */
public final class AIBotAiSubcommand {
    private AIBotAiSubcommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("ai")
                .then(literal("setup")
                        .then(botName().executes(context -> setup(
                                context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("status")
                        .then(botName().executes(context -> status(
                                context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("test")
                        .then(botName().executes(context -> test(
                                context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("disconnect")
                        .then(botName().executes(context -> disconnect(
                                context.getSource(), StringArgumentType.getString(context, "name")))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> botName() {
        return argument("name", StringArgumentType.word());
    }

    private static int setup(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[FakeAiPlayer] API Key 保存在玩家客户端，请由游戏内玩家执行该命令。"));
            return 0;
        }
        Optional<AIPlayerEntity> bot = resolve(source, name, "command:ai_setup");
        if (bot.isEmpty()) {
            return 0;
        }
        if (!AIBotServerNetworking.INSTANCE.beginBotAiSetup(player, bot.get())) {
            source.sendFailure(Component.literal("[FakeAiPlayer] 客户端未安装支持安全 Key 输入的同版本模组。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] 已打开 " + name + " 的安全 API 设置窗口。"), false);
        return 1;
    }

    private static int status(CommandSourceStack source, String name) {
        Optional<AIPlayerEntity> bot = resolve(source, name, "command:ai_status");
        if (bot.isEmpty()) {
            return 0;
        }
        BotApiCredentialRegistry.CredentialState state =
                BrainCoordinator.INSTANCE.botApiCredentialStatus(bot.get().getUUID());
        String text = switch (state.status()) {
            case BOT_KEY -> "玩家客户端 Key 已绑定到本次连接";
            case SERVER_FALLBACK -> "正在使用服务器默认 Key";
            case MISSING -> "尚未连接 API";
        };
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] " + name + " AI: " + text), false);
        return state.usable() ? 1 : 0;
    }

    private static int test(CommandSourceStack source, String name) {
        Optional<AIPlayerEntity> bot = resolve(source, name, "command:ai_test");
        if (bot.isEmpty()) {
            return 0;
        }
        BotApiCredentialRegistry.CredentialState state =
                BrainCoordinator.INSTANCE.botApiCredentialStatus(bot.get().getUUID());
        if (!state.usable()) {
            source.sendFailure(Component.literal("[FakeAiPlayer] " + name + " 尚未设置 API Key。"));
            return 0;
        }
        boolean queued = BotAiConnectionService.INSTANCE.verify(
                bot.get(), state.generation(), result -> {
                    if (result.connected()) {
                        source.sendSuccess(() -> Component.literal(
                                "[FakeAiPlayer] " + name + " API 连接测试成功。"), false);
                    } else {
                        source.sendFailure(Component.literal(
                                "[FakeAiPlayer] " + name + " API 连接失败: " + friendlyStatus(result.statusCode())));
                    }
                });
        if (!queued) {
            source.sendFailure(Component.literal("[FakeAiPlayer] API 测试队列繁忙，请稍后再试。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] 正在测试 " + name + " 的 API 连接。"), false);
        return 1;
    }

    private static int disconnect(CommandSourceStack source, String name) {
        Optional<AIPlayerEntity> bot = resolve(source, name, "command:ai_disconnect");
        if (bot.isEmpty()) {
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        AIBotServerNetworking.INSTANCE.disconnectBotAiCredential(player, bot.get());
        String detail = player == null
                ? "已断开玩家 Key；控制台无法删除玩家客户端上的已保存 Key。"
                : "已断开玩家 Key，并从当前客户端配置中删除。";
        source.sendSuccess(() -> Component.literal(
                "[FakeAiPlayer] " + name + " " + detail), false);
        return 1;
    }

    private static Optional<AIPlayerEntity> resolve(CommandSourceStack source, String name, String channel) {
        return BotAuthorizationGate.INSTANCE.resolveAuthorized(
                source, name, BotAuthorizationPolicy.Operation.ADMIN, channel);
    }

    private static String friendlyStatus(String code) {
        return switch (code) {
            case "auth_error" -> "Key 无效或没有模型权限";
            case "rate_limited" -> "服务商限流或额度不足";
            case "timeout" -> "请求超时";
            case "provider_unavailable" -> "模型服务暂时不可用";
            case "bad_response" -> "模型返回格式无效";
            case "network_error" -> "服务器无法连接模型服务";
            case "busy" -> "请求队列繁忙";
            default -> "连接失败";
        };
    }
}
