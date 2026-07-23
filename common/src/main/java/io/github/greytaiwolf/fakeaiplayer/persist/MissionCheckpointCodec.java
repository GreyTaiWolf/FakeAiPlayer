package io.github.greytaiwolf.fakeaiplayer.persist;

import io.github.greytaiwolf.fakeaiplayer.mission.CursorCheckpoint;
import io.github.greytaiwolf.fakeaiplayer.mission.RecoveryLedger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Additive codec for replay-safe Mission runtime accounting.
 *
 * <p>V0, V1, and V2 are read-only migration formats. V3 is the only writable format and stores
 * the context fingerprint and complete {@link CursorCheckpoint} in the same atomic runtime
 * payload as the recovery counters. A present malformed payload is rejected as a unit, so damage
 * can never silently reset a retry branch, timeout, or recovery budget.</p>
 */
public final class MissionCheckpointCodec {
    public static final int CURRENT_VERSION = 3;
    public static final String RUNTIME_BUDGET_V1 = "runtime_budget_v1";
    public static final String RUNTIME_BUDGET_V2 = "runtime_budget_v2";
    public static final String RUNTIME_BUDGET_V3 = "runtime_budget_v3";
    public static final String LEGACY_REVISION = "revision";
    public static final String ELAPSED_MISSION_TICKS = "elapsed_mission_ticks";
    public static final int MAX_COMPLETED_STEPS = 65_536;

    private static final int LEGACY_VERSION = 0;
    private static final String RUNTIME_BUDGET_PREFIX = "runtime_budget_v";
    private static final int V1_PAYLOAD_PARTS = 12;
    private static final int V2_PAYLOAD_PARTS = 16;
    private static final int V3_PAYLOAD_PARTS = 20;
    private static final int MAX_PAYLOAD_LENGTH = 524_288;
    private static final int MAX_ATTEMPT_ENTRIES = 256;
    private static final String FINGERPRINT_PATTERN = "[0-9a-f]{64}";
    private static final HexFormat HEX = HexFormat.of();

    private MissionCheckpointCodec() {
    }

    public static DecodeResult decode(Map<String, String> encoded) {
        Map<String, String> source;
        try {
            source = encoded == null ? Map.of() : Map.copyOf(encoded);
        } catch (RuntimeException exception) {
            return DecodeResult.corrupt(LEGACY_VERSION, "checkpoint_map_invalid");
        }

        java.util.List<String> runtimeKeys = source.keySet().stream()
                .filter(key -> key != null && key.startsWith(RUNTIME_BUDGET_PREFIX))
                .sorted()
                .toList();
        if (runtimeKeys.isEmpty()) {
            try {
                int completedSteps = optionalCompletedSteps(source, LEGACY_REVISION).orElse(0);
                int elapsedTicks = optionalNonNegative(source, ELAPSED_MISSION_TICKS).orElse(0);
                return DecodeResult.ok(LEGACY_VERSION, new Checkpoint(
                        completedSteps,
                        elapsedTicks,
                        RecoveryLedger.Snapshot.empty(),
                        ProgressSnapshot.legacy(completedSteps)));
            } catch (IllegalArgumentException exception) {
                return DecodeResult.corrupt(LEGACY_VERSION, exception.getMessage());
            }
        }

        if (runtimeKeys.size() != 1) {
            return DecodeResult.corrupt(-1, "multiple_runtime_budget_payloads");
        }
        String runtimeKey = runtimeKeys.get(0);
        final int keyVersion;
        try {
            keyVersion = decodeRuntimeKeyVersion(runtimeKey);
        } catch (IllegalArgumentException exception) {
            return DecodeResult.corrupt(-1, exception.getMessage());
        }
        if (keyVersion > CURRENT_VERSION) {
            return DecodeResult.unsupported(keyVersion);
        }
        if (keyVersion < 1) {
            return DecodeResult.corrupt(keyVersion, "runtime_budget_key_version_invalid");
        }

        String payload = source.get(runtimeKey);
        if (payload == null || payload.isBlank() || payload.length() > MAX_PAYLOAD_LENGTH) {
            return DecodeResult.corrupt(keyVersion, "runtime_budget_payload_invalid");
        }
        // The positive limit bounds attacker-controlled String allocations. Any additional
        // delimiter remains in the final element and therefore fails the exact shape check.
        String[] parts = payload.split("\\|", V3_PAYLOAD_PARTS + 1);
        final int version;
        try {
            version = requiredCanonicalNonNegative(parts[0], "runtime_budget_version");
        } catch (IllegalArgumentException exception) {
            return DecodeResult.corrupt(-1, exception.getMessage());
        }
        if (version > CURRENT_VERSION) {
            return DecodeResult.unsupported(version);
        }
        if (version != keyVersion) {
            return DecodeResult.corrupt(version, "runtime_budget_key_payload_version_mismatch");
        }
        int expectedParts = switch (version) {
            case 1 -> V1_PAYLOAD_PARTS;
            case 2 -> V2_PAYLOAD_PARTS;
            case 3 -> V3_PAYLOAD_PARTS;
            default -> -1;
        };
        if (expectedParts < 0) {
            return DecodeResult.corrupt(version, "runtime_budget_version_invalid");
        }
        if (parts.length != expectedParts) {
            return DecodeResult.corrupt(version, "runtime_budget_payload_shape_invalid");
        }

        try {
            return switch (version) {
                case 1 -> decodeV1(source, parts);
                case 2 -> decodeV2(source, parts);
                case 3 -> decodeV3(source, parts);
                default -> throw new IllegalStateException("unreachable_checkpoint_version");
            };
        } catch (IllegalArgumentException exception) {
            return DecodeResult.corrupt(version, exception.getMessage());
        }
    }

