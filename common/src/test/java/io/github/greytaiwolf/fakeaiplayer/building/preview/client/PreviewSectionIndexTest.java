package io.github.greytaiwolf.fakeaiplayer.building.preview.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildPhase;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewChunkS2C;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PreviewSectionIndexTest {
    @Test
    void roundRobinPromotionPreventsSectionsBeyondScanBudgetFromStarving() {
        PreviewSectionIndex index = new PreviewSectionIndex();
        BuildingPreviewClientState.Snapshot preview = snapshotWithSections(5);
        Set<Integer> scannedSections = new HashSet<>();

        for (int frame = 0; frame < 5; frame++) {
            PreviewRenderBudget budget = new PreviewRenderBudget(2, 2, 0);
            scan:
            for (PreviewSectionIndex.Section section
                    : index.orderedSections(preview, Vec3.ZERO)) {
                for (PreviewSectionIndex.IndexedCell ignored : section.cells()) {
                    if (!budget.tryScan()) {
                        break scan;
                    }
                    scannedSections.add(section.stableOrder());
                }
            }
        }

        assertEquals(Set.of(0, 1, 2, 3, 4), scannedSections);
    }

    @Test
    void newPreviewAndExplicitClearResetTheFairCursor() {
        PreviewSectionIndex index = new PreviewSectionIndex();
        BuildingPreviewClientState.Snapshot preview = snapshotWithSections(3);

        assertEquals(0, index.orderedSections(preview, Vec3.ZERO).getFirst().stableOrder());
        assertEquals(1, index.orderedSections(preview, Vec3.ZERO).getFirst().stableOrder());

        BuildingPreviewClientState.Snapshot revised = new BuildingPreviewClientState.Snapshot(
                preview.sessionId(), preview.botId(), preview.botName(), preview.planId(),
                preview.planRevision(), preview.planHash(), "b".repeat(64),
                preview.transformRevision() + 1, preview.dimension(), preview.anchor(),
                preview.width(), preview.height(), preview.depth(), preview.palette(), preview.cells());
        assertEquals(0, index.orderedSections(revised, Vec3.ZERO).getFirst().stableOrder());

        index.orderedSections(revised, Vec3.ZERO);
        index.clear();
        assertEquals(0, index.orderedSections(revised, Vec3.ZERO).getFirst().stableOrder());
    }

    private static BuildingPreviewClientState.Snapshot snapshotWithSections(int count) {
        List<BuildingPreviewChunkS2C.Cell> cells = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new BuildingPreviewChunkS2C.Cell(
                        index * 16, 0, 0, 0,
                        CellOperation.PLACE,
                        ReplacePolicy.REQUIRE_EMPTY,
                        BuildPhase.FOUNDATION))
                .toList();
        return new BuildingPreviewClientState.Snapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000901"),
                UUID.fromString("00000000-0000-0000-0000-000000000902"),
                "bot",
                "test:fair-preview",
                1,
                "a".repeat(64),
                "c".repeat(64),
                1,
                "minecraft:overworld",
                BlockPos.ZERO,
                count * 16,
                1,
                1,
                List.of(new BlockStateSpec("minecraft:oak_planks")),
                cells);
    }
}
