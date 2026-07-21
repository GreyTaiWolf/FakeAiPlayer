package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;

/** Per-bot and server-wide main-thread search budget reset on each server tick. */
public final class NavigationSearchBudget {
    private static final int BOT_MAX_SEARCHES_PER_TICK = 8;
    private static final int SERVER_MAX_SEARCHES_PER_TICK = 32;
    private static final int BOT_MAX_NODES_PER_TICK = 32_000;
    private static final int SERVER_MAX_NODES_PER_TICK = 128_000;
    private static final long BOT_MAX_MILLIS_PER_TICK = 80L;
    private static final long SERVER_MAX_MILLIS_PER_TICK = 240L;
    private static final Ledger<MinecraftServer> PRODUCTION = new Ledger<>(
            BOT_MAX_SEARCHES_PER_TICK,
            SERVER_MAX_SEARCHES_PER_TICK,
            BOT_MAX_NODES_PER_TICK,
            SERVER_MAX_NODES_PER_TICK,
            BOT_MAX_MILLIS_PER_TICK,
            SERVER_MAX_MILLIS_PER_TICK,
            new WeakHashMap<>());

    private NavigationSearchBudget() {
    }

    public static Permit acquire(MinecraftServer server,
                                 UUID botId,
                                 int requestedNodes,
                                 long requestedMillis) {
        if (server == null) {
            return Permit.denied(FailureReason.SEARCH_BUDGET);
        }
        return PRODUCTION.acquire(
                server, server.getTickCount(), botId, requestedNodes, requestedMillis);
    }

    public static Snapshot snapshot(MinecraftServer server, UUID botId) {
        if (server == null || botId == null) {
            return Snapshot.empty();
        }
        return PRODUCTION.snapshot(server, server.getTickCount(), botId);
    }

    /**
     * Isolated accounting core. Production uses weak server keys; unit tests use ordinary synthetic
     * keys and an explicit tick so exhausting a test ledger cannot throttle a live GameTest server.
     */
    static final class Ledger<K> {
        private final int botMaxSearches;
        private final int serverMaxSearches;
        private final int botMaxNodes;
        private final int serverMaxNodes;
        private final long botMaxMillis;
        private final long serverMaxMillis;
        private final Map<K, TickState> states;

        Ledger(int botMaxSearches,
               int serverMaxSearches,
               int botMaxNodes,
               int serverMaxNodes,
               long botMaxMillis,
               long serverMaxMillis) {
            this(botMaxSearches, serverMaxSearches, botMaxNodes, serverMaxNodes,
                    botMaxMillis, serverMaxMillis, new HashMap<>());
        }

        Ledger(int botMaxSearches,
               int serverMaxSearches,
               int botMaxNodes,
               int serverMaxNodes,
               long botMaxMillis,
               long serverMaxMillis,
               Map<K, TickState> states) {
            if (botMaxSearches <= 0 || serverMaxSearches < botMaxSearches
                    || botMaxNodes <= 0 || serverMaxNodes < botMaxNodes
                    || botMaxMillis <= 0L || serverMaxMillis < botMaxMillis
                    || states == null) {
                throw new IllegalArgumentException("invalid navigation search budget limits");
            }
            this.botMaxSearches = botMaxSearches;
            this.serverMaxSearches = serverMaxSearches;
            this.botMaxNodes = botMaxNodes;
            this.serverMaxNodes = serverMaxNodes;
            this.botMaxMillis = botMaxMillis;
            this.serverMaxMillis = serverMaxMillis;
            this.states = states;
        }

        synchronized Permit acquire(K serverKey,
                                    int tick,
                                    UUID botId,
                                    int requestedNodes,
                                    long requestedMillis) {
            if (serverKey == null || botId == null
                    || requestedNodes <= 0 || requestedMillis <= 0L) {
                return Permit.denied(FailureReason.SEARCH_BUDGET);
            }
            TickState state = states.computeIfAbsent(serverKey, ignored -> new TickState());
            state.resetIfNeeded(tick);
            Usage bot = state.bots.computeIfAbsent(botId, ignored -> new Usage());
            if (bot.searches >= botMaxSearches
                    || state.server.searches >= serverMaxSearches) {
                return Permit.denied(FailureReason.SEARCH_BUDGET);
            }
            int nodes = Math.min(requestedNodes, Math.min(
                    botMaxNodes - bot.nodes,
                    serverMaxNodes - state.server.nodes));
            long millis = Math.min(requestedMillis, Math.min(
                    botMaxMillis - bot.millis,
                    serverMaxMillis - state.server.millis));
            if (nodes <= 0 || millis <= 0L) {
                return Permit.denied(FailureReason.SEARCH_BUDGET);
            }
            bot.searches++;
            state.server.searches++;
            return new Permit(
                    this, state, bot, tick, nodes, millis,
                    nodes < requestedNodes, millis < requestedMillis, FailureReason.NONE);
        }