    private static DecodeResult decodeV1(Map<String, String> source, String[] parts) {
        int completedSteps = requiredCompletedSteps(parts[1], "runtime_completed_steps");
        int elapsedTicks = requiredNonNegative(parts[2], "runtime_elapsed_ticks");
        verifyLegacyAliases(source, completedSteps, elapsedTicks, false);
        RecoveryLedger.Snapshot recovery = new RecoveryLedger.Snapshot(
                decodeAttempts(parts[11], false),
                requiredNonNegative(parts[3], "runtime_recovery_total"),
                requiredNonNegative(parts[4], "runtime_recovery_no_progress"),
                requiredNonNegative(parts[5], "runtime_recovery_postcondition"));
        int snapshotSteps = requiredCompletedSteps(parts[6], "runtime_snapshot_steps");
        OptionalInt targetCount = optionalPayloadNonNegative(
                parts[7], "runtime_snapshot_target_count");
        Optional<Position> position = decodePosition(parts[8], parts[9], parts[10]);
        return DecodeResult.ok(1, new Checkpoint(
                completedSteps,
                elapsedTicks,
                recovery,
                new ProgressSnapshot(snapshotSteps, targetCount, position)));
    }

    private static DecodeResult decodeV2(Map<String, String> source, String[] parts) {
        UUID missionId = requiredUuid(parts[1]);
        int planRevision = requiredNonNegative(parts[2], "runtime_plan_revision");
        String planFingerprint = requiredFingerprint(
                parts[3], "runtime_plan_fingerprint_invalid");
        String intentFingerprint = requiredFingerprint(
                parts[4], "runtime_intent_fingerprint_invalid");
        int completedSteps = requiredCompletedSteps(parts[5], "runtime_completed_steps");
        int elapsedTicks = requiredNonNegative(parts[6], "runtime_elapsed_ticks");
        verifyLegacyAliases(source, completedSteps, elapsedTicks, false);
        RecoveryLedger.Snapshot recovery = new RecoveryLedger.Snapshot(
                decodeAttempts(parts[15], false),
                requiredNonNegative(parts[7], "runtime_recovery_total"),
                requiredNonNegative(parts[8], "runtime_recovery_no_progress"),
                requiredNonNegative(parts[9], "runtime_recovery_postcondition"));
        int snapshotSteps = requiredCompletedSteps(parts[10], "runtime_snapshot_steps");
        OptionalInt targetCount = optionalPayloadNonNegative(
                parts[11], "runtime_snapshot_target_count");
        Optional<Position> position = decodePosition(parts[12], parts[13], parts[14]);
        return DecodeResult.ok(2, new Checkpoint(
                missionId,
                planRevision,
                planFingerprint,
                intentFingerprint,
                completedSteps,
                elapsedTicks,
                recovery,
                new ProgressSnapshot(snapshotSteps, targetCount, position)));
    }

