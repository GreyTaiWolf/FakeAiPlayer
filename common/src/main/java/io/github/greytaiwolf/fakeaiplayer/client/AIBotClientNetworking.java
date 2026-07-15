package io.github.greytaiwolf.fakeaiplayer.client;

import io.github.greytaiwolf.fakeaiplayer.network.payload.BotChatS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotSnapshotS2C;
import io.github.greytaiwolf.fakeaiplayer.building.preview.client.BuildingPreviewClientState;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewBeginS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewChunkS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewClearS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewCommitS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewReadyC2S;

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

    public static void handle(BuildingPreviewBeginS2C payload) {
        BuildingPreviewClientState.INSTANCE.handle(payload);
    }

    public static void handle(BuildingPreviewChunkS2C payload) {
        BuildingPreviewClientState.INSTANCE.handle(payload);
    }

    public static void handle(BuildingPreviewCommitS2C payload) {
        BuildingPreviewClientState.INSTANCE.handle(payload);
        BuildingPreviewClientState.INSTANCE.active()
                .filter(snapshot -> snapshot.sessionId().equals(payload.sessionId())
                        && snapshot.previewHash().equals(payload.previewHash())
                        && snapshot.transformRevision() == payload.transformRevision())
                .ifPresent(snapshot -> {
                    if (ClientNetworkServices.canSend(BuildingPreviewReadyC2S.ID)) {
                        ClientNetworkServices.send(new BuildingPreviewReadyC2S(
                                snapshot.sessionId(), snapshot.previewHash(), snapshot.transformRevision()));
                    }
                });
    }

    public static void handle(BuildingPreviewClearS2C payload) {
        BuildingPreviewClientState.INSTANCE.handle(payload);
    }
}
