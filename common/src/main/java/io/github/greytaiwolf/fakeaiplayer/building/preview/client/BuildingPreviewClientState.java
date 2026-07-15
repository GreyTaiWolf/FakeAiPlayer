package io.github.greytaiwolf.fakeaiplayer.building.preview.client;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;
import io.github.greytaiwolf.fakeaiplayer.building.preview.BuildingPreviewFingerprint;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewBeginS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewChunkS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewClearS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewCommitS2C;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/**
 * Loader-neutral client assembly state for an atomic preview transfer.
 *
 * <p>Chunks are never rendered while they are arriving. A snapshot is exposed only when the
 * matching commit proves that every expected chunk and cell was received.</p>
 */
public final class BuildingPreviewClientState {
    public static final BuildingPreviewClientState INSTANCE = new BuildingPreviewClientState();

    private Pending pending;
    private Snapshot active;
    private String lastClearReason = "";

    private BuildingPreviewClientState() {
    }

    public synchronized void handle(BuildingPreviewBeginS2C payload) {
        if (active != null
                && active.sessionId().equals(payload.sessionId())
                && payload.transformRevision() <= active.transformRevision()) {
            return;
        }
        if (pending != null
                && pending.begin().sessionId().equals(payload.sessionId())
                && payload.transformRevision() <= pending.begin().transformRevision()) {
            return;
        }
        pending = new Pending(payload, new HashMap<>());
    }

    public synchronized void handle(BuildingPreviewChunkS2C payload) {
        if (pending == null || !pending.matches(
                payload.sessionId(), payload.previewHash(), payload.transformRevision())) {
            return;
        }
        BuildingPreviewBeginS2C begin = pending.begin();
        if (payload.chunkIndex() < 0 || payload.chunkIndex() >= begin.chunkCount()) {
            pending = null;
            return;
        }
        for (BuildingPreviewChunkS2C.Cell cell : payload.cells()) {
            if (cell.paletteIndex() >= begin.palette().size()
                    || cell.dx() < 0 || cell.dx() >= begin.width()
                    || cell.dy() < 0 || cell.dy() >= begin.height()
                    || cell.dz() < 0 || cell.dz() >= begin.depth()) {
                pending = null;
                return;
            }
        }
        if (pending.chunks().putIfAbsent(payload.chunkIndex(), payload.cells()) != null) {
            pending = null;
        }
    }

    public synchronized void handle(BuildingPreviewCommitS2C payload) {
        if (pending == null || !pending.matches(
                payload.sessionId(), payload.previewHash(), payload.transformRevision())) {
            return;
        }
        BuildingPreviewBeginS2C begin = pending.begin();
        if (pending.chunks().size() != begin.chunkCount()) {
            pending = null;
            return;
        }
        List<BuildingPreviewChunkS2C.Cell> cells = new ArrayList<>(begin.placementCount());
        HashSet<CellKey> occupied = new HashSet<>(begin.placementCount());
        for (int chunkIndex = 0; chunkIndex < begin.chunkCount(); chunkIndex++) {
            List<BuildingPreviewChunkS2C.Cell> chunk = pending.chunks().get(chunkIndex);
            if (chunk == null) {
                pending = null;
                return;
            }
            for (BuildingPreviewChunkS2C.Cell cell : chunk) {
                if (!occupied.add(new CellKey(cell.dx(), cell.dy(), cell.dz()))) {
                    pending = null;
                    return;
                }
                cells.add(cell);
            }
        }
        if (cells.size() != begin.placementCount()) {
            pending = null;
            return;
        }
        String computedHash = BuildingPreviewFingerprint.sha256(
                begin.sessionId(), begin.botId(), begin.planId(), begin.planRevision(),
                begin.planHash(), begin.transformRevision(), begin.dimension(),
                begin.anchorX(), begin.anchorY(), begin.anchorZ(),
                begin.width(), begin.height(), begin.depth(), begin.palette(), cells);
        if (!computedHash.equals(begin.previewHash())) {
            pending = null;
            return;
        }
        active = new Snapshot(
                begin.sessionId(),
                begin.botId(),
                begin.botName(),
                begin.planId(),
                begin.planRevision(),
                begin.planHash(),
                begin.previewHash(),
                begin.transformRevision(),
                begin.dimension(),
                new BlockPos(begin.anchorX(), begin.anchorY(), begin.anchorZ()),
                begin.width(),
                begin.height(),
                begin.depth(),
                begin.palette(),
                cells);
        pending = null;
        lastClearReason = "";
    }

    public synchronized void handle(BuildingPreviewClearS2C payload) {
        if (pending != null && pending.begin().sessionId().equals(payload.sessionId())) {
            pending = null;
        }
        if (active != null && active.sessionId().equals(payload.sessionId())) {
            active = null;
            lastClearReason = payload.reason();
        }
    }

    public synchronized Optional<Snapshot> active() {
        return Optional.ofNullable(active);
    }

    public synchronized String lastClearReason() {
        return lastClearReason;
    }

    public synchronized void clearAll(String reason) {
        pending = null;
        active = null;
        lastClearReason = reason == null ? "" : reason;
    }

    private record Pending(
            BuildingPreviewBeginS2C begin,
            Map<Integer, List<BuildingPreviewChunkS2C.Cell>> chunks
    ) {
        private boolean matches(UUID sessionId, String previewHash, int transformRevision) {
            return begin.sessionId().equals(sessionId)
                    && begin.previewHash().equals(previewHash)
                    && begin.transformRevision() == transformRevision;
        }
    }

    public record Snapshot(
            UUID sessionId,
            UUID botId,
            String botName,
            String planId,
            int planRevision,
            String planHash,
            String previewHash,
            int transformRevision,
            String dimension,
            BlockPos anchor,
            int width,
            int height,
            int depth,
            List<BlockStateSpec> palette,
            List<BuildingPreviewChunkS2C.Cell> cells
    ) {
        public Snapshot {
            anchor = anchor.immutable();
            palette = List.copyOf(palette);
            cells = List.copyOf(cells);
        }
    }

    private record CellKey(int x, int y, int z) {
    }
}
