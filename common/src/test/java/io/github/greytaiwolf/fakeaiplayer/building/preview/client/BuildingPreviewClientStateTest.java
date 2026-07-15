package io.github.greytaiwolf.fakeaiplayer.building.preview.client;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildPhase;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy;
import io.github.greytaiwolf.fakeaiplayer.building.preview.BuildingPreviewFingerprint;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewBeginS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewChunkS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewClearS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewCommitS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.PayloadLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingPreviewClientStateTest {
    private static final String PLAN_HASH = "1".repeat(PayloadLimits.PREVIEW_HASH_LENGTH);
    private static final UUID BOT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final List<BlockStateSpec> PALETTE = List.of(
            new BlockStateSpec("minecraft:oak_planks"));

    private final BuildingPreviewClientState state = BuildingPreviewClientState.INSTANCE;

    @BeforeEach
    void resetState() {
        state.clearAll("test_reset");
    }

    @Test
    void acceptsChunksOutOfOrderAndPublishesOnlyOnCommit() {
        List<BuildingPreviewChunkS2C.Cell> cells = gridCells(257, 17);
        Transfer transfer = transfer(
                UUID.fromString("00000000-0000-0000-0000-000000000101"),
                1, 17, 1, 16, PALETTE, cells);

        state.handle(transfer.begin());
        state.handle(transfer.chunks().get(1));
        state.handle(transfer.chunks().get(0));
        assertTrue(state.active().isEmpty(), "staged chunks must never be rendered");

        state.handle(transfer.commit());

        BuildingPreviewClientState.Snapshot active = state.active().orElseThrow();
        assertEquals(transfer.begin().sessionId(), active.sessionId());
        assertEquals(257, active.cells().size());
        assertEquals(cells, active.cells());
    }

    @Test
    void keepsTheOldSnapshotUntilACompleteNewCommitSwitchesAtomically() {
        Transfer first = transfer(
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                1, 1, 1, 1, PALETTE, List.of(cell(0, 0, 0, 0)));
        publish(first);

        Transfer second = transfer(
                UUID.fromString("00000000-0000-0000-0000-000000000202"),
                1, 2, 1, 1, PALETTE, List.of(
                        cell(0, 0, 0, 0),
                        cell(1, 0, 0, 0)));
        state.handle(second.begin());
        state.handle(second.chunks().get(0));

        assertEquals(first.begin().sessionId(), state.active().orElseThrow().sessionId());

        state.handle(second.commit());

        assertEquals(second.begin().sessionId(), state.active().orElseThrow().sessionId());
        assertEquals(2, state.active().orElseThrow().cells().size());
    }

    @Test
    void rejectsCommitWhenAChunkIsMissing() {
        Transfer transfer = transfer(
                UUID.fromString("00000000-0000-0000-0000-000000000301"),
                1, 17, 1, 16, PALETTE, gridCells(257, 17));

        state.handle(transfer.begin());
        state.handle(transfer.chunks().get(0));
        state.handle(transfer.commit());

        assertTrue(state.active().isEmpty());
    }

    @Test
    void rejectsADuplicateChunk() {
        Transfer transfer = transfer(
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                1, 1, 1, 1, PALETTE, List.of(cell(0, 0, 0, 0)));

        state.handle(transfer.begin());
        state.handle(transfer.chunks().get(0));
        state.handle(transfer.chunks().get(0));
        state.handle(transfer.commit());

        assertTrue(state.active().isEmpty());
    }

    @Test
    void rejectsDuplicateCoordinatesAcrossChunks() {
        List<BuildingPreviewChunkS2C.Cell> cells = new ArrayList<>(gridCells(256, 17));
        cells.add(cell(0, 0, 0, 0));
        Transfer transfer = transfer(
                UUID.fromString("00000000-0000-0000-0000-000000000402"),
                1, 17, 1, 16, PALETTE, cells);

        state.handle(transfer.begin());
        transfer.chunks().forEach(state::handle);
        state.handle(transfer.commit());

        assertTrue(state.active().isEmpty());
    }

    @Test
    void rejectsOutOfBoundsCellsAndUnknownPaletteIndexes() {
        Transfer outside = transfer(
                UUID.fromString("00000000-0000-0000-0000-000000000501"),
                1, 1, 1, 1, PALETTE, List.of(cell(1, 0, 0, 0)));
        state.handle(outside.begin());
        state.handle(outside.chunks().get(0));
        state.handle(outside.commit());
        assertTrue(state.active().isEmpty());

        Transfer badPalette = transfer(
                UUID.fromString("00000000-0000-0000-0000-000000000502"),
                1, 1, 1, 1, PALETTE, List.of(cell(0, 0, 0, 1)));
        state.handle(badPalette.begin());
        state.handle(badPalette.chunks().get(0));
        state.handle(badPalette.commit());
        assertTrue(state.active().isEmpty());
    }

    @Test
    void rejectsACompleteTransferWhoseAdvertisedDigestDoesNotMatchItsCells() {
        Transfer valid = transfer(
                UUID.fromString("00000000-0000-0000-0000-000000000503"),
                1, 1, 1, 1, PALETTE, List.of(cell(0, 0, 0, 0)));
        String forgedHash = "2".repeat(PayloadLimits.PREVIEW_HASH_LENGTH);
        BuildingPreviewBeginS2C forgedBegin = new BuildingPreviewBeginS2C(
                valid.begin().sessionId(),
                valid.begin().botId(),
                valid.begin().botName(),
                valid.begin().planId(),
                valid.begin().planRevision(),
                valid.begin().planHash(),
                forgedHash,
                valid.begin().transformRevision(),
                valid.begin().dimension(),
                valid.begin().anchorX(),
                valid.begin().anchorY(),
                valid.begin().anchorZ(),
                valid.begin().width(),
                valid.begin().height(),
                valid.begin().depth(),
                valid.begin().placementCount(),
                valid.begin().chunkCount(),
                valid.begin().palette());
        BuildingPreviewChunkS2C forgedChunk = new BuildingPreviewChunkS2C(
                forgedBegin.sessionId(),
                forgedHash,
                forgedBegin.transformRevision(),
                0,
                valid.chunks().get(0).cells());

        state.handle(forgedBegin);
        state.handle(forgedChunk);
        state.handle(new BuildingPreviewCommitS2C(
                forgedBegin.sessionId(), forgedHash, forgedBegin.transformRevision()));

        assertTrue(state.active().isEmpty());
    }

    @Test
    void neverLetsAnOlderTransformRevisionReplaceTheActiveSnapshot() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000601");
        Transfer current = transfer(
                sessionId, 2, 1, 1, 1, PALETTE, List.of(cell(0, 0, 0, 0)));
        publish(current);

        Transfer stale = transfer(
                sessionId, 1, 2, 1, 1, PALETTE, List.of(
                        cell(0, 0, 0, 0),
                        cell(1, 0, 0, 0)));
        publish(stale);

        BuildingPreviewClientState.Snapshot active = state.active().orElseThrow();
        assertEquals(2, active.transformRevision());
        assertEquals(current.begin().previewHash(), active.previewHash());
        assertEquals(1, active.cells().size());
    }

    @Test
    void clearAffectsOnlyTheMatchingSession() {
        Transfer transfer = transfer(
                UUID.fromString("00000000-0000-0000-0000-000000000701"),
                1, 1, 1, 1, PALETTE, List.of(cell(0, 0, 0, 0)));
        publish(transfer);

        state.handle(new BuildingPreviewClearS2C(
                UUID.fromString("00000000-0000-0000-0000-000000000702"), "wrong"));
        assertTrue(state.active().isPresent());
        assertFalse("wrong".equals(state.lastClearReason()));

        state.handle(new BuildingPreviewClearS2C(transfer.begin().sessionId(), "cancelled"));
        assertTrue(state.active().isEmpty());
        assertEquals("cancelled", state.lastClearReason());
    }

    private void publish(Transfer transfer) {
        state.handle(transfer.begin());
        transfer.chunks().forEach(state::handle);
        state.handle(transfer.commit());
    }

    private static List<BuildingPreviewChunkS2C.Cell> gridCells(int count, int width) {
        List<BuildingPreviewChunkS2C.Cell> cells = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            cells.add(cell(index % width, 0, index / width, 0));
        }
        return List.copyOf(cells);
    }

    private static BuildingPreviewChunkS2C.Cell cell(
            int x, int y, int z, int paletteIndex
    ) {
        return new BuildingPreviewChunkS2C.Cell(
                x, y, z, paletteIndex,
                CellOperation.PLACE,
                ReplacePolicy.REQUIRE_EMPTY,
                BuildPhase.FOUNDATION);
    }

    private static Transfer transfer(
            UUID sessionId,
            int transformRevision,
            int width,
            int height,
            int depth,
            List<BlockStateSpec> palette,
            List<BuildingPreviewChunkS2C.Cell> cells
    ) {
        String previewHash = BuildingPreviewFingerprint.sha256(
                sessionId,
                BOT_ID,
                "test:preview",
                3,
                PLAN_HASH,
                transformRevision,
                "minecraft:overworld",
                10,
                64,
                -5,
                width,
                height,
                depth,
                palette,
                cells);
        int chunkCount = (cells.size() + PayloadLimits.MAX_PREVIEW_CHUNK_CELLS - 1)
                / PayloadLimits.MAX_PREVIEW_CHUNK_CELLS;
        BuildingPreviewBeginS2C begin = new BuildingPreviewBeginS2C(
                sessionId,
                BOT_ID,
                "Builder",
                "test:preview",
                3,
                PLAN_HASH,
                previewHash,
                transformRevision,
                "minecraft:overworld",
                10,
                64,
                -5,
                width,
                height,
                depth,
                cells.size(),
                chunkCount,
                palette);
        List<BuildingPreviewChunkS2C> chunks = new ArrayList<>(chunkCount);
        for (int index = 0; index < chunkCount; index++) {
            int start = index * PayloadLimits.MAX_PREVIEW_CHUNK_CELLS;
            int end = Math.min(cells.size(), start + PayloadLimits.MAX_PREVIEW_CHUNK_CELLS);
            chunks.add(new BuildingPreviewChunkS2C(
                    sessionId,
                    previewHash,
                    transformRevision,
                    index,
                    List.copyOf(cells.subList(start, end))));
        }
        return new Transfer(
                begin,
                List.copyOf(chunks),
                new BuildingPreviewCommitS2C(sessionId, previewHash, transformRevision));
    }

    private record Transfer(
            BuildingPreviewBeginS2C begin,
            List<BuildingPreviewChunkS2C> chunks,
            BuildingPreviewCommitS2C commit
    ) {
    }
}
