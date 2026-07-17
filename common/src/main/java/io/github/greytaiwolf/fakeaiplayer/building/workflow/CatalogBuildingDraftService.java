package io.github.greytaiwolf.fakeaiplayer.building.workflow;

import io.github.greytaiwolf.fakeaiplayer.building.catalog.BuildingCatalog;
import io.github.greytaiwolf.fakeaiplayer.building.catalog.BuildingCatalogEntry;
import io.github.greytaiwolf.fakeaiplayer.building.catalog.BuildingSeedCode;
import io.github.greytaiwolf.fakeaiplayer.building.generator.MultiStoreyBuildingGenerator;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingDesignFingerprint;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlan;
import io.github.greytaiwolf.fakeaiplayer.building.plan.PlanTransform;
import io.github.greytaiwolf.fakeaiplayer.building.site.BuildingSiteCandidate;
import io.github.greytaiwolf.fakeaiplayer.building.site.BuildingSiteSelector;
import io.github.greytaiwolf.fakeaiplayer.building.site.BuildingTerrainAdapter;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/** Shared command/AI workflow for resolving one public code into a reviewed projection. */
public final class CatalogBuildingDraftService {
    public static final int DEFAULT_SEARCH_RADIUS = 32;
    public static final int MAX_SEARCH_RADIUS = 64;

    private CatalogBuildingDraftService() {
    }

    public static PreparedDraft prepare(ServerPlayer viewer,
                                        AIPlayerEntity bot,
                                        BuildingSeedCode code,
                                        int searchRadius) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(bot, "bot");
        Objects.requireNonNull(code, "code");
        if (searchRadius < 0 || searchRadius > MAX_SEARCH_RADIUS) {
            throw new IllegalArgumentException(
                    "building_search_radius_outside_0_" + MAX_SEARCH_RADIUS + ": " + searchRadius);
        }

        BuildingCatalogEntry entry = BuildingCatalog.resolve(code);
        if (!entry.usesCurrentGenerator()) {
            throw new IllegalStateException(
                    "building_catalog_generator_unavailable: " + entry.generatorVersion());
        }
        String planId = "fakeaiplayer:building_catalog/"
                + entry.catalogVersion() + "/" + entry.seedCode().value();
        String name = entry.archetype().id() + " #" + entry.seedCode().value();
        BuildingPlan design = new MultiStoreyBuildingGenerator().generate(
                entry.toRequest(planId, name));
        String designHash = BuildingDesignFingerprint.sha256(design);

        Rotation preferredRotation = rotationForFront(viewer.getDirection().getOpposite());
        Optional<BuildingSiteCandidate> selected = BuildingSiteSelector.select(
                bot, design, preferredRotation, searchRadius);
        if (selected.isPresent()) {
            try {
                BuildingSiteCandidate candidate = selected.get();
                BuildingTerrainAdapter.AdaptedPlan adapted = BuildingTerrainAdapter.adapt(
                        design, candidate.transform(), candidate.survey());
                return new PreparedDraft(
                        entry,
                        adapted.plan(),
                        designHash,
                        adapted.anchor(),
                        adapted.transform(),
                        true,
                        candidate.strategy(),
                        candidate.survey().signature(),
                        "",
                        searchRadius);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                return manualFallback(
                        viewer, entry, design, designHash, preferredRotation, searchRadius,
                        "terrain_adaptation_failed:" + safeReason(exception));
            }
        }
        return manualFallback(
                viewer, entry, design, designHash, preferredRotation, searchRadius,
                "no_loaded_observable_site");
    }

    private static PreparedDraft manualFallback(ServerPlayer viewer,
                                                BuildingCatalogEntry entry,
                                                BuildingPlan plan,
                                                String designHash,
                                                Rotation rotation,
                                                int searchRadius,
                                                String reason) {
        PlanTransform transform = new PlanTransform(Mirror.NONE, rotation);
        int width = transform.transformedWidth(plan.width(), plan.depth());
        int depth = transform.transformedDepth(plan.width(), plan.depth());
        int distance = Math.max(width, depth) / 2 + 5;
        BlockPos center = viewer.blockPosition().relative(viewer.getDirection(), distance);
        BlockPos anchor = new BlockPos(
                center.getX() - width / 2,
                viewer.blockPosition().getY(),
                center.getZ() - depth / 2);
        return new PreparedDraft(
                entry,
                plan,
                designHash,
                anchor,
                transform,
                false,
                "MANUAL_UNSURVEYED",
                "",
                reason,
                searchRadius);
    }

    private static String safeReason(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 160 ? message : message.substring(0, 160);
    }

    private static Rotation rotationForFront(Direction front) {
        return switch (front) {
            case NORTH -> Rotation.NONE;
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public record PreparedDraft(
            BuildingCatalogEntry entry,
            BuildingPlan plan,
            String designHash,
            BlockPos anchor,
            PlanTransform transform,
            boolean automaticallySelectedSite,
            String siteStrategy,
            String siteSignature,
            String fallbackReason,
            int searchRadius
    ) {
        public PreparedDraft {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(plan, "plan");
            if (designHash == null || designHash.length() != 64) {
                throw new IllegalArgumentException("invalid_building_design_hash");
            }
            anchor = Objects.requireNonNull(anchor, "anchor").immutable();
            Objects.requireNonNull(transform, "transform");
            siteStrategy = siteStrategy == null ? "MANUAL_UNSURVEYED" : siteStrategy;
            siteSignature = siteSignature == null ? "" : siteSignature;
            fallbackReason = fallbackReason == null ? "" : fallbackReason;
        }
    }
}
