package io.github.greytaiwolf.fakeaiplayer.brain;

import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationGate;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.regex.Pattern;

public final class ChatCaptureListener {
    private static final Pattern MENTION = Pattern.compile("@(\\w+)\\s+(.+)");
    private static final Pattern CREDENTIAL_LIKE = Pattern.compile(
            "(?i)(?<![a-z0-9_-])(?:sk-[a-z0-9_-]{12,}|bearer\\s+\\S{12,})(?![a-z0-9_-])");

    private ChatCaptureListener() {
    }

    public static void handle(ServerPlayer sender, String text) {
        var matcher = MENTION.matcher(text);
        if (!matcher.find()) {
            return;
        }
        String targetName = matcher.group(1);
        String body = matcher.group(2);
        if (looksLikeCredential(body)) {
            sender.sendSystemMessage(Component.literal(
                    "[FakeAiPlayer] 检测到疑似 API Key，已拒绝发送给 Bot；请使用 /fakeaiplayer ai setup <Bot名称>。"));
            return;
        }
        AIPlayerManager.INSTANCE.getByName(targetName).ifPresent(bot -> {
            if (!BotAuthorizationGate.INSTANCE.authorize(
                    sender, bot, BotAuthorizationPolicy.Operation.COMMAND, "chat:@bot")) {
                return;
            }
            BotLog.comm(bot, "chat_in", "sender", sender.getGameProfile().getName(), "text", body);
            if (io.github.greytaiwolf.fakeaiplayer.runtime.IntentController.INSTANCE.routePlayerControlPhrase(
                    bot, io.github.greytaiwolf.fakeaiplayer.runtime.IntentController.ControlOrigin.PLAYER_COMMAND, body)) {
                return;
            }
            BrainCoordinator.INSTANCE.handleMessage(bot, sender.getGameProfile().getName(), body);
        });
    }

    static boolean looksLikeCredential(String text) {
        return text != null && CREDENTIAL_LIKE.matcher(text).find();
    }
}
