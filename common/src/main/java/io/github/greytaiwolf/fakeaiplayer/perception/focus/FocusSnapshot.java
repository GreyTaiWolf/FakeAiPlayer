package io.github.greytaiwolf.fakeaiplayer.perception.focus;

import com.google.gson.Gson;

import java.util.List;
import java.util.Map;

/**
 * Immutable, sanitized semantic description of the object under the Bot's crosshair.
 * Minecraft objects and raw NBT must never escape the server thread through this DTO.
 */
public record FocusSnapshot(
        FocusState state,
        FocusKind kind,
        FocusSource source,
        String targetKey,
        long observedTick,
        String dimension,
        Position position,
        double distance,
        boolean lineOfSight,
        boolean withinInteractionReach,
        boolean stale,
        String id,
        String displayName,
        String hitFace,
        BlockDetails block,
        EntityDetails entity,
        ItemDetails item,
        BehaviorDetails behavior
) {
    private static final Gson GSON = new Gson();

    public static FocusSnapshot miss(long tick, String dimension) {
        return new FocusSnapshot(
                FocusState.NO_TARGET,
                FocusKind.MISS,
                FocusSource.BOT_GAZE,
                "miss:" + safe(dimension),
                tick,
                safe(dimension),
                null,
                0.0D,
                false,
                false,
                false,
                "",
                "",
                "",
                null,
                null,
                null,
                new BehaviorDetails(ObservedBehavior.UNKNOWN, 1.0D, List.of("gaze_ray_missed")));
    }

    public static FocusSnapshot disabled(long tick, String dimension) {
        return miss(tick, dimension)
                .withTargetKey("disabled:" + safe(dimension))
                .withTrackingState(FocusState.DISABLED, false);
    }

    private FocusSnapshot withTargetKey(String newTargetKey) {
        return new FocusSnapshot(
                state,
                kind,
                source,
                safe(newTargetKey),
                observedTick,
                dimension,
                position,
                distance,
                lineOfSight,
                withinInteractionReach,
                stale,
                id,
                displayName,
                hitFace,
                block,
                entity,
                item,
                behavior);
    }

    public FocusSnapshot withTrackingState(FocusState newState, boolean nowStale) {
        return new FocusSnapshot(
                newState,
                kind,
                source,
                targetKey,
                observedTick,
                dimension,
                position,
                distance,
                nowStale ? false : lineOfSight,
                withinInteractionReach,
                nowStale,
                id,
                displayName,
                hitFace,
                block,
                entity,
                item,
                behavior);
    }

    public FocusSummary summary() {
        return new FocusSummary(
                state,
                kind,
                source,
                targetToken(),
                safe(id),
                safe(displayName),
                position,
                distance,
                withinInteractionReach,
                stale,
                safe(hitFace),
                behavior == null ? ObservedBehavior.UNKNOWN : behavior.label(),
                describe(),
                observedTick);
    }

    /** Stable request correlation token; deliberately separate from the tracker's location key. */
    public String targetToken() {
        String key = safe(targetKey);
        String semanticId = safe(id);
        return semanticId.isBlank() ? key : key + ":" + semanticId;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public String toSummaryJson() {
        return GSON.toJson(summary());
    }

    /** Excludes the observation tick so an unchanged target does not create one history row per sample. */
    public String materialFingerprint() {
        return GSON.toJson(new MaterialFingerprint(
                kind,
                targetKey,
                id,
                withinInteractionReach,
                block == null ? null : new BlockMaterialFingerprint(
                        block.blockId(),
                        block.x(),
                        block.y(),
                        block.z(),
                        block.properties(),
                        block.destroySpeed(),
                        block.unbreakable(),
                        block.requiresCorrectTool(),
                        block.heldToolCorrect(),
                        block.heldTool(),
                        block.fluid(),
                        block.light(),
                        block.blockEntity() == null ? "" : block.blockEntity().type()),
                entity == null ? null : new EntityMaterialFingerprint(
                        entity.health(),
                        entity.maxHealth(),
                        entity.armor(),
                        entity.alive(),
                        entity.hostile(),
                        entity.baby(),
                        entity.inWater(),
                        entity.onFire(),
                        entity.sprinting(),
                        entity.crouching(),
                        entity.usingItem(),
                        entity.pose(),
                        entity.mainHandItem(),
                        entity.attackTargetType()),
                item,
                behavior == null ? ObservedBehavior.UNKNOWN : behavior.label()));
    }

    private String describe() {
        if (state == FocusState.DISABLED) {
            return "semantic gaze is disabled";
        }
        if (kind == FocusKind.MISS || state == FocusState.NO_TARGET) {
            return "the Bot's gaze does not currently hit a target";
        }
        String target = safe(displayName).isBlank() ? safe(id) : safe(displayName) + " (" + safe(id) + ")";
        String where = position == null
                ? ""
                : " at " + position.x() + "," + position.y() + "," + position.z();
        String action = behavior == null || behavior.label() == ObservedBehavior.UNKNOWN
                ? ""
                : ", behavior=" + behavior.label();
        return target + where + ", distance=" + distance + action;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record Position(double x, double y, double z) {
    }

    public record Velocity(double x, double y, double z, double speed) {
    }

    public record FocusSummary(
            FocusState state,
            FocusKind kind,
            FocusSource source,
            String targetToken,
            String id,
            String displayName,
            Position position,
            double distance,
            boolean withinInteractionReach,
            boolean stale,
            String hitFace,
            ObservedBehavior behavior,
            String summary,
            long observedTick
    ) {
    }

    public record BlockDetails(
            String blockId,
            int x,
            int y,
            int z,
            Map<String, String> properties,
            float destroySpeed,
            boolean unbreakable,
            boolean requiresCorrectTool,
            boolean heldToolCorrect,
            String heldTool,
            String fluid,
            int light,
            BlockEntityDetails blockEntity
    ) {
        public BlockDetails {
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }

    public record BlockEntityDetails(
            String type,
            int containerSlots,
            int occupiedSlots,
            int itemCount,
            Map<String, Integer> itemCounts,
            boolean itemCountsTruncated,
            boolean contentsReadable,
            String restrictionReason
    ) {
        public BlockEntityDetails {
            itemCounts = itemCounts == null ? Map.of() : Map.copyOf(itemCounts);
        }
    }

    public record EntityDetails(
            int runtimeId,
            String entityType,
            Position position,
            Velocity velocity,
            Float health,
            Float maxHealth,
            Integer armor,
            boolean alive,
            boolean hostile,
            boolean baby,
            boolean onGround,
            boolean inWater,
            boolean onFire,
            boolean sprinting,
            boolean crouching,
            boolean usingItem,
            String pose,
            String mainHandItem,
            String attackTargetType,
            Double attackTargetDistance,
            List<String> effects
    ) {
        public EntityDetails {
            effects = effects == null ? List.of() : List.copyOf(effects);
        }
    }

    public record ItemDetails(
            String itemId,
            int count,
            int damage,
            int maxDamage,
            String customName,
            boolean glowing
    ) {
    }

    public record BehaviorDetails(
            ObservedBehavior label,
            double confidence,
            List<String> evidence
    ) {
        public BehaviorDetails {
            label = label == null ? ObservedBehavior.UNKNOWN : label;
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    private record MaterialFingerprint(
            FocusKind kind,
            String targetKey,
            String id,
            boolean withinInteractionReach,
            BlockMaterialFingerprint block,
            EntityMaterialFingerprint entity,
            ItemDetails item,
            ObservedBehavior behavior
    ) {
    }

    private record BlockMaterialFingerprint(
            String blockId,
            int x,
            int y,
            int z,
            Map<String, String> properties,
            float destroySpeed,
            boolean unbreakable,
            boolean requiresCorrectTool,
            boolean heldToolCorrect,
            String heldTool,
            String fluid,
            int light,
            String blockEntityType
    ) {
    }

    private record EntityMaterialFingerprint(
            Float health,
            Float maxHealth,
            Integer armor,
            boolean alive,
            boolean hostile,
            boolean baby,
            boolean inWater,
            boolean onFire,
            boolean sprinting,
            boolean crouching,
            boolean usingItem,
            String pose,
            String mainHandItem,
            String attackTargetType
    ) {
    }
}
