package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class Node {
    private final BlockPos pos;
    private final double gCost;
    private final double hCost;
    private final MoveType moveType;
    private final Node parent;
    private final Direction heading;
    private final int depth;

    public Node(BlockPos pos, double gCost, double hCost, MoveType moveType, Node parent) {
        this(pos, gCost, hCost, moveType, parent, inheritedHeading(pos, parent));
    }

    public Node(BlockPos pos,
                double gCost,
                double hCost,
                MoveType moveType,
                Node parent,
                Direction heading) {
        this.pos = pos.immutable();
        this.gCost = gCost;
        this.hCost = hCost;
        this.moveType = moveType;
        this.parent = parent;
        this.heading = heading;
        this.depth = parent == null ? 0 : parent.depth + 1;
    }

    public BlockPos pos() {
        return pos;
    }

    public double gCost() {
        return gCost;
    }

    public double hCost() {
        return hCost;
    }

    public MoveType moveType() {
        return moveType;
    }

    public Node parent() {
        return parent;
    }

    /** Incoming horizontal direction, retained across vertical steps for turn-cost state. */
    public Direction heading() {
        return heading;
    }

    /** Number of graph transitions from the search start. */
    public int depth() {
        return depth;
    }

    public double fCost() {
        return gCost + hCost;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof Node node && pos.equals(node.pos);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pos);
    }

    private static Direction inheritedHeading(BlockPos position, Node parent) {
        if (parent == null) {
            return null;
        }
        int dx = Integer.compare(position.getX() - parent.pos().getX(), 0);
        int dz = Integer.compare(position.getZ() - parent.pos().getZ(), 0);
        if (dx != 0 && dz != 0) {
            // The current cost model has no eight-way turn state. Reset after a diagonal instead
            // of inheriting an unrelated older cardinal heading and charging a phantom turn.
            return null;
        }
        Direction incoming = CostModel.horizontalDirection(parent.pos(), position);
        return incoming == null ? parent.heading() : incoming;
    }
}
