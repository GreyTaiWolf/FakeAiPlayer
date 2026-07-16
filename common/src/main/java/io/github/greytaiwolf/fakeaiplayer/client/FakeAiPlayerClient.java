package io.github.greytaiwolf.fakeaiplayer.client;

import io.github.greytaiwolf.fakeaiplayer.client.screen.BotPanelScreen;
import io.github.greytaiwolf.fakeaiplayer.building.preview.client.BuildingPreviewClientState;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewCancelC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewConfirmC2S;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import io.github.greytaiwolf.fakeaiplayer.client.credential.ClientCredentialManager;

/** Loader-neutral client tick handler. */
public final class FakeAiPlayerClient {
    private FakeAiPlayerClient() {
    }

    public static void onClientTick(Minecraft client) {
        ClientCredentialManager.tick(client);
        handleBuildingPreviewKey(client);
        BotPanelScreen.Mode mode = AIBotKeyBindings.pollToggle(client);
        if (mode == null) {
            return;
        }
        if (client.screen instanceof BotPanelScreen panel && panel.mode() == mode) {
            client.setScreen(null);
        } else {
            client.setScreen(new BotPanelScreen(mode));
        }
    }

    private static void handleBuildingPreviewKey(Minecraft client) {
        AIBotKeyBindings.PreviewAction action = AIBotKeyBindings.pollPreviewAction();
        if (action == null || client.screen != null) {
            return;
        }
        BuildingPreviewClientState.Snapshot preview =
                BuildingPreviewClientState.INSTANCE.active().orElse(null);
        if (preview == null || client.player == null) {
            return;
        }
        if (action == AIBotKeyBindings.PreviewAction.CONFIRM) {
            if (!ClientNetworkServices.canSend(BuildingPreviewConfirmC2S.ID)) {
                client.player.displayClientMessage(
                        Component.literal("[FakeAiPlayer] 服务器不支持建筑投影确认。"), false);
                return;
            }
            ClientNetworkServices.send(new BuildingPreviewConfirmC2S(
                    preview.sessionId(), preview.previewHash(), preview.transformRevision()));
            client.player.displayClientMessage(
                    Component.literal("[FakeAiPlayer] 已向服务器提交建筑确认，正在重新校验。"), false);
            return;
        }
        if (!ClientNetworkServices.canSend(BuildingPreviewCancelC2S.ID)) {
            client.player.displayClientMessage(
                    Component.literal("[FakeAiPlayer] 服务器不支持建筑投影取消。"), false);
            return;
        }
        ClientNetworkServices.send(new BuildingPreviewCancelC2S(preview.sessionId()));
    }
}
