package io.github.greytaiwolf.fakeaiplayer.log;

import net.minecraft.core.BlockPos;

public final class LogFields {
    private LogFields() {
    }

    public static String pos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
