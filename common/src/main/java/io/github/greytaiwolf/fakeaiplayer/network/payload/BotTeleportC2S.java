package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端请求传送。
 * direction:0=TO_AI(把玩家传送到 AI 附近可站立方块)、1=RECALL_AI(把 AI 传送到玩家附近可站立方块)。
 */
public record BotTeleportC2S(String botName, int direction) implements CustomPacketPayload {
    public static final int TO_AI = 0;
    public static final int RECALL_AI = 1;

    public static final Type<BotTeleportC2S> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "teleport"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BotTeleportC2S> CODEC =
            StreamCodec.ofMember(BotTeleportC2S::write, BotTeleportC2S::new);

    private BotTeleportC2S(RegistryFriendlyByteBuf buf) {
        this(buf.readUtf(PayloadLimits.BOT_NAME_LENGTH), buf.readVarInt());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(botName, PayloadLimits.BOT_NAME_LENGTH);
        buf.writeVarInt(direction);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
