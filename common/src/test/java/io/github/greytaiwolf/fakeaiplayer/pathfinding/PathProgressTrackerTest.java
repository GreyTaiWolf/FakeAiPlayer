package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PathProgressTrackerTest {
    @Test
    void lateralMovementWithoutGoalImprovementEventuallyStalls() {
        PathProgressTracker tracker = new PathProgressTracker(3, 10);
        Vec3 target = new Vec3(10.0D, 0.0D, 0.0D);

        assertEquals(PathProgressTracker.Stall.NONE,
                tracker.sample(new Vec3(0.0D, 0.0D, 0.0D), target));
        assertEquals(PathProgressTracker.Stall.NONE,
                tracker.sample(new Vec3(0.0D, 0.0D, 1.0D), target));
        assertEquals(PathProgressTracker.Stall.NONE,
                tracker.sample(new Vec3(0.0D, 0.0D, 2.0D), target));
        assertEquals(PathProgressTracker.Stall.NO_GOAL_PROGRESS,
                tracker.sample(new Vec3(0.0D, 0.0D, 3.0D), target));
    }

    @Test
    void alternatingBetweenTwoCellsIsDetectedAsOscillation() {
        PathProgressTracker tracker = new PathProgressTracker(10, 2);
        Vec3 target = new Vec3(10.5D, 0.0D, 1.0D);
        Vec3 cellA = new Vec3(0.5D, 0.0D, 0.5D);
        Vec3 cellB = new Vec3(0.5D, 0.0D, 1.5D);

        assertEquals(PathProgressTracker.Stall.NONE, tracker.sample(cellA, target));
        assertEquals(PathProgressTracker.Stall.NONE, tracker.sample(cellB, target));
        assertEquals(PathProgressTracker.Stall.NONE, tracker.sample(cellA, target));
        assertEquals(PathProgressTracker.Stall.OSCILLATION, tracker.sample(cellB, target));
    }

    @Test
    void meaningfulMovementTowardGoalResetsNoProgressWindow() {
        PathProgressTracker tracker = new PathProgressTracker(3, 10);
        Vec3 target = new Vec3(10.0D, 0.0D, 0.0D);

        tracker.sample(new Vec3(0.0D, 0.0D, 0.0D), target);
        tracker.sample(new Vec3(0.0D, 0.0D, 1.0D), target);
        tracker.sample(new Vec3(0.0D, 0.0D, 2.0D), target);
        assertEquals(2, tracker.noProgressTicks());

        assertEquals(PathProgressTracker.Stall.NONE,
                tracker.sample(new Vec3(1.0D, 0.0D, 0.0D), target));
        assertEquals(0, tracker.noProgressTicks());
        assertEquals(9.0D, tracker.bestDistance(), 1.0E-9D);

        assertEquals(PathProgressTracker.Stall.NONE,
                tracker.sample(new Vec3(1.0D, 0.0D, 1.0D), target));
        assertEquals(PathProgressTracker.Stall.NONE,
                tracker.sample(new Vec3(2.0D, 0.0D, 0.0D), target));
        assertEquals(0, tracker.noProgressTicks(),
                "a later improvement must open a fresh no-progress window");
    }
}
