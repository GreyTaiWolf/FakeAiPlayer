package io.github.greytaiwolf.fakeaiplayer.pathfinding;

/** Request-owned search accounting; safe to assert without global or parallel-test interference. */
public final class NavigationSearchMetrics {
    private int frontiersStarted;
    private int searchesCompleted;
    private int nodesExplored;
    private long elapsedNanos;
    private int cacheHits;

    void frontierStarted() {
        frontiersStarted++;
    }

    void searchCompleted(PathfindingResult result, long elapsed) {
        searchesCompleted++;
        nodesExplored += Math.max(0, result.nodesExplored());
        elapsedNanos += Math.max(0L, elapsed);
        if (result.cacheHit()) {
            cacheHits++;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                frontiersStarted, searchesCompleted, nodesExplored, elapsedNanos, cacheHits);
    }

    public record Snapshot(int frontiersStarted,
                           int searchesCompleted,
                           int nodesExplored,
                           long elapsedNanos,
                           int cacheHits) {
    }
}
