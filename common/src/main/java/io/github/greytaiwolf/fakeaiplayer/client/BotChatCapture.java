package io.github.greytaiwolf.fakeaiplayer.client;

import io.github.greytaiwolf.fakeaiplayer.network.payload.BotChatS2C;
import net.minecraft.network.chat.Component;

public final class BotChatCapture {
    private BotChatCapture() {
    }

    public static void handle(Component message) {
        if (ClientNetworkServices.canSend(BotChatS2C.ID)) {
            return;
        }
        String text = message.getString();
        String target = BotClientState.INSTANCE.targetBot();
        String botPrefix = "<" + target + "> ";
        String aiBotPrefix = "[AI] " + botPrefix;
        if (text.startsWith("[FakeAiPlayer] ")) {
            BotClientState.INSTANCE.addTranscript("system", text.substring("[FakeAiPlayer] ".length()));
        } else if (text.contains(target + " is thinking") || text.contains("brain error:")) {
            BotClientState.INSTANCE.addTranscript("system", text);
        } else if (text.startsWith(aiBotPrefix)) {
            BotClientState.INSTANCE.addTranscript("bot", text.substring(aiBotPrefix.length()));
        } else if (text.startsWith(botPrefix)) {
            BotClientState.INSTANCE.addTranscript("bot", text.substring(botPrefix.length()));
        }
    }
}
