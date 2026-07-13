package io.github.greytaiwolf.fakeaiplayer.client;

import io.github.greytaiwolf.fakeaiplayer.network.payload.BotChatS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotSnapshotS2C;

public final class AIBotClientNetworking {
    private AIBotClientNetworking() {
    }

    public static void handle(BotSnapshotS2C payload) {
        BotClientState.INSTANCE.setSnapshot(payload);
    }

    public static void handle(BotChatS2C payload) {
        if (BotClientState.INSTANCE.matchesTarget(payload.botName())) {
            BotClientState.INSTANCE.addTranscript(payload.role(), payload.text());
        }
    }
}