        synchronized Snapshot snapshot(K serverKey, int tick, UUID botId) {
            if (serverKey == null || botId == null) {
                return Snapshot.empty();
            }
            TickState state = states.get(serverKey);
            if (state == null) {
                return Snapshot.empty();
            }
            state.resetIfNeeded(tick);
            Usage bot = state.bots.getOrDefault(botId, new Usage());
            return new Snapshot(
                    bot.searches, bot.nodes, bot.millis,
                    state.server.searches, state.server.nodes, state.server.millis);
        }

        private synchronized void complete(Permit permit,
                                           int nodesExplored,
                                           long elapsedMillis) {
            if (permit.completed) {
                return;
            }
            permit.completed = true;
            // A synchronous production search completes in its acquisition tick. If a future
            // asynchronous caller finishes late, never charge its old permit to a fresh tick.
            if (permit.state.tick != permit.tick) {
                return;
            }
            int nodes = Math.min(permit.maxNodes, Math.max(0, nodesExplored));
            long millis = Math.min(permit.maxMillis, Math.max(0L, elapsedMillis));
            permit.bot.nodes += nodes;
            permit.bot.millis += millis;
            permit.state.server.nodes += nodes;
            permit.state.server.millis += millis;
        }
    }

    public static final class Permit {
        private final Ledger<?> ledger;
        private final TickState state;
        private final Usage bot;
        private final int tick;
        private final int maxNodes;
        private final long maxMillis;
        private final boolean nodesClipped;
        private final boolean millisClipped;
        private final FailureReason denialReason;
        private boolean completed;

        private Permit(Ledger<?> ledger,
                       TickState state,
                       Usage bot,
                       int tick,
                       int maxNodes,
                       long maxMillis,
                       boolean nodesClipped,
                       boolean millisClipped,
                       FailureReason denialReason) {
            this.ledger = ledger;
            this.state = state;
            this.bot = bot;
            this.tick = tick;
            this.maxNodes = maxNodes;
            this.maxMillis = maxMillis;
            this.nodesClipped = nodesClipped;
            this.millisClipped = millisClipped;
            this.denialReason = denialReason;
        }

        private static Permit denied(FailureReason reason) {
            return new Permit(
                    null, null, null, Integer.MIN_VALUE, 0, 0L,
                    false, false, reason);
        }

        public boolean granted() {
            return denialReason == FailureReason.NONE;
        }

        public int maxNodes() {
            return maxNodes;
        }

        public long maxMillis() {
            return maxMillis;
        }

        public FailureReason denialReason() {
            return denialReason;
        }

        /** Distinguishes a request-owned limit from a temporarily clipped tick quota. */
        public FailureReason classifyExhaustion(FailureReason failure) {
            if ((failure == FailureReason.SEARCH_LIMIT && nodesClipped)
                    || (failure == FailureReason.TIMEOUT && millisClipped)) {
                return FailureReason.SEARCH_BUDGET;
            }
            return failure;
        }

        public void complete(int nodesExplored, long elapsedMillis) {
            if (granted()) {
                ledger.complete(this, nodesExplored, elapsedMillis);
            }
        }
    }

    public record Snapshot(int botSearches,
                           int botNodes,
                           long botMillis,
                           int serverSearches,
                           int serverNodes,
                           long serverMillis) {
        private static Snapshot empty() {
            return new Snapshot(0, 0, 0L, 0, 0, 0L);
        }
    }

    static final class TickState {
        private int tick = Integer.MIN_VALUE;
        private final Usage server = new Usage();
        private final Map<UUID, Usage> bots = new HashMap<>();

        void resetIfNeeded(int currentTick) {
            if (tick == currentTick) {
                return;
            }
            tick = currentTick;
            server.clear();
            bots.clear();
        }
    }

    private static final class Usage {
        private int searches;
        private int nodes;
        private long millis;

        private void clear() {
            searches = 0;
            nodes = 0;
            millis = 0L;
        }
    }
}
