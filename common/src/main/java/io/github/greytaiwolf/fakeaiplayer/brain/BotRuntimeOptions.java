package io.github.greytaiwolf.fakeaiplayer.brain;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BotRuntimeOptions {
    public static final BotRuntimeOptions INSTANCE = new BotRuntimeOptions();

    private final Map<UUID, Boolean> memoryTools = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> verboseReports = new ConcurrentHashMap<>();

    private BotRuntimeOptions() {
    }

    public boolean memoryToolsEnabled(AIPlayerEntity bot) {
        return memoryTools.getOrDefault(bot.getUUID(), AIBotConfig.get().brain().memoryToolsEnabled());
    }

    public void setMemoryToolsEnabled(AIPlayerEntity bot, boolean enabled) {
        memoryTools.put(bot.getUUID(), enabled);
    }

    public boolean verboseReportsEnabled(AIPlayerEntity bot) {
        return verboseReports.getOrDefault(bot.getUUID(), AIBotConfig.get().brain().verboseReportsEnabled());
    }

    public void setVerboseReportsEnabled(AIPlayerEntity bot, boolean enabled) {
        verboseReports.put(bot.getUUID(), enabled);
    }

    public void clear(AIPlayerEntity bot) {
        memoryTools.remove(bot.getUUID());
        verboseReports.remove(bot.getUUID());
    }

    public void clearAll() {
        memoryTools.clear();
        verboseReports.clear();
    }
}
