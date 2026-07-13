package io.github.greytaiwolf.fakeaiplayer.brain;

import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationGate;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import net.minecraft.server.level.ServerPlayer;

import java.util.regex.Pattern;

public final class ChatCaptureListener {
    private static final Pattern MENTION = Pattern.compile("@(\\w+)\\s+(.+)");

    private ChatCaptureListener() {
    }

    public static void handle(ServerPlayer sender, String text) {
        var matcher = MENTION.matcher(text);
        if (!matcher.find()) {
            return;
        }
        String targetName = matcher.group(1);
        String body = matcher.group(2);
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
}
