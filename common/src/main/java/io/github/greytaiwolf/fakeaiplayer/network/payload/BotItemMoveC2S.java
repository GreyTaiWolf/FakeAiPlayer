package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端请求在玩家与 AI 之间移动物品。
 * direction:0=TAKE(从 AI 背包 slot 拿到玩家背包)、1=PUT(把玩家背包 slot 放进 AI 背包)。
 * slot:源容器里的槽位下标(TAKE=AI main 槽,PUT=玩家 inventory main 槽)。
 * amount:期望移动数量(<=0 视为整堆;服务端按实际可移动量裁剪)。
 */
public record BotItemMoveC2S(String botName, int direction, int slot, int amount) implements CustomPacketPayload {
    public static final int TAKE = 0;
    public static final int PUT = 1;

    public static final Type<BotItemMoveC2S> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "item_move"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BotItemMoveC2S> CODEC =
            StreamCodec.ofMember(BotItemMoveC2S::write, BotItemMoveC2S::new);

    private BotItemMoveC2S(RegistryFriendlyByteBuf buf) {
        this(buf.readUtf(PayloadLimits.BOT_NAME_LENGTH), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(botName, PayloadLimits.BOT_NAME_LENGTH);
        buf.writeVarInt(direction);
        buf.writeVarInt(slot);
        buf.writeVarInt(amount);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
