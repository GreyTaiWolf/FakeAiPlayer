package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.coordination.IdleCoordinator;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.goal.GoalExecutor;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventorySessionManager;
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
            // Immediate terrain safety always owns the first opportunity to act.
            if (NavSafetyNet.INSTANCE.tickBot(server, bot)) {
                IdleCoordinator.INSTANCE.cancelAmbient(bot, "navigation_safety");
                // NavSafety owns immediate movement, while the dedicated task supplies bounded
                // bank search and platform placement when no adjacent dry cell exists.
                if (bot.isInLava()) {
                    DangerWatcher.INSTANCE.scanBot(server, bot);
                }
                continue;
            }
            boolean handled = runDanger && DangerWatcher.INSTANCE.scanBot(server, bot);
            if (!handled && BotInventorySessionManager.INSTANCE.isOpen(bot)) {
                IdleCoordinator.INSTANCE.cancelAmbient(bot, "inventory_open");
                continue;
            }
            StuckWatcher.INSTANCE.tickBot(server, bot);
            if (!handled && GoalExecutor.INSTANCE.tickBot(server, bot)) {
                continue;
            }
            if (!handled) {
                // Ambient LOOK and watchdog state run every tick; only job sampling is throttled.
                IdleCoordinator.INSTANCE.tickBot(bot, runBackground);
            }
        }
    }
}
