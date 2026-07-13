package io.github.greytaiwolf.fakeaiplayer.persist;

public record PersistedBot(BotRecord bot, MissionRuntimeRecord missions) {
    public PersistedBot {
        missions = missions == null ? MissionRuntimeRecord.empty() : missions;
    }
}