    private static DecodeResult decodeV3(Map<String, String> source, String[] parts) {
        verifyV3Checksum(parts);
        UUID missionId = requiredUuid(parts[1]);
        int planRevision = requiredCanonicalNonNegative(parts[2], "runtime_plan_revision");
        String planFingerprint = requiredFingerprint(
                parts[3], "runtime_plan_fingerprint_invalid");
        String intentFingerprint = requiredFingerprint(
                parts[4], "runtime_intent_fingerprint_invalid");
        String contextFingerprint = requiredFingerprint(
                parts[5], "runtime_context_fingerprint_invalid");
        int completedSteps = requiredCanonicalCompletedSteps(
                parts[6], "runtime_completed_steps");
        int elapsedTicks = requiredCanonicalNonNegative(parts[7], "runtime_elapsed_ticks");
        verifyLegacyAliases(source, completedSteps, elapsedTicks, true);
        RecoveryLedger.Snapshot recovery = new RecoveryLedger.Snapshot(
                decodeAttempts(parts[16], true),
                requiredCanonicalNonNegative(parts[8], "runtime_recovery_total"),
                requiredCanonicalNonNegative(parts[9], "runtime_recovery_no_progress"),
                requiredCanonicalNonNegative(parts[10], "runtime_recovery_postcondition"));
        int snapshotSteps = requiredCanonicalCompletedSteps(
                parts[11], "runtime_snapshot_steps");
        OptionalInt targetCount = optionalPayloadCanonicalNonNegative(
                parts[12], "runtime_snapshot_target_count");
        Optional<Position> position = decodePosition(
                parts[13], parts[14], parts[15], true);
        boolean replanAfterInterrupt = requiredCanonicalBoolean(
                parts[17], "runtime_replan_after_interrupt");

        CursorCheckpointCodec.DecodeResult cursorDecoded = CursorCheckpointCodec.decode(parts[18]);
        if (cursorDecoded.status() == CursorCheckpointCodec.Status.UNSUPPORTED_VERSION) {
            return DecodeResult.unsupported(
                    cursorDecoded.version(), "cursor_checkpoint_version_unsupported");
        }
        if (!cursorDecoded.valid()) {
            throw new IllegalArgumentException("runtime_cursor_checkpoint_invalid:"
                    + cursorDecoded.reason());
        }
        Checkpoint checkpoint = new Checkpoint(
                missionId,
                planRevision,
                planFingerprint,
                intentFingerprint,
                contextFingerprint,
                completedSteps,
                elapsedTicks,
                recovery,
                new ProgressSnapshot(snapshotSteps, targetCount, position),
                replanAfterInterrupt,
                cursorDecoded.checkpoint());
        if (!encodePayload(checkpoint).equals(String.join("|", parts))) {
            throw new IllegalArgumentException("runtime_budget_payload_noncanonical");
        }
        return DecodeResult.ok(3, checkpoint);
    }

    private static void verifyLegacyAliases(Map<String, String> source,
                                            int completedSteps,
                                            int elapsedTicks,
                                            boolean canonical) {
        OptionalInt legacyCompleted = canonical
                ? optionalCanonicalCompletedSteps(source, LEGACY_REVISION)
                : optionalCompletedSteps(source, LEGACY_REVISION);
        OptionalInt legacyElapsed = canonical
                ? optionalCanonicalNonNegative(source, ELAPSED_MISSION_TICKS)
                : optionalNonNegative(source, ELAPSED_MISSION_TICKS);
        if (legacyCompleted.isPresent() && legacyCompleted.getAsInt() != completedSteps) {
            throw new IllegalArgumentException("runtime_revision_mismatch");
        }
        if (legacyElapsed.isPresent() && legacyElapsed.getAsInt() != elapsedTicks) {
            throw new IllegalArgumentException("runtime_elapsed_ticks_mismatch");
        }
    }

    private static void verifyV3Checksum(String[] parts) {
        String encodedChecksum = parts[19];
        if (!encodedChecksum.matches(FINGERPRINT_PATTERN)) {
            throw new IllegalArgumentException("runtime_budget_checksum_invalid");
        }
        byte[] expected = HEX.parseHex(encodedChecksum);
        String payload = String.join("|", Arrays.asList(parts).subList(0, 19));
        byte[] actual = sha256(payload.getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("runtime_budget_checksum_mismatch");
        }
    }

