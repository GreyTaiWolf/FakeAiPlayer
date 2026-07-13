package io.github.greytaiwolf.fakeaiplayer.mixin;

import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractVillager.class)
public interface MerchantEntityInvokerMixin {
    @Invoker("rewardTradeXp")
    void fakeaiplayer$invokeAfterUsing(MerchantOffer offer);
}
