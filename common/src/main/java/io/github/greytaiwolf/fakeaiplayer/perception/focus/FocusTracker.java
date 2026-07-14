package io.github.greytaiwolf.fakeaiplayer.perception.focus;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.observe.TpsGuard;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Independent background sensor. It deliberately does not depend on TaskManager and therefore
 * keeps observing while mining, walking, fighting, building, paused, or idle.
 */
public final class FocusTracker {
    public static final FocusTracker INSTANCE = new FocusTracker();

    private static final int DEGRADED_INTERVAL_MULTIPLIER = 5;

    private final Map<UUID, Track> tracks = new ConcurrentHashMap<>();

    private FocusTracker() {
    }

    public void tick(MinecraftServer server) {
        AIBotConfig.Focus config = AIBotConfig.get().perception().focus();
        int interval = Math.max(1, config.sampleIntervalTicks());
        if (TpsGuard.INSTANCE.degraded(server)) {
            interval *= DEGRADED_INTERVAL_MULTIPLIER;
        }
        if (server.getTickCount() % interval != 0) {
            return;
        }
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            if (!bot.isAlive() || bot.isRemoved()) {
                clear(bot);
                continue;
            }
            observeNow(bot);
        }
    }

    /** Performs a fresh server-thread raycast, including after a look_at call in the same tick. */
    public FocusSnapshot observeNow(AIPlayerEntity bot) {
        AIBotConfig.Focus config = AIBotConfig.get().perception().focus();
        Track track = tracks.compute(bot.getUUID(), (ignored, existing) ->
                existing != null && existing.matches(config)
                        ? existing
                        : new Track(config.stableSamples(), config.lostGraceSamples()));
        FocusSnapshot raw = FocusResolver.observeNow(bot);
        return track.advance(bot, raw, config.historySize());
    }

    /** Fresh detailed read for inspect_focus; never serves a pre-look_at cache entry. */
    public FocusSnapshot inspectNow(AIPlayerEntity bot) {
        AIBotConfig.Focus config = AIBotConfig.get().perception().focus();
        Track track = tracks.compute(bot.getUUID(), (ignored, existing) ->
                existing != null && existing.matches(config)
                        ? existing
                        : new Track(config.stableSamples(), config.lostGraceSamples()));
        FocusSnapshot raw = FocusResolver.inspectNow(bot);
        FocusSnapshot tracked = track.advance(bot, raw, config.historySize());
        // On-demand inspection answers what the ray hits now. Background context keeps its
        // LOST_GRACE debounce, but a fresh MISS must not masquerade as the stale prior target.
        return raw.kind() == FocusKind.MISS ? raw : tracked;
    }

    public FocusSnapshot current(AIPlayerEntity bot) {
        Track track = tracks.get(bot.getUUID());
        if (track != null) {
            return track.current();
        }
        long tick = bot.getServer().getTickCount();
        String dimension = bot.serverLevel().dimension().location().toString();
        return AIBotConfig.get().perception().focus().enabledValue()
                ? FocusSnapshot.miss(tick, dimension)
                : FocusSnapshot.disabled(tick, dimension);
    }

    public List<HistoryEntry> history(UUID botId) {
        Track track = tracks.get(botId);
        return track == null ? List.of() : track.history();
    }

    public void clear(AIPlayerEntity bot) {
        clear(bot.getUUID());
    }

    public void clear(UUID botId) {
        tracks.remove(botId);
    }

    public void clearAll() {
        tracks.clear();
    }

    private static final class Track {
        private final int stableSamples;
        private final int lostGraceSamples;
        private final FocusStateMachine machine;
        private final ArrayDeque<HistoryEntry> history = new ArrayDeque<>();

        private FocusSnapshot current;
        private FocusSnapshot lastTracked;
        private String lastRawKey;
        private String lastMaterialFingerprint = "";
        private long lastSampleTick = Long.MIN_VALUE;

        private Track(int stableSamples, int lostGraceSamples) {
            this.stableSamples = Math.max(1, stableSamples);
            this.lostGraceSamples = Math.max(1, lostGraceSamples);
            this.machine = new FocusStateMachine(this.stableSamples, this.lostGraceSamples);
        }

        private boolean matches(AIBotConfig.Focus config) {
            return stableSamples == Math.max(1, config.stableSamples())
                    && lostGraceSamples == Math.max(1, config.lostGraceSamples());
        }

        private synchronized FocusSnapshot advance(AIPlayerEntity bot,
                                                   FocusSnapshot raw,
                                                   int maxHistory) {
            String rawKey = raw.kind() == FocusKind.MISS ? null : raw.targetKey();
            if (raw.state() == FocusState.DISABLED) {
                machine.disable();
                current = raw;
                lastTracked = null;
                lastRawKey = null;
                lastMaterialFingerprint = "";
                lastSampleTick = raw.observedTick();
                return current;
            }

            if (lastSampleTick == raw.observedTick() && Objects.equals(lastRawKey, rawKey)) {
                if (current != null && rawKey != null) {
                    current = raw.withTrackingState(current.state(), current.stale());
                    if (current.state() == FocusState.TRACKING) {
                        lastTracked = current;
                    }
                }
                return current == null ? raw : current;
            }

            lastSampleTick = raw.observedTick();
            lastRawKey = rawKey;
            FocusStateMachine.Update update = machine.sample(rawKey);
            current = project(raw, update);

            FocusEvent event = update.event();
            FocusSnapshot eventSnapshot = current;
            if (current.state() == FocusState.TRACKING) {
                String fingerprint = current.materialFingerprint();
                if (event == FocusEvent.NONE
                        && !lastMaterialFingerprint.isBlank()
                        && !lastMaterialFingerprint.equals(fingerprint)) {
                    event = FocusEvent.UPDATED;
                }
                lastMaterialFingerprint = fingerprint;
                lastTracked = current;
            } else if (current.state() == FocusState.NO_TARGET) {
                lastMaterialFingerprint = "";
                if (event == FocusEvent.LOST) {
                    if (lastTracked != null) {
                        eventSnapshot = lastTracked.withTrackingState(FocusState.NO_TARGET, true);
                    }
                    lastTracked = null;
                }
            }

            if (event != FocusEvent.NONE) {
                appendHistory(new HistoryEntry(
                        raw.observedTick(),
                        event,
                        update.targetKey() == null ? "" : update.targetKey(),
                        eventSnapshot),
                        Math.max(1, maxHistory));
                BotLog.perception(bot, "focus_" + event.name().toLowerCase(java.util.Locale.ROOT),
                        "state", eventSnapshot.state(),
                        "kind", eventSnapshot.kind(),
                        "target", update.targetKey(),
                        "id", eventSnapshot.id(),
                        "distance", eventSnapshot.distance());
            }
            return current;
        }

        private FocusSnapshot project(FocusSnapshot raw, FocusStateMachine.Update update) {
            return switch (update.state()) {
                case DISABLED -> raw.withTrackingState(FocusState.DISABLED, false);
                case NO_TARGET -> FocusSnapshot.miss(raw.observedTick(), raw.dimension());
                case ACQUIRING -> raw.withTrackingState(FocusState.ACQUIRING, false);
                case TRACKING -> raw.withTrackingState(FocusState.TRACKING, false);
                case LOST_GRACE -> lastTracked == null
                        ? FocusSnapshot.miss(raw.observedTick(), raw.dimension())
                                .withTrackingState(FocusState.LOST_GRACE, true)
                        : lastTracked.withTrackingState(FocusState.LOST_GRACE, true);
            };
        }

        private void appendHistory(HistoryEntry entry, int maxHistory) {
            history.addLast(entry);
            while (history.size() > maxHistory) {
                history.removeFirst();
            }
        }

        private synchronized FocusSnapshot current() {
            return current;
        }

        private synchronized List<HistoryEntry> history() {
            return List.copyOf(new ArrayList<>(history));
        }
    }

    public record HistoryEntry(long tick, FocusEvent event, String targetKey, FocusSnapshot snapshot) {
    }
}
