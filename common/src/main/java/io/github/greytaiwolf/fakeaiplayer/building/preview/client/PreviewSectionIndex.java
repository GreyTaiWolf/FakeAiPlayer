package io.github.greytaiwolf.fakeaiplayer.building.preview.client;

import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewChunkS2C;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Cached 16-cube index; large plans sort sections rather than all 65,536 cells every frame. */
public final class PreviewSectionIndex {
    private static final int SECTION_SIZE = 16;

    private UUID sessionId;
    private int transformRevision = -1;
    private String previewHash = "";
    private List<Section> sections = List.of();
    private int nextFairStableOrder;

    public List<Section> orderedSections(BuildingPreviewClientState.Snapshot preview, Vec3 camera) {
        refresh(preview);
        if (sections.size() < 2) {
            return sections;
        }
        List<Section> ordered = new ArrayList<>(sections);
        ordered.sort(Comparator
                .comparingDouble((Section section) -> distanceToBoxSquared(camera, section.bounds()))
                .thenComparingInt(Section::stableOrder));
        // Promote one stable section per frame before the distance-ordered remainder. Each section
        // contains at most 16^3 cells, below the normal scan budget, so a stationary large preview
        // cannot permanently starve sections beyond the first 16,384 cells. Stable-order fairness
        // is independent of camera-distance reordering between frames.
        int fairStableOrder = Math.floorMod(nextFairStableOrder, sections.size());
        nextFairStableOrder = (fairStableOrder + 1) % sections.size();
        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).stableOrder() == fairStableOrder) {
                if (index > 0) {
                    ordered.add(0, ordered.remove(index));
                }
                break;
            }
        }
        return ordered;
    }

    public void clear() {
        sessionId = null;
        transformRevision = -1;
        previewHash = "";
        sections = List.of();
        nextFairStableOrder = 0;
    }

    private void refresh(BuildingPreviewClientState.Snapshot preview) {
        if (preview.sessionId().equals(sessionId)
                && preview.transformRevision() == transformRevision
                && preview.previewHash().equals(previewHash)) {
            return;
        }

        Map<SectionKey, MutableSection> grouped = new LinkedHashMap<>();
        int stableOrder = 0;
        for (BuildingPreviewChunkS2C.Cell cell : preview.cells()) {
            BlockPos worldPos = preview.anchor().offset(cell.dx(), cell.dy(), cell.dz());
            SectionKey key = new SectionKey(
                    Math.floorDiv(worldPos.getX(), SECTION_SIZE),
                    Math.floorDiv(worldPos.getY(), SECTION_SIZE),
                    Math.floorDiv(worldPos.getZ(), SECTION_SIZE));
            MutableSection section = grouped.get(key);
            if (section == null) {
                section = new MutableSection(stableOrder++);
                grouped.put(key, section);
            }
            section.add(new IndexedCell(cell, worldPos.immutable()));
        }

        List<Section> next = new ArrayList<>(grouped.size());
        for (MutableSection section : grouped.values()) {
            next.add(section.freeze());
        }
        sections = List.copyOf(next);
        sessionId = preview.sessionId();
        transformRevision = preview.transformRevision();
        previewHash = preview.previewHash();
        nextFairStableOrder = 0;
    }

    private static double distanceToBoxSquared(Vec3 point, AABB box) {
        double dx = axisDistance(point.x, box.minX, box.maxX);
        double dy = axisDistance(point.y, box.minY, box.maxY);
        double dz = axisDistance(point.z, box.minZ, box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        return value > max ? value - max : 0.0D;
    }

    public record IndexedCell(BuildingPreviewChunkS2C.Cell cell, BlockPos worldPos) {
    }

    public record Section(int stableOrder, AABB bounds, List<IndexedCell> cells) {
        public Section {
            cells = List.copyOf(cells);
        }
    }

    private record SectionKey(int x, int y, int z) {
    }

    private static final class MutableSection {
        private final int stableOrder;
        private final List<IndexedCell> cells = new ArrayList<>();
        private int minX = Integer.MAX_VALUE;
        private int minY = Integer.MAX_VALUE;
        private int minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private int maxZ = Integer.MIN_VALUE;

        private MutableSection(int stableOrder) {
            this.stableOrder = stableOrder;
        }

        private void add(IndexedCell cell) {
            cells.add(cell);
            BlockPos pos = cell.worldPos();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        private Section freeze() {
            return new Section(stableOrder,
                    new AABB(minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D),
                    cells);
        }
    }
}
