package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TraversalPolicyTest {
    @Test
    void emergencyAndAmbientPoliciesNeverGrantWorldMutation() {
        assertFalse(TraversalPolicy.AMBIENT_DRY_OPEN.allowsDigging());
        assertFalse(TraversalPolicy.AMBIENT_DRY_OPEN.allowsPillaring());
        assertFalse(TraversalPolicy.ESCAPE_DRY_OPEN.allowsDigging());
        assertFalse(TraversalPolicy.ESCAPE_DRY_OPEN.allowsPillaring());
        assertTrue(TraversalPolicy.AMBIENT_DRY_OPEN.requiresOpenGoal());
        assertTrue(TraversalPolicy.ESCAPE_DRY_OPEN.requiresOpenGoal());
    }

    @Test
    void waterRequiresExplicitOptIn() {
        assertFalse(TraversalPolicy.TASK_WALK_DRY.allowsWater());
        assertFalse(TraversalPolicy.TASK_MUTATING_DRY.allowsWater());
        assertTrue(TraversalPolicy.WATER_CAPABLE.allowsWater());
        assertFalse(TraversalPolicy.WATER_CAPABLE.allowsDigging());
        assertFalse(TraversalPolicy.WATER_CAPABLE.allowsPillaring());
    }

    @Test
    void opennessRequiresBothSpaceAndDistinctExits() {
        assertFalse(new LocalOpenness.Result(20, Set.of(Direction.NORTH)).isOpen());
        assertFalse(new LocalOpenness.Result(7, Set.of(Direction.NORTH, Direction.SOUTH)).isOpen());
        assertTrue(new LocalOpenness.Result(8, Set.of(Direction.NORTH, Direction.SOUTH)).isOpen());
    }

    @Test
    void ambientBoundsAreCircularAndVerticallyLimited() {
        TraversalBounds bounds = TraversalBounds.around(BlockPos.ZERO, 10, 3);
        assertTrue(bounds.contains(new BlockPos(6, 3, 8)));
        assertFalse(bounds.contains(new BlockPos(8, 0, 8)));
        assertFalse(bounds.contains(new BlockPos(0, 4, 0)));
    }
}
