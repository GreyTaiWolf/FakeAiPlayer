package io.github.greytaiwolf.fakeaiplayer.building.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stable identity of design geometry, independent of a preview/project instance.
 *
 * <p>Unlike {@link BuildingPlanFingerprint}, this deliberately excludes plan id, display name,
 * revision and instance-scoped metadata such as owner, session, world and anchor. The same public
 * building code can therefore be compared across players and placements.</p>
 */
public final class BuildingDesignFingerprint {
    private static final Set<String> INSTANCE_KEYS = Set.of(
            "owner", "owner_id", "player", "player_id", "session", "session_id",
            "plan_id", "project_id", "preview_id", "world", "world_id", "dimension",
            "anchor", "anchor_x", "anchor_y", "anchor_z", "rotation", "mirror");

    private BuildingDesignFingerprint() {
    }

    public static String sha256(BuildingPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("building_plan_missing");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            field(digest, Integer.toString(plan.schemaVersion()));
            field(digest, plan.width() + "," + plan.height() + "," + plan.depth());
            field(digest, Long.toString(plan.seed()));
            field(digest, plan.generatorVersion());
            for (Map.Entry<String, String> entry : plan.metadata().entrySet()) {
                if (isDesignMetadata(entry.getKey())) {
                    field(digest, "meta:" + entry.getKey());
                    field(digest, entry.getValue());
                }
            }
            for (PlanPlacement placement : plan.placements()) {
                field(digest, placement.id());
                field(digest, placement.dx() + "," + placement.dy() + "," + placement.dz());
                field(digest, placement.state().blockId());
                for (Map.Entry<String, String> entry : placement.state().properties().entrySet()) {
                    field(digest, entry.getKey());
                    field(digest, entry.getValue());
                }
                field(digest, placement.operation().name());
                field(digest, placement.replacePolicy().name());
                field(digest, placement.materialRole().name());
                field(digest, placement.phase().name());
                field(digest, placement.componentId());
                for (String dependency : new TreeSet<>(placement.dependencies())) {
                    field(digest, dependency);
                }
                field(digest, placement.atomicGroup());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static boolean isDesignMetadata(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        if (INSTANCE_KEYS.contains(normalized)) {
            return false;
        }
        return !normalized.startsWith("owner_")
                && !normalized.startsWith("session_")
                && !normalized.startsWith("preview_")
                && !normalized.startsWith("anchor_");
    }

    private static void field(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
