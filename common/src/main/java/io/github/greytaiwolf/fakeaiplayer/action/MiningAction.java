package io.github.greytaiwolf.fakeaiplayer.action;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.Level;

public final class MiningAction {
    private MiningAction() {
    }

    public static ActionResult startMining(AIPlayerEntity player, BlockPos pos, Direction face) {
        return player.getActionPack().startMining(pos, face);
    }

    public static ActionResult stopMining(AIPlayerEntity player) {
        player.getActionPack().stopMining();
        return ActionResult.SUCCESS;
    }

    public static ActionResult mineOnceInstant(AIPlayerEntity player, BlockPos pos, Direction face) {
        player.gameMode.handleBlockBreakAction(
                pos,
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                face,
                Level.MAX_ENTITY_SPAWN_Y,
                -1);
        return ActionResult.SUCCESS;
    }
}
