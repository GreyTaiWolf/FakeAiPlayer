package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** Bounded dry flood-fill used to reject ambient/escape goals at the end of a cul-de-sac. */
public final class LocalOpenness {
    public static final int DEFAULT_RADIUS = 4;
    public static final int MIN_REACHABLE_CELLS = 8;
    public static final int MIN_EXIT_DIRECTIONS = 2;

    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private LocalOpenness() {
    }

    public static boolean isOpen(ServerLevel world, BlockPos origin, TraversalPolicy policy) {
        return analyze(world, origin, policy, DEFAULT_RADIUS).isOpen();
    }

    public static Result analyze(ServerLevel world,
                                 BlockPos origin,
                                 TraversalPolicy policy,
                                 int radius) {
        int boundedRadius = Math.max(1, radius);
        if (!Standability.isStandable(world, origin, policy)) {
            return new Result(0, Set.of());
        }

        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        EnumSet<Direction> exits = EnumSet.noneOf(Direction.class);
        BlockPos immutableOrigin = origin.immutable();
        pending.add(immutableOrigin);
        visited.add(immutableOrigin);

        while (!pending.isEmpty()) {
            BlockPos current = pending.removeFirst();
            recordBoundaryDirection(immutableOrigin, current, boundedRadius, exits);
            for (Direction direction : HORIZONTAL) {
                BlockPos next = findWalkNeighbor(world, current, direction, policy);
                if (next == null || outsideRadius(immutableOrigin, next, boundedRadius)) {
                    continue;
                }
                BlockPos immutable = next.immutable();
                if (visited.add(immutable)) {
                    pending.addLast(immutable);
                }
            }
        }
        return new Result(visited.size(), Set.copyOf(exits));
    }

    private static BlockPos findWalkNeighbor(ServerLevel world,
                                             BlockPos current,
                                             Direction direction,
                                             TraversalPolicy policy) {
        BlockPos adjacent = current.relative(direction);
        if (Standability.isStandable(world, adjacent, policy)) {
            return adjacent;
        }
        BlockPos up = adjacent.above();
        if (Standability.isStandable(world, up, policy)
                && collisionEmpty(world, current.above(2))) {
            return up;
        }
        BlockPos down = adjacent.below();
        if (Standability.isStandable(world, down, policy)
                && collisionEmpty(world, adjacent)
                && collisionEmpty(world, adjacent.above())) {
            return down;
        }
        return null;
    }

    private static boolean collisionEmpty(ServerLevel world, BlockPos pos) {
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static boolean outsideRadius(BlockPos origin, BlockPos candidate, int radius) {
        return Math.abs(candidate.getX() - origin.getX()) > radius
                || Math.abs(candidate.getZ() - origin.getZ()) > radius
                || Math.abs(candidate.getY() - origin.getY()) > 2;
    }

    private static void recordBoundaryDirection(BlockPos origin,
                                                BlockPos current,
                                                int radius,
                                                EnumSet<Direction> exits) {
        int dx = current.getX() - origin.getX();
        int dz = current.getZ() - origin.getZ();
        if (Math.abs(dx) < radius && Math.abs(dz) < radius) {
            return;
        }
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            exits.add(dx > 0 ? Direction.EAST : Direction.WEST);
        }
        if (Math.abs(dz) >= Math.abs(dx) && dz != 0) {
            exits.add(dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }
    }

    public record Result(int reachableCells, Set<Direction> exitDirections) {
        public Result {
            exitDirections = Set.copyOf(exitDirections);
        }

        public boolean isOpen() {
            return reachableCells >= MIN_REACHABLE_CELLS
                    && exitDirections.size() >= MIN_EXIT_DIRECTIONS;
        }
    }
}
