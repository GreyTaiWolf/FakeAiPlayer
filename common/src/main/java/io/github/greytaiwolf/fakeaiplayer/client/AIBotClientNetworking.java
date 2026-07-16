package io.github.greytaiwolf.fakeaiplayer.client;

import io.github.greytaiwolf.fakeaiplayer.network.payload.BotChatS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotSnapshotS2C;
import io.github.greytaiwolf.fakeaiplayer.building.preview.client.BuildingPreviewClientState;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewBeginS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewChunkS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewClearS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewCommitS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewReadyC2S;
import io.github.greytaiwolf.fakeaiplayer.client.credential.ClientCredentialManager;
import io.github.greytaiwolf.fakeaiplayer.client.screen.BotAiSetupScreen;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotAiCredentialStatusS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.OpenBotAiSetupS2C;
import net.minecraft.client.Minecraft;

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

    public static void handle(OpenBotAiSetupS2C payload) {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(new BotAiSetupScreen(payload.botName(), payload.nonce()));
    }

    public static void handle(BotAiCredentialStatusS2C payload) {
        Minecraft client = Minecraft.getInstance();
        ClientCredentialManager.StorageResult forgetResult = ClientCredentialManager.StorageResult.OK;
        if (payload.forgetLocal()) {
            String scope = ClientCredentialManager.currentServerScope(client);
            forgetResult = scope.isBlank()
                    ? ClientCredentialManager.StorageResult.UNAVAILABLE
                    : ClientCredentialManager.forget(scope, payload.botName());
        }
        if (client.screen instanceof BotAiSetupScreen screen) {
            screen.applyStatus(payload, forgetResult);
        } else if (client.player != null
                && BotAiCredentialStatusS2C.NO_NONCE.equals(payload.nonce())) {
            client.player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            "message.fakeaiplayer.ai_credential_status",
                            payload.botName(),
                            BotAiSetupScreen.statusMessage(payload.statusKey())),
                    false);
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
