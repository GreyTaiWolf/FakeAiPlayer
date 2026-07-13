package io.github.greytaiwolf.fakeaiplayer.entity;

import com.mojang.authlib.GameProfile;
import io.github.greytaiwolf.fakeaiplayer.action.ActionPack;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class AIPlayerEntity extends ServerPlayer {
    private final ActionPack actionPack = new ActionPack(this);

    public AIPlayerEntity(MinecraftServer server,
                          ServerLevel world,
                          GameProfile profile,
                          ClientInformation clientOptions) {
        super(server, world, profile, clientOptions);
    }

    @Override
    public void tick() {
        if (this.server.getTickCount() % 10 == 0 && this.connection != null) {
            this.connection.resetPosition();
            this.serverLevel().getChunkSource().move(this);
        }

        try {
            super.tick();
            this.doTick();
            this.actionPack.onUpdate();
        } catch (NullPointerException exception) {
            BotLog.error(this, "tick_npe_swallowed", exception);
        }
    }

    @Override
    public String getIpAddress() {
        return "127.0.0.1";
    }

    public void reviveForAIBotSpawn() {
        this.unsetRemoved();
    }

    public ActionPack getActionPack() {
        return actionPack;
    }
}
