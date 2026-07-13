package io.github.greytaiwolf.fakeaiplayer.mixin;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.network.AINetworkHandler;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerList.class)
public abstract class PlayerManagerFakePlayerMixin {
    @Shadow
    @Final
    private MinecraftServer server;

    @Redirect(
            method = "placeNewPlayer",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"
            )
    )
    private ServerGamePacketListenerImpl fakeaiplayer$replaceNetworkHandler(MinecraftServer server,
                                                                  Connection connection,
                                                                  ServerPlayer player,
                                                                  CommonListenerCookie clientData) {
        if (player instanceof AIPlayerEntity fakePlayer) {
            return new AINetworkHandler(this.server, connection, fakePlayer, clientData);
        }
        return new ServerGamePacketListenerImpl(this.server, connection, player, clientData);
    }
}
