package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildPhase;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One bounded chunk of projection cells. It becomes visible only after the matching commit. */
public record BuildingPreviewChunkS2C(
        UUID sessionId,
        String previewHash,
        int transformRevision,
        int chunkIndex,
        List<Cell> cells
) implements CustomPacketPayload {
    public static final Type<BuildingPreviewChunkS2C> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "building_preview_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingPreviewChunkS2C> CODEC =
            StreamCodec.ofMember(BuildingPreviewChunkS2C::write, BuildingPreviewChunkS2C::new);

    public BuildingPreviewChunkS2C {
        if (sessionId == null || !PayloadLimits.validSha256Hex(previewHash)) {
            throw new IllegalArgumentException("invalid preview chunk identity");
        }
        if (chunkIndex < 0 || chunkIndex >= PayloadLimits.MAX_PREVIEW_CHUNKS) {
            throw new IllegalArgumentException("preview chunk index is out of range");
        }
        cells = cells == null ? List.of() : List.copyOf(cells);
        PayloadLimits.requireSize(cells.size(), PayloadLimits.MAX_PREVIEW_CHUNK_CELLS, "preview chunk cells");
    }

    private BuildingPreviewChunkS2C(RegistryFriendlyByteBuf buf) {
        this(
                buf.readUUID(),
                buf.readUtf(PayloadLimits.PREVIEW_HASH_LENGTH),
                buf.readInt(),
                buf.readInt(),
                readCells(buf));
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(sessionId);
        buf.writeUtf(previewHash, PayloadLimits.PREVIEW_HASH_LENGTH);
        buf.writeInt(transformRevision);
        buf.writeInt(chunkIndex);
        buf.writeInt(cells.size());
        for (Cell cell : cells) {
            buf.writeInt(cell.dx());
            buf.writeInt(cell.dy());
            buf.writeInt(cell.dz());
            buf.writeInt(cell.paletteIndex());
            buf.writeByte(operationCode(cell.operation()));
            buf.writeByte(policyCode(cell.replacePolicy()));
            buf.writeByte(phaseCode(cell.phase()));
        }
    }

    private static List<Cell> readCells(RegistryFriendlyByteBuf buf) {
        int count = PayloadLimits.readSize(
                buf, PayloadLimits.MAX_PREVIEW_CHUNK_CELLS, "preview chunk cells");
        List<Cell> cells = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            cells.add(new Cell(
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    operation(buf.readUnsignedByte()),
                    policy(buf.readUnsignedByte()),
                    phase(buf.readUnsignedByte())));
        }
        return List.copyOf(cells);
    }

    private static int operationCode(CellOperation value) {
        return switch (value) {
            case PLACE -> 1;
            case CLEAR -> 2;
            case PRESERVE -> 3;
            case TEMPORARY -> 4;
        };
    }

    private static CellOperation operation(int code) {
        return switch (code) {
            case 1 -> CellOperation.PLACE;
            case 2 -> CellOperation.CLEAR;
            case 3 -> CellOperation.PRESERVE;
            case 4 -> CellOperation.TEMPORARY;
            default -> throw new IllegalArgumentException("invalid preview operation code: " + code);
        };
    }

    private static int policyCode(ReplacePolicy value) {
        return switch (value) {
            case REQUIRE_EMPTY -> 1;
            case REPLACE_REPLACEABLE -> 2;
            case REPLACE_NATURAL -> 3;
            case CLEAR_AUTHORIZED -> 4;
            case PRESERVE_EXISTING -> 5;
            case FORCE_AUTHORIZED -> 6;
        };
    }

    private static ReplacePolicy policy(int code) {
        return switch (code) {
            case 1 -> ReplacePolicy.REQUIRE_EMPTY;
            case 2 -> ReplacePolicy.REPLACE_REPLACEABLE;
            case 3 -> ReplacePolicy.REPLACE_NATURAL;
            case 4 -> ReplacePolicy.CLEAR_AUTHORIZED;
            case 5 -> ReplacePolicy.PRESERVE_EXISTING;
            case 6 -> ReplacePolicy.FORCE_AUTHORIZED;
            default -> throw new IllegalArgumentException("invalid preview replace-policy code: " + code);
        };
    }

    private static int phaseCode(BuildPhase value) {
        return switch (value) {
            case SITE_SURVEY -> 1;
            case SITE_PREPARATION -> 2;
            case FOUNDATION -> 3;
            // Added after the original wire table; keep existing codes stable for compatibility.
            case CONSTRUCTION_ACCESS -> 13;
            case FRAME -> 4;
            case FLOORS_AND_STAIRS -> 5;
            case WALLS_AND_OPENINGS -> 6;
            case ROOF -> 7;
            case EXTERIOR_FEATURES -> 8;
            case INTERIOR_DETAILS -> 9;
            case LIGHTING -> 10;
            case CLEANUP -> 11;
            case VERIFY -> 12;
        };
    }

    private static BuildPhase phase(int code) {
        return switch (code) {
            case 1 -> BuildPhase.SITE_SURVEY;
            case 2 -> BuildPhase.SITE_PREPARATION;
            case 3 -> BuildPhase.FOUNDATION;
            case 13 -> BuildPhase.CONSTRUCTION_ACCESS;
            case 4 -> BuildPhase.FRAME;
            case 5 -> BuildPhase.FLOORS_AND_STAIRS;
            case 6 -> BuildPhase.WALLS_AND_OPENINGS;
            case 7 -> BuildPhase.ROOF;
            case 8 -> BuildPhase.EXTERIOR_FEATURES;
            case 9 -> BuildPhase.INTERIOR_DETAILS;
            case 10 -> BuildPhase.LIGHTING;
            case 11 -> BuildPhase.CLEANUP;
            case 12 -> BuildPhase.VERIFY;
            default -> throw new IllegalArgumentException("invalid preview phase code: " + code);
        };
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public record Cell(
            int dx,
            int dy,
            int dz,
            int paletteIndex,
            CellOperation operation,
            ReplacePolicy replacePolicy,
            BuildPhase phase
    ) {
        public Cell {
            if (paletteIndex < 0 || paletteIndex >= PayloadLimits.MAX_PREVIEW_PALETTE) {
                throw new IllegalArgumentException("preview palette index is out of range");
            }
            if (operation == null || replacePolicy == null || phase == null) {
                throw new IllegalArgumentException("preview cell semantic data is missing");
            }
        }
    }
}
