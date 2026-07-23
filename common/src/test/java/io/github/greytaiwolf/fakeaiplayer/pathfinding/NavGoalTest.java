package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class NavGoalTest {
    @Test
    void exactAcceptsOnlyRequestedFeet() {
        NavGoal.Exact goal = new NavGoal.Exact(new BlockPos(4, 70, -2));
        assertTrue(goal.accepts(null, new BlockPos(4, 70, -2)));
        assertFalse(goal.accepts(null, new BlockPos(4, 70, -1)));
    }

    @Test
    void nearSeparatesHorizontalRadiusAndVerticalTolerance() {
        NavGoal.Near goal = new NavGoal.Near(BlockPos.ZERO, 3, 1);
        assertTrue(goal.accepts(null, new BlockPos(3, 1, 0)));
        assertFalse(goal.accepts(null, new BlockPos(3, 2, 0)));
        assertFalse(goal.accepts(null, new BlockPos(3, 0, 1)));
    }

    @Test
    void interactionDefensivelyCopiesAndCanonicalizesStands() {
        Set<BlockPos> mutable = new HashSet<>();
        mutable.add(new BlockPos(2, 0, 0));
        mutable.add(new BlockPos(-1, 0, 0));
        NavGoal.Interaction goal = new NavGoal.Interaction(BlockPos.ZERO, mutable, "oak");
        mutable.clear();
        assertEquals(2, goal.stands().size());
        assertTrue(goal.accepts(null, new BlockPos(-1, 0, 0)));
        assertThrows(UnsupportedOperationException.class,
                () -> goal.stands().add(BlockPos.ZERO));
    }

    @Test
    void interactionFingerprintIncludesTargetState() {
        Set<BlockPos> stands = Set.of(new BlockPos(1, 0, 0));
        assertNotEquals(
                new NavGoal.Interaction(BlockPos.ZERO, stands, "oak").identityKey(),
                new NavGoal.Interaction(BlockPos.ZERO, stands, "spruce").identityKey());
    }

    @Test
    void fixedFollowRingHonorsInnerOuterAndVerticalBounds() {
        NavGoal.FollowRing ring = new NavGoal.FollowRing(BlockPos.ZERO, 2, 4, 1);
        assertFalse(ring.accepts(null, new BlockPos(1, 0, 0)));
        assertTrue(ring.accepts(null, new BlockPos(3, 1, 0)));
        assertFalse(ring.accepts(null, new BlockPos(5, 0, 0)));
        assertFalse(ring.accepts(null, new BlockPos(3, 2, 0)));
    }

    @Test
    void fleeRequiresSeparationFromEveryThreat() {
        NavGoal.Flee flee = new NavGoal.Flee(
                Set.of(BlockPos.ZERO, new BlockPos(10, 0, 0)), 4);
        assertFalse(flee.accepts(null, new BlockPos(3, 0, 0)));
        assertTrue(flee.accepts(null, new BlockPos(5, 0, 5)));
    }

    @Test
    void semanticGoalHeuristicsDoNotDoubleChargeDropProgress() {
        double oneBlockDrop = CostModel.stepCost(MoveType.DROP_DOWN, 1);
        assertTrue(new NavGoal.Near(new BlockPos(1, 0, 0), 0, 0)
                .heuristic(null, new BlockPos(0, 1, 0)) <= oneBlockDrop);
        assertTrue(new NavGoal.FollowRing(new BlockPos(1, 0, 0), 0, 0, 0)
                .heuristic(null, new BlockPos(0, 1, 0)) <= oneBlockDrop);
        assertTrue(new NavGoal.Flee(Set.of(BlockPos.ZERO), 2)
                .heuristic(null, new BlockPos(1, 1, 0)) <= oneBlockDrop);
    }

    @Test
    void escapeRequestCannotGainMutationOrUnboundedScope() {
        NavGoal.Flee flee = new NavGoal.Flee(Set.of(BlockPos.ZERO), 4);
        TraversalBounds bounds = TraversalBounds.around(BlockPos.ZERO, 8, 2);
        NavigationRequest request = NavigationRequest.escape(flee, bounds, "unit_test");
        assertEquals(TraversalPolicy.ESCAPE_DRY_OPEN, request.traversalPolicy());
        assertFalse(request.allowDig());
        assertFalse(request.allowPillar());
        assertEquals(bounds, request.bounds());
        assertThrows(IllegalArgumentException.class,
                () -> NavigationRequest.escape(
                        flee, TraversalBounds.unbounded(), "unit_test"));
        assertThrows(IllegalArgumentException.class,
                () -> NavigationRequest.ambient(
                        NavGoal.near(BlockPos.ZERO, 2, 1),
                        TraversalBounds.unbounded(), "unit_test"));
        assertThrows(IllegalArgumentException.class,
                () -> request.withBounds(TraversalBounds.unbounded()));
        NavigationRequest ambient = NavigationRequest.ambient(
                NavGoal.near(BlockPos.ZERO, 2, 1), bounds, "unit_test");
        assertThrows(IllegalArgumentException.class,
                () -> ambient.withBounds(TraversalBounds.unbounded()));
        assertThrows(IllegalArgumentException.class,
                () -> request.withGoal(NavGoal.exact(BlockPos.ZERO)));
        assertThrows(IllegalArgumentException.class,
                () -> NavigationRequest.walk(flee, "unsafe_flee"));
        assertThrows(IllegalArgumentException.class,
                () -> NavigationRequest.walk(
                        NavGoal.Composite.anyOf(List.of(
                                flee, NavGoal.exact(new BlockPos(8, 0, 0)))),
                        "unsafe_composite_flee"));
    }

    @Test
    void compositeAnyIsCanonicalAndOrderIndependent() {
        NavGoal first = NavGoal.exact(new BlockPos(1, 0, 0));
        NavGoal second = NavGoal.exact(new BlockPos(2, 0, 0));
        NavGoal.Composite left = NavGoal.Composite.anyOf(List.of(first, second));
        NavGoal.Composite right = NavGoal.Composite.anyOf(List.of(second, first));
        assertEquals(left.identityKey(), right.identityKey());
        assertTrue(left.accepts(null, new BlockPos(2, 0, 0)));
    }

    @Test
    void compositeAllRequiresOverlap() {
        NavGoal.Composite all = NavGoal.Composite.allOf(List.of(
                NavGoal.near(BlockPos.ZERO, 2, 1),
                NavGoal.near(new BlockPos(2, 0, 0), 2, 1)));
        assertTrue(all.accepts(null, new BlockPos(1, 0, 0)));
        assertFalse(all.accepts(null, new BlockPos(-1, 0, 0)));
    }

    @Test
    void invalidGoalParametersFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new NavGoal.Interaction(BlockPos.ZERO, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new NavGoal.Near(BlockPos.ZERO, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new NavGoal.Flee(Set.of(), 4));
        assertThrows(IllegalArgumentException.class,
                () -> new NavGoal.FollowRing(BlockPos.ZERO, 5, 4, 1));
        assertThrows(IllegalArgumentException.class,
                () -> NavGoal.Composite.anyOf(List.of()));
    }
}
