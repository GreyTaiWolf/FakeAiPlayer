package io.github.greytaiwolf.fakeaiplayer.perception.focus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusSnapshotTest {
    @Test
    void compactSummaryDoesNotContainDetailedContainerData() {
        FocusSnapshot snapshot = blockSnapshot();

        String summary = snapshot.toSummaryJson();
        assertTrue(summary.contains("minecraft:chest"));
        assertTrue(summary.contains("TRACKING"));
        assertTrue(summary.contains("targetToken"));
        assertTrue(summary.contains("block:minecraft:overworld:1:minecraft:chest"));
        assertFalse(summary.contains("itemCounts"));
        assertFalse(summary.contains("minecraft:diamond"));
        assertTrue(snapshot.toJson().contains("minecraft:diamond"));
    }

    @Test
    void nestedCollectionsAreDefensivelyCopied() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("facing", "north");
        Map<String, Integer> items = new LinkedHashMap<>();
        items.put("minecraft:diamond", 2);
        FocusSnapshot.BlockEntityDetails blockEntity = new FocusSnapshot.BlockEntityDetails(
                "minecraft:chest", 27, 1, 2, items, false, true, "");
        FocusSnapshot.BlockDetails block = new FocusSnapshot.BlockDetails(
                "minecraft:chest", 1, 2, 3, properties, 2.5F, false,
                false, true, "minecraft:iron_axe", "", 12, blockEntity);

        properties.put("waterlogged", "true");
        items.put("minecraft:apple", 4);

        assertEquals(Map.of("facing", "north"), block.properties());
        assertEquals(Map.of("minecraft:diamond", 2), block.blockEntity().itemCounts());
        assertThrows(UnsupportedOperationException.class,
                () -> block.properties().put("open", "true"));
    }

    @Test
    void behaviorEvidenceIsDefensivelyCopied() {
        List<String> evidence = new ArrayList<>();
        evidence.add("speed=0.2");
        FocusSnapshot.BehaviorDetails behavior = new FocusSnapshot.BehaviorDetails(
                ObservedBehavior.MOVING, 0.9D, evidence);
        evidence.add("untrusted-late-write");

        assertEquals(List.of("speed=0.2"), behavior.evidence());
    }

    @Test
    void missIsAValidSuccessfulObservationShape() {
        FocusSnapshot miss = FocusSnapshot.miss(42L, "minecraft:overworld");
        assertEquals(FocusKind.MISS, miss.kind());
        assertEquals(FocusState.NO_TARGET, miss.state());
        assertEquals("miss:minecraft:overworld", miss.summary().targetToken());
        assertTrue(miss.toSummaryJson().contains("does not currently hit a target"));
    }

    @Test
    void staleSnapshotNoLongerClaimsCurrentLineOfSight() {
        FocusSnapshot stale = blockSnapshot().withTrackingState(FocusState.LOST_GRACE, true);

        assertTrue(stale.stale());
        assertFalse(stale.lineOfSight());
        assertEquals("minecraft:chest", stale.id());
    }

    private static FocusSnapshot blockSnapshot() {
        FocusSnapshot.BlockEntityDetails blockEntity = new FocusSnapshot.BlockEntityDetails(
                "minecraft:chest", 27, 1, 2,
                Map.of("minecraft:diamond", 2), false, true, "");
        FocusSnapshot.BlockDetails block = new FocusSnapshot.BlockDetails(
                "minecraft:chest", 1, 2, 3, Map.of("facing", "north"),
                2.5F, false, false, true, "minecraft:iron_axe", "", 12, blockEntity);
        return new FocusSnapshot(
                FocusState.TRACKING,
                FocusKind.BLOCK_ENTITY,
                FocusSource.BOT_GAZE,
                "block:minecraft:overworld:1",
                10L,
                "minecraft:overworld",
                new FocusSnapshot.Position(1, 2, 3),
                3.5D,
                true,
                true,
                false,
                "minecraft:chest",
                "Chest",
                "north",
                block,
                null,
                null,
                new FocusSnapshot.BehaviorDetails(ObservedBehavior.UNKNOWN, 1.0D, List.of("stationary_block")));
    }
}
