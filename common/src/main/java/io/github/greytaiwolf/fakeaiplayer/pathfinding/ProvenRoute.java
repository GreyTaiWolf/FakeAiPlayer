package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/**
 * Immutable continuation of one complete A* proof.
 *
 * <p>Execution is segmented without throwing away the proven suffix. Each returned segment rebases
 * its g-cost to zero at the boundary while retaining the original incoming heading, so summing
 * segment costs is exactly equal to the complete route, including a turn on a boundary edge.</p>
 */
final class ProvenRoute {
    private final List<Node> fullPath;
    private final int boundaryIndex;
    private final long worldVersion;
    private final String goalFingerprint;

    private ProvenRoute(List<Node> fullPath,
                        int boundaryIndex,
                        long worldVersion,
                        String goalFingerprint) {
        this.fullPath = List.copyOf(fullPath);
        this.boundaryIndex = boundaryIndex;
        this.worldVersion = worldVersion;
        this.goalFingerprint = goalFingerprint;
    }

    static Slice splitInitial(PathfindingResult complete, int segmentLength) {
        if (complete == null || !complete.success() || complete.path().isEmpty()) {
            throw new IllegalArgumentException("a proven route requires a successful complete path");
        }
        int lastIndex = complete.path().size() - 1;
        int endIndex = Math.min(lastIndex, Math.max(1, segmentLength));
        if (endIndex >= lastIndex) {
            return new Slice(complete, null);
        }
        List<Node> first = rebase(complete.path(), 0, endIndex);
        ProvenRoute continuation = new ProvenRoute(
                complete.path(), endIndex, complete.worldVersion(), complete.goalFingerprint());
        return new Slice(segmentResult(first, complete), continuation);
    }

    Optional<Slice> resume(AIPlayerEntity bot,
                           NavigationRequest request,
                           int segmentLength) {
        if (bot == null || request == null || boundaryIndex < 0
                || boundaryIndex >= fullPath.size() - 1
                || AStarPathfinder.worldVersion() != worldVersion
                || !bot.blockPosition().equals(fullPath.get(boundaryIndex).pos())
                || !request.goal().resolvable(bot.serverLevel())
                || !goalFingerprint.equals(request.goal().fingerprint(bot.serverLevel()))
                || !NavigationPlanner.legalStart(bot, request, bot.blockPosition())) {
            return Optional.empty();
        }
        int lastIndex = fullPath.size() - 1;
        int endIndex = Math.min(lastIndex, boundaryIndex + Math.max(1, segmentLength));
        List<Node> segment = rebase(fullPath, boundaryIndex, endIndex);
        PathfindingResult result = new PathfindingResult(
                segment, true, FailureReason.NONE, 0, 0L,
                segment.get(0).pos(), segment.get(segment.size() - 1).pos(),
                segment.get(segment.size() - 1).gCost(), worldVersion, goalFingerprint, false);
        ProvenRoute next = endIndex < lastIndex
                ? new ProvenRoute(fullPath, endIndex, worldVersion, goalFingerprint)
                : null;
        return Optional.of(new Slice(result, next));
    }

    private static PathfindingResult segmentResult(List<Node> segment,
                                                   PathfindingResult source) {
        return new PathfindingResult(
                segment, true, FailureReason.NONE,
                source.nodesExplored(), source.elapsedMs(),
                segment.get(0).pos(), segment.get(segment.size() - 1).pos(),
                segment.get(segment.size() - 1).gCost(),
                source.worldVersion(), source.goalFingerprint(), source.cacheHit());
    }

    private static List<Node> rebase(List<Node> source, int start, int endInclusive) {
        double baseCost = source.get(start).gCost();
        List<Node> segment = new ArrayList<>(endInclusive - start + 1);
        Node parent = null;
        for (int index = start; index <= endInclusive; index++) {
            Node original = source.get(index);
            Node rebased = new Node(
                    original.pos(),
                    Math.max(0.0D, original.gCost() - baseCost),
                    original.hCost(),
                    original.moveType(),
                    parent,
                    original.heading());
            segment.add(rebased);
            parent = rebased;
        }
        return List.copyOf(segment);
    }

    record Slice(PathfindingResult result, ProvenRoute continuation) {
        Slice {
            if (result == null || !result.success()) {
                throw new IllegalArgumentException("proven route slice must be successful");
            }
        }

        boolean segmented() {
            return continuation != null;
        }
    }
}
