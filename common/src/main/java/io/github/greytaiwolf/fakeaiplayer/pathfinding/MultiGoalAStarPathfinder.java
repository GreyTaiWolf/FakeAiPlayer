package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * One-frontier, cost-sensitive A* over a semantic {@link NavGoal}.
 *
 * <p>The search state includes incoming heading because turn penalties make two arrivals at the
 * same block observably different. With heuristic weight {@code 1.0}, the first accepted terminal
 * path is therefore no more expensive than the best equivalent singleton-goal search.</p>
 */
public final class MultiGoalAStarPathfinder {
    private static final int MAX_CACHE_ENTRIES = 256;
    private static final long SUCCESS_CACHE_NANOS = 2_000_000_000L;
    private static final Predicate<BlockPos> ALLOW_ALL_POSITIONS = ignored -> true;
    private static final Map<CacheKey, CachedResult> RESULT_CACHE = new LinkedHashMap<>(
            MAX_CACHE_ENTRIES, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, CachedResult> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    private final ServerLevel world;
    private final BlockPos start;
    private final NavGoal goal;
    private final int maxNodes;
    private final long maxNanos;
    private final boolean canPillar;
    private final boolean allowDig;
    private final TraversalPolicy policy;
    private final double heuristicWeight;
    private final Set<BlockPos> excludedPositions;
    private final TraversalBounds bounds;
    private final Predicate<BlockPos> positionConstraint;
    private final String constraintKey;
    private final NavigationSearchMetrics metrics;
    private final NeighborEnumerator enumerator;
    private final Direction initialHeading;

    public MultiGoalAStarPathfinder(ServerLevel world,
                                    BlockPos start,
                                    NavGoal goal,
                                    int maxNodes,
                                    long maxMillis,
                                    TraversalPolicy policy,
                                    NavigationSearchMetrics metrics) {
        this(world, start, goal, maxNodes, maxMillis,
                policy.allowsPillaring(), policy.allowsDigging(), policy, 1.0D,
                Set.of(), TraversalBounds.unbounded(), ALLOW_ALL_POSITIONS,
                "none", metrics, null);
    }

    public MultiGoalAStarPathfinder(ServerLevel world,
                                    BlockPos start,
                                    NavGoal goal,
                                    int maxNodes,
                                    long maxMillis,
                                    boolean canPillar,
                                    boolean allowDig,
                                    TraversalPolicy policy,
                                    double heuristicWeight,
                                    Set<BlockPos> excludedPositions,
                                    TraversalBounds bounds,
                                    Predicate<BlockPos> positionConstraint,
                                    String constraintKey,
                                    NavigationSearchMetrics metrics) {
        this(world, start, goal, maxNodes, maxMillis, canPillar, allowDig, policy,
                heuristicWeight, excludedPositions, bounds, positionConstraint,
                constraintKey, metrics, null);
    }

    public MultiGoalAStarPathfinder(ServerLevel world,
                                    BlockPos start,
                                    NavGoal goal,
                                    int maxNodes,
                                    long maxMillis,
                                    boolean canPillar,
                                    boolean allowDig,
                                    TraversalPolicy policy,
                                    double heuristicWeight,
                                    Set<BlockPos> excludedPositions,
                                    TraversalBounds bounds,
                                    Predicate<BlockPos> positionConstraint,
                                    String constraintKey,
                                    NavigationSearchMetrics metrics,
                                    Direction initialHeading) {
        if (world == null || start == null || goal == null || policy == null) {
            throw new IllegalArgumentException("world, start, goal and policy are required");
        }
        if (maxNodes <= 0 || maxMillis <= 0L || heuristicWeight < 1.0D
                || !Double.isFinite(heuristicWeight)) {
            throw new IllegalArgumentException("invalid multi-goal search budget");
        }
        this.world = world;
        this.start = start.immutable();
        this.goal = goal;
        this.maxNodes = maxNodes;
        this.maxNanos = Math.multiplyExact(maxMillis, 1_000_000L);
        this.canPillar = canPillar;
        this.allowDig = allowDig;
        this.policy = policy;
        this.heuristicWeight = heuristicWeight;
        this.excludedPositions = immutablePositions(excludedPositions);
        this.bounds = bounds == null ? TraversalBounds.unbounded() : bounds;
        this.positionConstraint = positionConstraint == null
                ? ALLOW_ALL_POSITIONS : positionConstraint;
        this.constraintKey = constraintKey == null ? "opaque" : constraintKey;
        this.metrics = metrics == null ? new NavigationSearchMetrics() : metrics;
        this.enumerator = new NeighborEnumerator(canPillar, allowDig, policy);
        this.initialHeading = initialHeading != null
                && initialHeading.getAxis() != Direction.Axis.Y
                ? initialHeading : null;
    }

    public PathfindingResult findPath() {
        return findPath(false);
    }

    public PathfindingResult findPath(boolean bypassCache) {
        long startedNanos = System.nanoTime();
        long worldVersion = AStarPathfinder.worldVersion();
        String goalFingerprint = goal.fingerprint(world);
        if (!goal.resolvable(world)) {
            return finish(PathfindingResult.failure(
                    FailureReason.STALE_WORLD, 0, 0L, worldVersion, goalFingerprint),
                    startedNanos);
        }

        Standability.clearCache();
        if (!validPosition(start)
                || !Standability.isStandable(world, start, policy)) {
            return finish(PathfindingResult.failure(
                    FailureReason.NO_START, 0, elapsedMillis(startedNanos),
                    worldVersion, goalFingerprint), startedNanos);
        }
        FailureReason terminalValidation = validateKnownTerminals();
        if (terminalValidation != FailureReason.NONE) {
            return finish(PathfindingResult.failure(
                    terminalValidation, 0, elapsedMillis(startedNanos),
                    worldVersion, goalFingerprint), startedNanos);
        }

        enumerator.setPathGoalPredicate(position -> goal.accepts(world, position));
        CacheKey cacheKey = cacheKey(goalFingerprint, worldVersion);
        boolean cacheable = cacheable();
        if (!bypassCache && cacheable) {
            PathfindingResult cached = cached(cacheKey);
            if (cached != null) {
                if (validateCachedPath(cached.path())) {
                    return finish(cached, startedNanos);
                }
                removeCached(cacheKey);
            }
        }

        metrics.frontierStarted();
        PriorityQueueWithDeterminism open = new PriorityQueueWithDeterminism();
        Map<SearchState, Double> gScore = new HashMap<>();
        Set<SearchState> closed = new HashSet<>();

        Node startNode = new Node(
                start, 0.0D, safeHeuristic(start), MoveType.WALK, null, initialHeading);
        SearchState startState = state(startNode);
        open.add(startNode);
        gScore.put(startState, 0.0D);

        int explored = 0;
        while (!open.isEmpty()) {
            if (AStarPathfinder.worldVersion() != worldVersion) {
                return finish(PathfindingResult.failure(
                        FailureReason.STALE_WORLD, explored, elapsedMillis(startedNanos),
                        worldVersion, goalFingerprint), startedNanos);
            }
            if (explored >= maxNodes) {
                return finish(PathfindingResult.failure(
                        FailureReason.SEARCH_LIMIT, explored, elapsedMillis(startedNanos),
                        worldVersion, goalFingerprint), startedNanos);
            }
            if (System.nanoTime() - startedNanos > maxNanos) {
                return finish(PathfindingResult.failure(
                        FailureReason.TIMEOUT, explored, elapsedMillis(startedNanos),
                        worldVersion, goalFingerprint), startedNanos);
            }

            Node current = open.poll();
            SearchState currentState = state(current);
            if (current.gCost() > gScore.getOrDefault(
                    currentState, Double.POSITIVE_INFINITY) + 1.0E-9D
                    || !closed.add(currentState)) {
                continue;
            }
            explored++;

            if (isAcceptedTerminal(current.pos())) {
                List<Node> completePath = reconstruct(current);
                PathfindingResult result = PathfindingResult.success(
                        completePath, explored, elapsedMillis(startedNanos),
                        worldVersion, goalFingerprint, false);
                if (cacheable) {
                    cache(cacheKey, result);
                }
                return finish(result, startedNanos);
            }

            for (NeighborCandidate neighbor : enumerator.getNeighbors(current, world)) {
                if (!validPosition(neighbor.pos())) {
                    continue;
                }
                Node candidate = new Node(
                        neighbor.pos(),
                        current.gCost() + CostModel.stepCost(current, neighbor, world),
                        safeHeuristic(neighbor.pos()),
                        neighbor.moveType(),
                        current);
                SearchState candidateState = state(candidate);
                double known = gScore.getOrDefault(candidateState, Double.POSITIVE_INFINITY);
                if (known <= candidate.gCost()) {
                    continue;
                }
                gScore.put(candidateState, candidate.gCost());
                // Reopen improved states: the admissible vertical heuristic is intentionally
                // direction-sensitive and may be inconsistent when a route first drops away from
                // its goal. Without reopening, a closed state could hide the true cheapest goal.
                closed.remove(candidateState);
                open.add(candidate);
            }
        }
        return finish(PathfindingResult.failure(
                FailureReason.GOAL_UNREACHABLE, explored, elapsedMillis(startedNanos),
                worldVersion, goalFingerprint), startedNanos);
    }

    public NavigationSearchMetrics metrics() {
        return metrics;
    }

    private FailureReason validateKnownTerminals() {
        if (goal instanceof NavGoal.Exact exact) {
            return terminalPositionUsable(exact.target())
                    ? FailureReason.NONE : FailureReason.GOAL_NOT_STANDABLE;
        }
        if (goal instanceof NavGoal.Interaction interaction) {
            boolean any = interaction.stands().stream().anyMatch(this::terminalPositionUsable);
            return any ? FailureReason.NONE : FailureReason.GOAL_NOT_STANDABLE;
        }
        return FailureReason.NONE;
    }

    private boolean terminalPositionUsable(BlockPos position) {
        if (!validPosition(position)) {
            return false;
        }
        if (Standability.isStandable(world, position, policy)) {
            return !policy.requiresOpenGoal() || LocalOpenness.isOpen(world, position, policy);
        }
        return allowDig && diggableOrPassable(position) && diggableOrPassable(position.above());
    }

    private boolean diggableOrPassable(BlockPos position) {
        var state = world.getBlockState(position);
        return state.getFluidState().isEmpty()
                && (state.getCollisionShape(world, position).isEmpty()
                || state.getDestroySpeed(world, position) >= 0.0F);
    }

    private boolean isAcceptedTerminal(BlockPos position) {
        return goal.accepts(world, position)
                && (!policy.requiresOpenGoal() || LocalOpenness.isOpen(world, position, policy));
    }

    private double safeHeuristic(BlockPos position) {
        double heuristic = goal.heuristic(world, position);
        if (!Double.isFinite(heuristic) || heuristic < 0.0D) {
            return Double.POSITIVE_INFINITY;
        }
        return heuristic * heuristicWeight;
    }

    private boolean validPosition(BlockPos position) {
        return bounds.contains(position)
                && !excludedPositions.contains(position)
                && positionConstraint.test(position);
    }

    private boolean cacheable() {
        return positionConstraint == ALLOW_ALL_POSITIONS
                && !"opaque".equals(constraintKey);
    }

    private CacheKey cacheKey(String goalFingerprint, long worldVersion) {
        return new CacheKey(
                Integer.toHexString(System.identityHashCode(world.getServer())),
                world.dimension().location().toString(),
                start,
                goalFingerprint,
                maxNodes,
                maxNanos,
                heuristicWeight,
                canPillar,
                allowDig,
                policy,
                excludedPositions,
                bounds,
                constraintKey,
                initialHeading,
                AIBotConfig.get().nav().maxSafeFall(),
                worldVersion);
    }

    private boolean validateCachedPath(List<Node> path) {
        if (path.isEmpty() || !path.get(0).pos().equals(start)
                || !isAcceptedTerminal(path.get(path.size() - 1).pos())) {
            return false;
        }
        for (int index = 1; index < path.size(); index++) {
            Node previous = path.get(index - 1);
            Node expected = path.get(index);
            if (!validPosition(expected.pos())) {
                return false;
            }
            boolean transition = enumerator.getNeighbors(previous, world).stream()
                    .anyMatch(candidate -> candidate.pos().equals(expected.pos())
                            && candidate.moveType() == expected.moveType());
            if (!transition) {
                return false;
            }
        }
        return true;
    }

    private PathfindingResult finish(PathfindingResult result, long startedNanos) {
        metrics.searchCompleted(result, System.nanoTime() - startedNanos);
        return result;
    }

    private static SearchState state(Node node) {
        return new SearchState(node.pos(), node.heading());
    }

    private static List<Node> reconstruct(Node end) {
        List<Node> path = new ArrayList<>();
        for (Node current = end; current != null; current = current.parent()) {
            path.add(current);
        }
        java.util.Collections.reverse(path);
        return List.copyOf(path);
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static Set<BlockPos> immutablePositions(Set<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<BlockPos> copy = new LinkedHashSet<>();
        positions.stream().map(BlockPos::immutable)
                .sorted(Comparator.comparingLong(BlockPos::asLong)).forEach(copy::add);
        return Set.copyOf(copy);
    }

    private static PathfindingResult cached(CacheKey key) {
        synchronized (RESULT_CACHE) {
            CachedResult cached = RESULT_CACHE.get(key);
            if (cached == null || cached.expiresAtNanos() < System.nanoTime()) {
                RESULT_CACHE.remove(key);
                return null;
            }
            return cached.toResult(key.worldVersion(), key.goalFingerprint());
        }
    }

    private static void cache(CacheKey key, PathfindingResult result) {
        synchronized (RESULT_CACHE) {
            RESULT_CACHE.put(key, new CachedResult(
                    result.path(), result.nodesExplored(),
                    System.nanoTime() + SUCCESS_CACHE_NANOS));
        }
    }

    private static void removeCached(CacheKey key) {
        synchronized (RESULT_CACHE) {
            RESULT_CACHE.remove(key);
        }
    }

    static void clearCache() {
        synchronized (RESULT_CACHE) {
            RESULT_CACHE.clear();
        }
    }

    private record SearchState(BlockPos position, Direction heading) {
        private SearchState {
            position = position.immutable();
        }
    }

    private record CacheKey(String runtimeSession,
                            String dimension,
                            BlockPos start,
                            String goalFingerprint,
                            int maxNodes,
                            long maxNanos,
                            double heuristicWeight,
                            boolean canPillar,
                            boolean allowDig,
                            TraversalPolicy policy,
                            Set<BlockPos> exclusions,
                            TraversalBounds bounds,
                            String constraintKey,
                            Direction initialHeading,
                            int maxSafeFall,
                            long worldVersion) {
        private CacheKey {
            start = start.immutable();
            exclusions = immutablePositions(exclusions);
        }
    }

    private record CachedResult(List<Node> path, int nodesExplored, long expiresAtNanos) {
        private CachedResult {
            path = List.copyOf(path);
        }

        private PathfindingResult toResult(long worldVersion, String goalFingerprint) {
            return PathfindingResult.success(
                    path, 0, 0L, worldVersion, goalFingerprint, true);
        }
    }

    private static final class PriorityQueueWithDeterminism {
        private final java.util.PriorityQueue<Node> delegate = new java.util.PriorityQueue<>(
                Comparator.comparingDouble(Node::fCost)
                        .thenComparingDouble(Node::hCost)
                        .thenComparingDouble(Node::gCost)
                        .thenComparingLong(node -> node.pos().asLong())
                        .thenComparingInt(node -> node.heading() == null
                                ? -1 : node.heading().ordinal()));

        void add(Node node) {
            delegate.add(node);
        }

        Node poll() {
            return delegate.poll();
        }

        boolean isEmpty() {
            return delegate.isEmpty();
        }
    }
}
