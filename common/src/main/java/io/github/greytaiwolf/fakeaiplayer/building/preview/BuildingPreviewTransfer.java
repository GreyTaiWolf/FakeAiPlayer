package io.github.greytaiwolf.fakeaiplayer.building.preview;

import java.util.Objects;
import java.util.UUID;

/** Mutable server-thread cursor for one bounded, revision-specific preview publication. */
final class BuildingPreviewTransfer {
    private final UUID sessionId;
    private final String previewHash;
    private final int transformRevision;
    private final int chunkCount;
    private int nextChunk;

    BuildingPreviewTransfer(UUID sessionId,
                            String previewHash,
                            int transformRevision,
                            int chunkCount) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (previewHash == null || previewHash.isBlank() || transformRevision < 0 || chunkCount < 1) {
            throw new IllegalArgumentException("invalid_building_preview_transfer");
        }
        this.previewHash = previewHash;
        this.transformRevision = transformRevision;
        this.chunkCount = chunkCount;
    }

    boolean matches(UUID candidateSessionId, String candidateHash, int candidateRevision) {
        return sessionId.equals(candidateSessionId)
                && previewHash.equals(candidateHash)
                && transformRevision == candidateRevision;
    }

    UUID sessionId() {
        return sessionId;
    }

    String previewHash() {
        return previewHash;
    }

    int transformRevision() {
        return transformRevision;
    }

    int nextChunk() {
        if (complete()) {
            throw new IllegalStateException("building_preview_transfer_complete");
        }
        return nextChunk++;
    }

    int remainingChunks() {
        return chunkCount - nextChunk;
    }

    boolean complete() {
        return nextChunk == chunkCount;
    }
}