    /**
     * Preserves unrelated metadata, updates the two legacy aliases, and writes only V3. All older
     * runtime keys are removed so a new save can never contain ambiguous checkpoint generations.
     */
    public static Map<String, String> encode(Map<String, String> base, Checkpoint checkpoint) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("mission_checkpoint_missing");
        }
        if (!checkpoint.bound()) {
            throw new IllegalArgumentException("mission_checkpoint_plan_binding_missing");
        }
        if (!checkpoint.current()) {
            throw new IllegalArgumentException("mission_checkpoint_v3_binding_missing");
        }
        Map<String, String> result = new LinkedHashMap<>(base == null ? Map.of() : base);
        java.util.List<String> unknownRuntimeKeys = result.keySet().stream()
                .filter(key -> key != null && key.startsWith(RUNTIME_BUDGET_PREFIX))
                .filter(key -> !key.equals(RUNTIME_BUDGET_V1)
                        && !key.equals(RUNTIME_BUDGET_V2)
                        && !key.equals(RUNTIME_BUDGET_V3))
                .toList();
        if (!unknownRuntimeKeys.isEmpty()) {
            throw new IllegalArgumentException("unknown_runtime_budget_key");
        }
        String payload = encodePayload(checkpoint);
        if (payload.length() > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("runtime_budget_payload_too_large");
        }
        result.remove(RUNTIME_BUDGET_V1);
        result.remove(RUNTIME_BUDGET_V2);
        result.remove(RUNTIME_BUDGET_V3);
        result.put(LEGACY_REVISION, String.valueOf(checkpoint.completedSteps()));
        result.put(ELAPSED_MISSION_TICKS, String.valueOf(checkpoint.elapsedMissionTicks()));
        result.put(RUNTIME_BUDGET_V3, payload);
        return Map.copyOf(result);
    }

    private static String encodePayload(Checkpoint checkpoint) {
        RecoveryLedger.Snapshot recovery = checkpoint.recovery();
        ProgressSnapshot progress = checkpoint.progress();
        StringBuilder encoded = new StringBuilder();
        encoded.append(CURRENT_VERSION).append('|')
                .append(checkpoint.missionId()).append('|')
                .append(checkpoint.planRevision()).append('|')
                .append(checkpoint.planFingerprint()).append('|')
                .append(checkpoint.intentFingerprint()).append('|')
                .append(checkpoint.contextFingerprint()).append('|')
                .append(checkpoint.completedSteps()).append('|')
                .append(checkpoint.elapsedMissionTicks()).append('|')
                .append(recovery.recoveriesConsumed()).append('|')
                .append(recovery.consecutiveNoProgressRecoveries()).append('|')
                .append(recovery.postconditionRecoveriesConsumed()).append('|')
                .append(progress.completedSteps()).append('|');
        if (progress.targetCount().isPresent()) {
            encoded.append(progress.targetCount().getAsInt());
        }
        encoded.append('|');
        if (progress.position().isPresent()) {
            Position position = progress.position().get();
            encoded.append(position.x()).append('|')
                    .append(position.y()).append('|')
                    .append(position.z());
        } else {
            encoded.append("||");
        }
        encoded.append('|').append(encodeAttempts(recovery.attemptsBySkill()))
                .append('|').append(checkpoint.replanAfterInterrupt() ? '1' : '0')
                .append('|').append(CursorCheckpointCodec.encode(checkpoint.cursor()));
        String payload = encoded.toString();
        return payload + '|' + HEX.formatHex(sha256(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static int decodeRuntimeKeyVersion(String key) {
        String suffix = key.substring(RUNTIME_BUDGET_PREFIX.length());
        if (!suffix.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("runtime_budget_key_noncanonical");
        }
        int version = requiredCanonicalNonNegative(suffix, "runtime_budget_key_version");
        if (!key.equals(RUNTIME_BUDGET_PREFIX + version)) {
            throw new IllegalArgumentException("runtime_budget_key_noncanonical");
        }
        return version;
    }

    private static UUID requiredUuid(String encoded) {
        try {
            UUID missionId = UUID.fromString(encoded);
            if (!missionId.toString().equals(encoded)) {
                throw new IllegalArgumentException("runtime_mission_id_noncanonical");
            }
            return missionId;
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException
                    && "runtime_mission_id_noncanonical".equals(exception.getMessage())) {
                throw exception;
            }
            throw new IllegalArgumentException("runtime_mission_id_invalid", exception);
        }
    }

    private static String requiredFingerprint(String value, String reason) {
        if (value == null || !value.matches(FINGERPRINT_PATTERN)) {
            throw new IllegalArgumentException(reason);
        }
        return value;
    }

    private static Optional<Position> decodePosition(String x, String y, String z) {
        return decodePosition(x, y, z, false);
    }

    private static Optional<Position> decodePosition(String x,
                                                     String y,
                                                     String z,
                                                     boolean canonical) {
        boolean hasX = !x.isEmpty();
        boolean hasY = !y.isEmpty();
        boolean hasZ = !z.isEmpty();
        if (!hasX && !hasY && !hasZ) {
            return Optional.empty();
        }
        if (!(hasX && hasY && hasZ)) {
            throw new IllegalArgumentException("runtime_snapshot_position_incomplete");
        }
        return Optional.of(new Position(
                canonical ? requiredCanonicalSignedInt(x, "runtime_snapshot_x")
                        : requiredSignedInt(x, "runtime_snapshot_x"),
                canonical ? requiredCanonicalSignedInt(y, "runtime_snapshot_y")
                        : requiredSignedInt(y, "runtime_snapshot_y"),
                canonical ? requiredCanonicalSignedInt(z, "runtime_snapshot_z")
                        : requiredSignedInt(z, "runtime_snapshot_z")));
    }

    private static OptionalInt optionalCompletedSteps(Map<String, String> source, String key) {
        if (!source.containsKey(key)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(requiredCompletedSteps(source.get(key), key));
    }

    private static OptionalInt optionalNonNegative(Map<String, String> source, String key) {
        if (!source.containsKey(key)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(requiredNonNegative(source.get(key), key));
    }

    private static OptionalInt optionalCanonicalCompletedSteps(Map<String, String> source,
                                                               String key) {
        if (!source.containsKey(key)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(requiredCanonicalCompletedSteps(source.get(key), key));
    }

    private static OptionalInt optionalCanonicalNonNegative(Map<String, String> source,
                                                            String key) {
        if (!source.containsKey(key)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(requiredCanonicalNonNegative(source.get(key), key));
    }

    private static OptionalInt optionalPayloadNonNegative(String value, String key) {
        return value.isEmpty() ? OptionalInt.empty()
                : OptionalInt.of(requiredNonNegative(value, key));
    }

    private static OptionalInt optionalPayloadCanonicalNonNegative(String value, String key) {
        return value.isEmpty() ? OptionalInt.empty()
                : OptionalInt.of(requiredCanonicalNonNegative(value, key));
    }

    private static int requiredCompletedSteps(String value, String key) {
        int parsed = requiredNonNegative(value, key);
        if (parsed > MAX_COMPLETED_STEPS) {
            throw new IllegalArgumentException(key + "_limit_exceeded");
        }
        return parsed;
    }

    private static int requiredCanonicalCompletedSteps(String value, String key) {
        int parsed = requiredCanonicalNonNegative(value, key);
        if (parsed > MAX_COMPLETED_STEPS) {
            throw new IllegalArgumentException(key + "_limit_exceeded");
        }
        return parsed;
    }

    private static int requiredCanonicalNonNegative(String value, String key) {
        int parsed = requiredNonNegative(value, key);
        if (!Integer.toString(parsed).equals(value)) {
            throw new IllegalArgumentException(key + "_noncanonical");
        }
        return parsed;
    }

    private static int requiredNonNegative(String value, String key) {
        int parsed = requiredSignedInt(value, key);
        if (parsed < 0) {
            throw new IllegalArgumentException(key + "_negative");
        }
        return parsed;
    }

    private static int requiredSignedInt(String value, String key) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException | NullPointerException exception) {
            throw new IllegalArgumentException(key + "_invalid", exception);
        }
    }

    private static int requiredCanonicalSignedInt(String value, String key) {
        int parsed = requiredSignedInt(value, key);
        if (!Integer.toString(parsed).equals(value)) {
            throw new IllegalArgumentException(key + "_noncanonical");
        }
        return parsed;
    }

    private static boolean requiredCanonicalBoolean(String value, String key) {
        if ("0".equals(value)) {
            return false;
        }
        if ("1".equals(value)) {
            return true;
        }
        throw new IllegalArgumentException(key + "_noncanonical");
    }

    private static String encodeAttempts(Map<String, Integer> attempts) {
        StringBuilder encoded = new StringBuilder();
        new TreeMap<>(attempts).forEach((fingerprint, count) -> {
            if (!encoded.isEmpty()) {
                encoded.append(';');
            }
            encoded.append(fingerprint).append('=').append(count);
        });
        return encoded.toString();
    }

    private static Map<String, Integer> decodeAttempts(String encoded, boolean canonical) {
        if (encoded.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> attempts = new LinkedHashMap<>();
        String[] entries = encoded.split(";", MAX_ATTEMPT_ENTRIES + 1);
        if (entries.length > MAX_ATTEMPT_ENTRIES) {
            throw new IllegalArgumentException("too_many_skill_attempt_snapshots");
        }
        String previousFingerprint = null;
        for (String entry : entries) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException("runtime_skill_attempts_invalid");
            }
            String fingerprint = entry.substring(0, separator);
            if (!fingerprint.matches(FINGERPRINT_PATTERN)) {
                throw new IllegalArgumentException("runtime_skill_attempt_fingerprint_invalid");
            }
            if (canonical && previousFingerprint != null
                    && previousFingerprint.compareTo(fingerprint) >= 0) {
                throw new IllegalArgumentException("runtime_skill_attempts_noncanonical");
            }
            int count = canonical
                    ? requiredCanonicalNonNegative(
                            entry.substring(separator + 1), "runtime_skill_attempt_count")
                    : requiredNonNegative(
                            entry.substring(separator + 1), "runtime_skill_attempt_count");
            if (count < 1 || attempts.putIfAbsent(fingerprint, count) != null) {
                throw new IllegalArgumentException("runtime_skill_attempt_count_invalid");
            }
            previousFingerprint = fingerprint;
        }
        return Map.copyOf(attempts);
    }

    public record Checkpoint(
            UUID missionId,
            int planRevision,
            String planFingerprint,
            String intentFingerprint,
            String contextFingerprint,
            int completedSteps,
            int elapsedMissionTicks,
            RecoveryLedger.Snapshot recovery,
            ProgressSnapshot progress,
            boolean replanAfterInterrupt,
            CursorCheckpoint cursor
    ) {
        /** V0/V1 read-only migration shape. */
        public Checkpoint(int completedSteps,
                          int elapsedMissionTicks,
                          RecoveryLedger.Snapshot recovery,
                          ProgressSnapshot progress) {
            this(null, -1, "", "", "", completedSteps, elapsedMissionTicks,
                    recovery, progress, false, null);
        }

        /** V2 read-only migration shape retained for source compatibility. */
        public Checkpoint(UUID missionId,
                          int planRevision,
                          String planFingerprint,
                          String intentFingerprint,
                          int completedSteps,
                          int elapsedMissionTicks,
                          RecoveryLedger.Snapshot recovery,
                          ProgressSnapshot progress) {
            this(missionId, planRevision, planFingerprint, intentFingerprint, "",
                    completedSteps, elapsedMissionTicks, recovery, progress, false, null);
        }

        public Checkpoint {
            boolean unbound = missionId == null
                    && planRevision == -1
                    && (planFingerprint == null || planFingerprint.isBlank())
                    && (intentFingerprint == null || intentFingerprint.isBlank())
                    && (contextFingerprint == null || contextFingerprint.isBlank())
                    && cursor == null;
            boolean legacyBound = missionId != null
                    && planRevision >= 0
                    && planFingerprint != null
                    && planFingerprint.matches(FINGERPRINT_PATTERN)
                    && intentFingerprint != null
                    && intentFingerprint.matches(FINGERPRINT_PATTERN)
                    && (contextFingerprint == null || contextFingerprint.isBlank())
                    && cursor == null;
            boolean currentBound = missionId != null
                    && planRevision >= 0
                    && planFingerprint != null
                    && planFingerprint.matches(FINGERPRINT_PATTERN)
                    && intentFingerprint != null
                    && intentFingerprint.matches(FINGERPRINT_PATTERN)
                    && contextFingerprint != null
                    && contextFingerprint.matches(FINGERPRINT_PATTERN)
                    && cursor != null;
            if (!unbound && !legacyBound && !currentBound) {
                throw new IllegalArgumentException("mission_checkpoint_plan_binding_invalid");
            }
            planFingerprint = unbound ? "" : planFingerprint;
            intentFingerprint = unbound ? "" : intentFingerprint;
            contextFingerprint = currentBound ? contextFingerprint : "";
            if (completedSteps < 0 || completedSteps > MAX_COMPLETED_STEPS
                    || elapsedMissionTicks < 0) {
                throw new IllegalArgumentException("invalid_mission_checkpoint_counter");
            }
            if (currentBound && (recovery == null || progress == null)) {
                throw new IllegalArgumentException("mission_checkpoint_runtime_state_missing");
            }
            recovery = recovery == null ? RecoveryLedger.Snapshot.empty() : recovery;
            progress = progress == null
                    ? ProgressSnapshot.legacy(completedSteps) : progress;
            if (progress.completedSteps() > completedSteps) {
                throw new IllegalArgumentException("snapshot_steps_exceed_completed_steps");
            }
            if (currentBound) {
                if (!missionId.equals(cursor.missionId())) {
                    throw new IllegalArgumentException("mission_cursor_mission_id_mismatch");
                }
                if (planRevision != cursor.planRevision()) {
                    throw new IllegalArgumentException("mission_cursor_plan_revision_mismatch");
                }
                if (!planFingerprint.equals(cursor.planFingerprint())) {
                    throw new IllegalArgumentException("mission_cursor_plan_fingerprint_mismatch");
                }
                if (cursor.tick() != elapsedMissionTicks) {
                    throw new IllegalArgumentException("mission_cursor_tick_mismatch");
                }
            }
        }

        public boolean bound() {
            return missionId != null;
        }

        public boolean current() {
            return cursor != null;
        }
    }

    public record ProgressSnapshot(
            int completedSteps,
            OptionalInt targetCount,
            Optional<Position> position
    ) {
        public ProgressSnapshot {
            if (completedSteps < 0 || completedSteps > MAX_COMPLETED_STEPS) {
                throw new IllegalArgumentException("snapshot_steps_invalid");
            }
            targetCount = targetCount == null ? OptionalInt.empty() : targetCount;
            if (targetCount.isPresent() && targetCount.getAsInt() < 0) {
                throw new IllegalArgumentException("snapshot_target_count_negative");
            }
            position = position == null ? Optional.empty() : position;
        }

        public static ProgressSnapshot legacy(int completedSteps) {
            return new ProgressSnapshot(completedSteps, OptionalInt.empty(), Optional.empty());
        }
    }

    public record Position(int x, int y, int z) {
    }

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha256_unavailable", exception);
        }
    }

    public enum Status {
        OK,
        CORRUPT,
        UNSUPPORTED_VERSION
    }

    public record DecodeResult(Status status, int version, Checkpoint checkpoint, String reason) {
        public DecodeResult {
            if (status == null || reason == null) {
                throw new IllegalArgumentException("invalid_checkpoint_decode_result");
            }
            if ((status == Status.OK) != (checkpoint != null)) {
                throw new IllegalArgumentException("checkpoint_decode_result_mismatch");
            }
        }

        public boolean valid() {
            return status == Status.OK;
        }

        private static DecodeResult ok(int version, Checkpoint checkpoint) {
            return new DecodeResult(Status.OK, version, checkpoint, "");
        }

        private static DecodeResult corrupt(int version, String reason) {
            return new DecodeResult(Status.CORRUPT, version, null,
                    reason == null || reason.isBlank() ? "checkpoint_corrupt" : reason);
        }

        private static DecodeResult unsupported(int version) {
            return unsupported(version, "checkpoint_version_unsupported");
        }

        private static DecodeResult unsupported(int version, String reason) {
            return new DecodeResult(Status.UNSUPPORTED_VERSION, version, null, reason);
        }
    }
}
