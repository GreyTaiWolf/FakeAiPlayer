package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import net.minecraft.core.BlockPos;

/** Optional hard spatial boundary for a path and every replan derived from it. */
public record TraversalBounds(BlockPos anchor, int horizontalRadius, int verticalRadius) {
    private static final TraversalBounds UNBOUNDED = new TraversalBounds(null, 0, 0);

    public TraversalBounds {
        anchor = anchor == null ? null : anchor.immutable();
        horizontalRadius = Math.max(0, horizontalRadius);
        verticalRadius = Math.max(0, verticalRadius);
    }

    public static TraversalBounds unbounded() {
        return UNBOUNDED;
    }

    public static TraversalBounds around(BlockPos anchor, int horizontalRadius, int verticalRadius) {
        if (anchor == null) {
            throw new IllegalArgumentException("bounded traversal requires an anchor");
        }
        return new TraversalBounds(anchor, horizontalRadius, verticalRadius);
    }

    public boolean isBounded() {
        return anchor != null;
    }

    public boolean contains(BlockPos position) {
        if (!isBounded()) {
            return true;
        }
        long dx = (long) position.getX() - anchor.getX();
        long dz = (long) position.getZ() - anchor.getZ();
        return dx * dx + dz * dz <= (long) horizontalRadius * horizontalRadius
                && Math.abs(position.getY() - anchor.getY()) <= verticalRadius;
    }
}
