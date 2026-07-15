package io.github.greytaiwolf.fakeaiplayer.building.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeSet;

/** Stable plan identity used for preview revisions, restart recovery and regression evidence. */
public final class BuildingPlanFingerprint {
    private BuildingPlanFingerprint() {
    }

    public static String sha256(BuildingPlan plan) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            field(digest, Integer.toString(plan.schemaVersion()));
            field(digest, plan.planId());
            field(digest, Integer.toString(plan.revision()));
            field(digest, plan.name());
            field(digest, plan.width() + "," + plan.height() + "," + plan.depth());
            field(digest, Long.toString(plan.seed()));
            field(digest, plan.generatorVersion());
            for (Map.Entry<String, String> entry : plan.metadata().entrySet()) {
                field(digest, "meta:" + entry.getKey());
                field(digest, entry.getValue());
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
                // Dependency order has no semantic meaning; canonicalize it for a stable hash.
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

    private static void field(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
