package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * A server-authoritative description of where a navigation request may terminate.
 *
 * <p>The goal is deliberately separate from a concrete path endpoint. A single A* frontier can
 * therefore choose between every legal interaction stance (or every member of a composite goal)
 * while preserving the semantic goal for later replans.</p>
 */
public sealed interface NavGoal permits NavGoal.Exact, NavGoal.Near, NavGoal.Interaction,
        NavGoal.FollowRing, NavGoal.Flee, NavGoal.Composite {

    /** True when {@code position} is an authoritative terminal position in the current world. */
    boolean accepts(ServerLevel world, BlockPos position);

    /** Admissible lower-bound cost used by cost-sensitive multi-goal A*. */
    double heuristic(ServerLevel world, BlockPos position);

    /** Representative point used for diagnostics, segmentation, and transition validation. */
    BlockPos anchor(ServerLevel world);

    /** Stable semantic identity, independent of the current position of a tracked entity. */
    String identityKey();

    /**
     * Cache fingerprint for this resolved world snapshot. Dynamic goals include their live anchor
     * so a moved entity cannot reuse a path to an old follow ring.
     */
    default String fingerprint(ServerLevel world) {
        BlockPos resolved = anchor(world);
        return identityKey() + "@" + (resolved == null ? "unresolved" : compact(resolved));
    }

    /** False when the semantic target can no longer be interpreted safely. */
    default boolean resolvable(ServerLevel world) {
        return anchor(world) != null;
    }

    /** True for goals whose accepted positions may change without a block-world revision. */
    default boolean dynamic() {
        return false;
    }

    static Exact exact(BlockPos target) {
        return new Exact(target);
    }

    static Near near(BlockPos center, int horizontalRadius, int verticalTolerance) {
        return new Near(center, horizontalRadius, verticalTolerance);
    }

    static Interaction interaction(BlockPos target, Set<BlockPos> stands) {
        return new Interaction(target, stands);
    }

    record Exact(BlockPos target) implements NavGoal {
        public Exact {
            target = requirePosition(target, "exact target");
        }

        @Override
        public boolean accepts(ServerLevel world, BlockPos position) {
            return target.equals(position);
        }

        @Override
        public double heuristic(ServerLevel world, BlockPos position) {
            return CostModel.heuristic(position, target);
        }

        @Override
        public BlockPos anchor(ServerLevel world) {
            return target;
        }

        @Override
        public String identityKey() {
            return "exact:" + compact(target);
        }
    }

    /** A horizontal radius with an explicit vertical tolerance. */
    record Near(BlockPos center, int horizontalRadius, int verticalTolerance) implements NavGoal {
        public Near {
            center = requirePosition(center, "near center");
            if (horizontalRadius < 0 || verticalTolerance < 0) {
                throw new IllegalArgumentException("near radii must be non-negative");
            }
        }

        @Override
        public boolean accepts(ServerLevel world, BlockPos position) {
            long dx = (long) position.getX() - center.getX();
            long dz = (long) position.getZ() - center.getZ();
            return dx * dx + dz * dz <= (long) horizontalRadius * horizontalRadius
                    && Math.abs(position.getY() - center.getY()) <= verticalTolerance;
        }

        @Override
        public double heuristic(ServerLevel world, BlockPos position) {
            double horizontal = horizontalDistance(position, center);
            double horizontalGap = Math.max(0.0D, horizontal - horizontalRadius);
            double verticalGap = Math.max(
                    0.0D, Math.abs(position.getY() - center.getY()) - verticalTolerance);
            return Math.max(
                    CostModel.minimumHorizontalCost(horizontalGap),
                    CostModel.minimumVerticalCost(position.getY(), center.getY(), verticalGap));
        }

        @Override
        public BlockPos anchor(ServerLevel world) {
            return center;
        }

        @Override
        public String identityKey() {
            return "near:" + compact(center) + ':' + horizontalRadius + ':' + verticalTolerance;
        }
    }

    /** A block interaction target together with every reviewed legal feet position. */
    record Interaction(BlockPos target,
                       Set<BlockPos> stands,
                       String expectedTargetState) implements NavGoal {
        public Interaction {
            target = requirePosition(target, "interaction target");
            stands = immutablePositions(stands);
            expectedTargetState = expectedTargetState == null ? "" : expectedTargetState;
            if (stands.isEmpty()) {
                throw new IllegalArgumentException("interaction goal requires at least one stand");
            }
        }

        public Interaction(BlockPos target, Set<BlockPos> stands) {
            this(target, stands, "");
        }

        @Override
        public boolean accepts(ServerLevel world, BlockPos position) {
            return stands.contains(position);
        }

        @Override
        public double heuristic(ServerLevel world, BlockPos position) {
            double best = Double.POSITIVE_INFINITY;
            for (BlockPos stand : stands) {
                best = Math.min(best, CostModel.heuristic(position, stand));
            }
            return best;
        }

        @Override
        public BlockPos anchor(ServerLevel world) {
            return target;
        }

        @Override
        public String identityKey() {
            return "interaction:" + compact(target) + ':' + positionsKey(stands)
                    + ":state=" + expectedTargetState;
        }

        @Override
        public boolean resolvable(ServerLevel world) {
            return expectedTargetState.isEmpty() || (world != null
                    && expectedTargetState.equals(world.getBlockState(target).toString()));
        }

        @Override
        public String fingerprint(ServerLevel world) {
            String actual = world == null
                    ? "unresolved" : world.getBlockState(target).toString();
            return identityKey() + "@actual=" + actual;
        }

        @Override
        public boolean dynamic() {
            return !expectedTargetState.isEmpty();
        }
    }

    /**
     * Keeps the bot inside a horizontal distance ring around either a fixed point or a tracked
     * entity. A missing tracked entity makes the goal stale instead of silently using old data.
     */
    record FollowRing(UUID targetEntityId,
                      BlockPos fixedOrLastKnownCenter,
                      int minimumRadius,
                      int maximumRadius,
                      int verticalTolerance) implements NavGoal {
        public FollowRing {
            fixedOrLastKnownCenter = requirePosition(
                    fixedOrLastKnownCenter, "follow center");
            if (minimumRadius < 0 || maximumRadius < minimumRadius || verticalTolerance < 0) {
                throw new IllegalArgumentException("invalid follow ring");
            }
        }

        public FollowRing(BlockPos center,
                          int minimumRadius,
                          int maximumRadius,
                          int verticalTolerance) {
            this(null, center, minimumRadius, maximumRadius, verticalTolerance);
        }

        public FollowRing(UUID targetEntityId,
                          BlockPos lastKnownCenter,
                          int minimumRadius,
                          int maximumRadius) {
            this(targetEntityId, lastKnownCenter, minimumRadius, maximumRadius, 3);
        }

        @Override
        public boolean accepts(ServerLevel world, BlockPos position) {
            BlockPos center = anchor(world);
            if (center == null || Math.abs(position.getY() - center.getY()) > verticalTolerance) {
                return false;
            }
            double distance = horizontalDistance(position, center);
            return distance >= minimumRadius && distance <= maximumRadius;
        }

        @Override
        public double heuristic(ServerLevel world, BlockPos position) {
            BlockPos center = anchor(world);
            if (center == null) {
                return Double.POSITIVE_INFINITY;
            }
            double distance = horizontalDistance(position, center);
            double horizontalGap = distance < minimumRadius
                    ? minimumRadius - distance
                    : Math.max(0.0D, distance - maximumRadius);
            double verticalGap = Math.max(
                    0.0D, Math.abs(position.getY() - center.getY()) - verticalTolerance);
            return Math.max(
                    CostModel.minimumHorizontalCost(horizontalGap),
                    CostModel.minimumVerticalCost(position.getY(), center.getY(), verticalGap));
        }

        @Override
        public BlockPos anchor(ServerLevel world) {
            if (targetEntityId == null) {
                return fixedOrLastKnownCenter;
            }
            if (world == null) {
                return null;
            }
            Entity target = world.getEntity(targetEntityId);
            return target == null || !target.isAlive() ? null : target.blockPosition().immutable();
        }

        @Override
        public String identityKey() {
            return targetEntityId == null
                    ? "follow-fixed:" + compact(fixedOrLastKnownCenter) + ':'
                    + minimumRadius + ':' + maximumRadius + ':' + verticalTolerance
                    : "follow-entity:" + targetEntityId + ':' + minimumRadius + ':'
                    + maximumRadius + ':' + verticalTolerance;
        }

        @Override
        public boolean dynamic() {
            return targetEntityId != null;
        }
    }

    /** A bounded caller-supplied search escapes every listed threat by at least the given radius. */
    record Flee(Set<BlockPos> threats, int minimumDistance, BlockPos preferredDirectionAnchor)
            implements NavGoal {
        public Flee {
            threats = immutablePositions(threats);
            if (threats.isEmpty()) {
                throw new IllegalArgumentException("flee goal requires at least one threat");
            }
            if (minimumDistance <= 0) {
                throw new IllegalArgumentException("flee distance must be positive");
            }
            preferredDirectionAnchor = preferredDirectionAnchor == null
                    ? threats.iterator().next() : preferredDirectionAnchor.immutable();
        }

        public Flee(Set<BlockPos> threats, int minimumDistance) {
            this(threats, minimumDistance, null);
        }

        @Override
        public boolean accepts(ServerLevel world, BlockPos position) {
            long requiredSquared = (long) minimumDistance * minimumDistance;
            for (BlockPos threat : threats) {
                long dx = (long) position.getX() - threat.getX();
                long dz = (long) position.getZ() - threat.getZ();
                if (dx * dx + dz * dz < requiredSquared) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public double heuristic(ServerLevel world, BlockPos position) {
            double nearest = Double.POSITIVE_INFINITY;
            for (BlockPos threat : threats) {
                nearest = Math.min(nearest, horizontalDistance(position, threat));
            }
            return CostModel.minimumHorizontalCost(
                    Math.max(0.0D, minimumDistance - nearest));
        }

        @Override
        public BlockPos anchor(ServerLevel world) {
            return preferredDirectionAnchor;
        }

        @Override
        public String identityKey() {
            return "flee:" + minimumDistance + ':' + positionsKey(threats)
                    + ':' + compact(preferredDirectionAnchor);
        }
    }

    /** Logical composition. ANY chooses the cheapest accepted child; ALL requires overlap. */
    record Composite(Mode mode, List<NavGoal> goals) implements NavGoal {
        public Composite {
            mode = mode == null ? Mode.ANY : mode;
            if (goals == null || goals.isEmpty()) {
                throw new IllegalArgumentException("composite goal requires children");
            }
            List<NavGoal> canonical = new ArrayList<>(goals);
            if (canonical.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("composite goal contains null");
            }
            canonical.sort(Comparator.comparing(NavGoal::identityKey));
            goals = List.copyOf(canonical);
        }

        public static Composite anyOf(List<NavGoal> goals) {
            return new Composite(Mode.ANY, goals);
        }

        public static Composite allOf(List<NavGoal> goals) {
            return new Composite(Mode.ALL, goals);
        }

        @Override
        public boolean accepts(ServerLevel world, BlockPos position) {
            return mode == Mode.ANY
                    ? goals.stream().anyMatch(goal -> goal.resolvable(world)
                    && goal.accepts(world, position))
                    : goals.stream().allMatch(goal -> goal.resolvable(world)
                    && goal.accepts(world, position));
        }

        @Override
        public double heuristic(ServerLevel world, BlockPos position) {
            if (mode == Mode.ALL && goals.stream().anyMatch(goal -> !goal.resolvable(world))) {
                return Double.POSITIVE_INFINITY;
            }
            return mode == Mode.ANY
                    ? goals.stream().filter(goal -> goal.resolvable(world))
                    .mapToDouble(goal -> goal.heuristic(world, position)).min()
                    .orElse(Double.POSITIVE_INFINITY)
                    : goals.stream().mapToDouble(goal -> goal.heuristic(world, position)).max()
                    .orElse(Double.POSITIVE_INFINITY);
        }

        @Override
        public BlockPos anchor(ServerLevel world) {
            return goals.stream().filter(goal -> goal.resolvable(world))
                    .map(goal -> goal.anchor(world))
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        }

        @Override
        public String identityKey() {
            return "composite:" + mode + ':' + goals.stream()
                    .map(NavGoal::identityKey).reduce((left, right) -> left + '|' + right)
                    .orElse("");
        }

        @Override
        public String fingerprint(ServerLevel world) {
            return "composite:" + mode + ':' + goals.stream()
                    .map(goal -> goal.fingerprint(world))
                    .reduce((left, right) -> left + '|' + right).orElse("");
        }

        @Override
        public boolean resolvable(ServerLevel world) {
            return mode == Mode.ANY
                    ? goals.stream().anyMatch(goal -> goal.resolvable(world))
                    : goals.stream().allMatch(goal -> goal.resolvable(world));
        }

        @Override
        public boolean dynamic() {
            return goals.stream().anyMatch(NavGoal::dynamic);
        }

        public enum Mode {
            ANY,
            ALL
        }
    }

    private static BlockPos requirePosition(BlockPos position, String label) {
        if (position == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return position.immutable();
    }

    private static Set<BlockPos> immutablePositions(Set<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return Set.of();
        }
        List<BlockPos> sorted = positions.stream()
                .map(position -> requirePosition(position, "goal position"))
                .sorted(Comparator.comparingLong(BlockPos::asLong))
                .toList();
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static String positionsKey(Set<BlockPos> positions) {
        return positions.stream().map(NavGoal::compact)
                .reduce((left, right) -> left + ';' + right).orElse("");
    }

    private static double horizontalDistance(BlockPos first, BlockPos second) {
        double dx = (double) first.getX() - second.getX();
        double dz = (double) first.getZ() - second.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String compact(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }
}
