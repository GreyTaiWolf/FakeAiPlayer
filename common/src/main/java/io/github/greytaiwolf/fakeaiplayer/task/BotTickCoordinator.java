package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.coordination.IdleCoordinator;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.goal.GoalExecutor;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.observe.TpsGuard;
import net.minecraft.server.MinecraftServer;

public final class BotTickCoordinator {
    public static final BotTickCoordinator INSTANCE = new BotTickCoordinator();

    private BotTickCoordinator() {
    }

    public void tick(MinecraftServer server) {
        int tick = server.getTickCount();
        TpsGuard guard = TpsGuard.INSTANCE;
        boolean runDanger = tick % guard.dangerScanInterval() == 0;
        boolean runBackground = tick % guard.scanInterval() == 0;
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            // SAFE-1:环境安全网最先跑;若正在自救(溺水/岩浆)则本 tick 接管,跳过其它检查。
            if (NavSafetyNet.INSTANCE.tickBot(server, bot)) {
                continue;
            }
            StuckWatcher.INSTANCE.tickBot(server, bot);
            boolean handled = runDanger && DangerWatcher.INSTANCE.scanBot(server, bot);
            if (!handled && GoalExecutor.INSTANCE.tickBot(server, bot)) {
                continue;
            }
            if (!handled && runBackground) {
                io.github.greytaiwolf.fakeaiplayer.action.EquipAction.equipBestArmor(bot); // 第3层:平时也自动穿上背包里更好的护甲
                IdleCoordinator.INSTANCE.tickBot(bot);
            }
        }
    }
}
