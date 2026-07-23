package io.github.greytaiwolf.fakeaiplayer.persist;

import io.github.greytaiwolf.fakeaiplayer.mission.CursorCheckpoint;
import io.github.greytaiwolf.fakeaiplayer.mission.RecoveryLedger;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionCheckpointCodecTest {
    private static final UUID MISSION_ID =
            UUID.fromString("12345678-1234-5678-9abc-def012345678");
    private static final String PLAN_FINGERPRINT = "a".repeat(64);
    private static final String INTENT_FINGERPRINT = "b".repeat(64);
    private static final String CONTEXT_FINGERPRINT = "c".repeat(64);
    private static final String FIRST_FINGERPRINT = "1".repeat(64);
    private static final String SECOND_FINGERPRINT = "2".repeat(64);

    @Test
    void currentV3RoundTripsAtomicContextCursorBudgetsAndSignedPosition() {
        RecoveryLedger.Snapshot recovery = new RecoveryLedger.Snapshot(
                Map.of(FIRST_FINGERPRINT, 2, SECOND_FINGERPRINT, 1),
                7,
                2,
                3);
        MissionCheckpointCodec.Checkpoint original = new MissionCheckpointCodec.Checkpoint(
                MISSION_ID,
                4,
                PLAN_FINGERPRINT,
                INTENT_FINGERPRINT,
                CONTEXT_FINGERPRINT,
                9,
                321,
                recovery,
                new MissionCheckpointCodec.ProgressSnapshot(
                        8,
                        OptionalInt.of(4),
                        Optional.of(new MissionCheckpointCodec.Position(-12, -48, 31))),
                true,
                cursor(MISSION_ID, 4, PLAN_FINGERPRINT, 321));

        Map<String, String> encoded = MissionCheckpointCodec.encode(
                Map.of(
                        "origin", "1,2,3",
                        "containers", "4,5,6",
                        MissionCheckpointCodec.RUNTIME_BUDGET_V1, "stale-v1",
                        MissionCheckpointCodec.RUNTIME_BUDGET_V2, "stale-v2"),
                original);
        MissionCheckpointCodec.DecodeResult decoded = MissionCheckpointCodec.decode(encoded);

        assertTrue(decoded.valid());
        assertEquals(3, decoded.version());
        assertEquals(original, decoded.checkpoint());
        assertTrue(decoded.checkpoint().current());
        assertTrue(decoded.checkpoint().replanAfterInterrupt());
        assertEquals("1,2,3", encoded.get("origin"));
        assertEquals("4,5,6", encoded.get("containers"));
        assertEquals("9", encoded.get(MissionCheckpointCodec.LEGACY_REVISION));
        assertEquals("321", encoded.get(MissionCheckpointCodec.ELAPSED_MISSION_TICKS));
        assertFalse(encoded.containsKey(MissionCheckpointCodec.RUNTIME_BUDGET_V1));
        assertFalse(encoded.containsKey(MissionCheckpointCodec.RUNTIME_BUDGET_V2));
        String payload = encoded.get(MissionCheckpointCodec.RUNTIME_BUDGET_V3);
        assertTrue(payload.startsWith("3|" + MISSION_ID + "|4|" + PLAN_FINGERPRINT
                + "|" + INTENT_FINGERPRINT + "|" + CONTEXT_FINGERPRINT
                + "|9|321|7|2|3|8|4|-12|-48|31|"));
        assertTrue(payload.contains(
                FIRST_FINGERPRINT + "=2;" + SECOND_FINGERPRINT + "=1|1|cpc."));
    }

    @Test
    void v0AndV1RemainReadOnlyUnboundMigrationFormats() {
        MissionCheckpointCodec.DecodeResult v0 = MissionCheckpointCodec.decode(Map.of(
                "origin", "10,64,-3",
                MissionCheckpointCodec.ELAPSED_MISSION_TICKS, "120",
                MissionCheckpointCodec.LEGACY_REVISION, "5"));
        String v1Payload = "1|2|10|1|1|0|2|||||" + FIRST_FINGERPRINT + "=2";
        MissionCheckpointCodec.DecodeResult v1 = MissionCheckpointCodec.decode(Map.of(
                MissionCheckpointCodec.LEGACY_REVISION, "2",
                MissionCheckpointCodec.ELAPSED_MISSION_TICKS, "10",
                MissionCheckpointCodec.RUNTIME_BUDGET_V1, v1Payload));

        assertTrue(v0.valid());
        assertEquals(0, v0.version());
        assertFalse(v0.checkpoint().bound());
        assertEquals(5, v0.checkpoint().completedSteps());
        assertTrue(v1.valid());
        assertEquals(1, v1.version());
        assertFalse(v1.checkpoint().bound());
        assertEquals(Map.of(FIRST_FINGERPRINT, 2),
                v1.checkpoint().recovery().attemptsBySkill());
        assertEquals("mission_checkpoint_plan_binding_missing", assertThrows(
                IllegalArgumentException.class,
                () -> MissionCheckpointCodec.encode(Map.of(), v1.checkpoint())).getMessage());
    }

    @Test
    void v2DecodesForMigrationButCannotBeWrittenBackWithoutV3Binding() {
        String payload = v2Payload(MISSION_ID.toString(), "4", PLAN_FINGERPRINT,
                INTENT_FINGERPRINT, 2, 10, 2);
        MissionCheckpointCodec.DecodeResult decoded = MissionCheckpointCodec.decode(Map.of(
                MissionCheckpointCodec.LEGACY_REVISION, "2",
                MissionCheckpointCodec.ELAPSED_MISSION_TICKS, "10",
                MissionCheckpointCodec.RUNTIME_BUDGET_V2, payload));

        assertTrue(decoded.valid());
        assertEquals(2, decoded.version());
        assertTrue(decoded.checkpoint().bound());
        assertFalse(decoded.checkpoint().current());
        assertEquals("", decoded.checkpoint().contextFingerprint());
        assertNull(decoded.checkpoint().cursor());
        assertEquals("mission_checkpoint_v3_binding_missing", assertThrows(
                IllegalArgumentException.class,
                () -> MissionCheckpointCodec.encode(Map.of(), decoded.checkpoint())).getMessage());
    }

    @Test
    void fullV3ChecksumRejectsCorruptionBeforeFallingBackToAliases() {
        Map<String, String> encoded = MissionCheckpointCodec.encode(Map.of(), currentCheckpoint());
        String[] parts = encoded.get(MissionCheckpointCodec.RUNTIME_BUDGET_V3)
                .split("\\|", -1);
        String cursor = parts[18];
        parts[18] = cursor.substring(0, cursor.length() - 1)
                + (cursor.endsWith("0") ? "1" : "0");
        Map<String, String> corrupt = Map.of(
                MissionCheckpointCodec.LEGACY_REVISION, "0",
                MissionCheckpointCodec.ELAPSED_MISSION_TICKS, "0",
                MissionCheckpointCodec.RUNTIME_BUDGET_V3, String.join("|", parts));

        MissionCheckpointCodec.DecodeResult decoded = MissionCheckpointCodec.decode(corrupt);

        assertEquals(MissionCheckpointCodec.Status.CORRUPT, decoded.status());
        assertEquals("runtime_budget_checksum_mismatch", decoded.reason());
        assertNull(decoded.checkpoint());
    }

    @Test
    void validLookingCounterMutationCannotRefundARecoveryBudget() {
        RecoveryLedger.Snapshot recovery = new RecoveryLedger.Snapshot(Map.of(), 2, 1, 1);
        MissionCheckpointCodec.Checkpoint checkpoint = new MissionCheckpointCodec.Checkpoint(
                MISSION_ID, 0, PLAN_FINGERPRINT, INTENT_FINGERPRINT, CONTEXT_FINGERPRINT,
                0, 0, recovery, MissionCheckpointCodec.ProgressSnapshot.legacy(0),
                false,
                cursor(MISSION_ID, 0, PLAN_FINGERPRINT, 0));
        Map<String, String> encoded = MissionCheckpointCodec.encode(Map.of(), checkpoint);
        String[] parts = encoded.get(MissionCheckpointCodec.RUNTIME_BUDGET_V3)
                .split("\\|", -1);
        parts[8] = "1";

        MissionCheckpointCodec.DecodeResult decoded = MissionCheckpointCodec.decode(Map.of(
                MissionCheckpointCodec.RUNTIME_BUDGET_V3, String.join("|", parts)));

        assertEquals(MissionCheckpointCodec.Status.CORRUPT, decoded.status());
        assertEquals("runtime_budget_checksum_mismatch", decoded.reason());
    }

    @Test
    void v3CursorMustMatchMissionRevisionFingerprintAndTick() {
        List<CursorCheckpoint> mismatches = List.of(
                cursor(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        4, PLAN_FINGERPRINT, 321),
                cursor(MISSION_ID, 5, PLAN_FINGERPRINT, 321),
                cursor(MISSION_ID, 4, "d".repeat(64), 321),
                cursor(MISSION_ID, 4, PLAN_FINGERPRINT, 322));
        List<String> expectedReasons = List.of(
                "mission_cursor_mission_id_mismatch",
                "mission_cursor_plan_revision_mismatch",
                "mission_cursor_plan_fingerprint_mismatch",
                "mission_cursor_tick_mismatch");

        for (int index = 0; index < mismatches.size(); index++) {
            String payload = v3Payload(
                    MISSION_ID.toString(),
                    "4",
                    PLAN_FINGERPRINT,
                    INTENT_FINGERPRINT,
                    CONTEXT_FINGERPRINT,
                    1,
                    321,
                    1,
                    mismatches.get(index));
            MissionCheckpointCodec.DecodeResult decoded = MissionCheckpointCodec.decode(Map.of(
                    MissionCheckpointCodec.RUNTIME_BUDGET_V3, payload));
            assertEquals(MissionCheckpointCodec.Status.CORRUPT,
                    decoded.status(), expectedReasons.get(index));
            assertEquals(expectedReasons.get(index), decoded.reason());
        }
    }

    @Test
    void malformedV1V2AndV3BindingsFailClosed() {
        String validV1Prefix = "1|2|10|0|0|0|2|||||";
        for (String malformed : List.of(
                "",
                "1|2|10",
                "1|2|10|not-a-number|0|0|2|||||",
                validV1Prefix + FIRST_FINGERPRINT + "=0",
                validV1Prefix + "not-a-fingerprint=2",
                validV1Prefix + FIRST_FINGERPRINT + "=1;" + FIRST_FINGERPRINT + "=2")) {
            assertEquals(MissionCheckpointCodec.Status.CORRUPT,
                    MissionCheckpointCodec.decode(Map.of(
                            MissionCheckpointCodec.RUNTIME_BUDGET_V1, malformed)).status(),
                    malformed);
        }

        Map<String, String> malformedV2 = Map.of(
                "bad_uuid", v2Payload("not-a-uuid", "4", PLAN_FINGERPRINT,
                        INTENT_FINGERPRINT, 2, 10, 2),
                "negative_revision", v2Payload(MISSION_ID.toString(), "-1", PLAN_FINGERPRINT,
                        INTENT_FINGERPRINT, 2, 10, 2),
                "bad_fingerprint", v2Payload(MISSION_ID.toString(), "4", "abc",
                        INTENT_FINGERPRINT, 2, 10, 2),
                "upper_fingerprint", v2Payload(MISSION_ID.toString(), "4", "A".repeat(64),
                        INTENT_FINGERPRINT, 2, 10, 2));
        for (Map.Entry<String, String> entry : malformedV2.entrySet()) {
            assertEquals(MissionCheckpointCodec.Status.CORRUPT,
                    MissionCheckpointCodec.decode(Map.of(
                            MissionCheckpointCodec.RUNTIME_BUDGET_V2, entry.getValue())).status(),
                    entry.getKey());
        }

        String badContext = v3Payload(MISSION_ID.toString(), "4", PLAN_FINGERPRINT,
                INTENT_FINGERPRINT, "short", 1, 321, 1,
                cursor(MISSION_ID, 4, PLAN_FINGERPRINT, 321));
        MissionCheckpointCodec.DecodeResult decoded = MissionCheckpointCodec.decode(Map.of(
                MissionCheckpointCodec.RUNTIME_BUDGET_V3, badContext));
        assertEquals(MissionCheckpointCodec.Status.CORRUPT, decoded.status());
        assertEquals("runtime_context_fingerprint_invalid", decoded.reason());
    }

    @Test
    void aliasesMustAgreeAndProgressCannotLeadCompletion() {
        String payload = v2Payload(MISSION_ID.toString(), "4", PLAN_FINGERPRINT,
                INTENT_FINGERPRINT, 2, 10, 2);
        MissionCheckpointCodec.DecodeResult conflictingRevision = MissionCheckpointCodec.decode(Map.of(
                MissionCheckpointCodec.LEGACY_REVISION, "3",
                MissionCheckpointCodec.RUNTIME_BUDGET_V2, payload));
        MissionCheckpointCodec.DecodeResult conflictingElapsed = MissionCheckpointCodec.decode(Map.of(
                MissionCheckpointCodec.ELAPSED_MISSION_TICKS, "11",
                MissionCheckpointCodec.RUNTIME_BUDGET_V2, payload));
        MissionCheckpointCodec.DecodeResult futureProgress = MissionCheckpointCodec.decode(Map.of(
                MissionCheckpointCodec.RUNTIME_BUDGET_V2,
                v2Payload(MISSION_ID.toString(), "4", PLAN_FINGERPRINT,
                        INTENT_FINGERPRINT, 2, 10, 3)));

        assertEquals("runtime_revision_mismatch", conflictingRevision.reason());
        assertEquals("runtime_elapsed_ticks_mismatch", conflictingElapsed.reason());
        assertEquals("snapshot_steps_exceed_completed_steps", futureProgress.reason());
        assertEquals(MissionCheckpointCodec.Status.CORRUPT, futureProgress.status());
    }

    @Test
    void checksummedV3RejectsEveryNoncanonicalIntegerAndCoordinate() {
        RecoveryLedger.Snapshot recovery = new RecoveryLedger.Snapshot(
                Map.of(FIRST_FINGERPRINT, 2, SECOND_FINGERPRINT, 1), 7, 2, 3);
        MissionCheckpointCodec.Checkpoint checkpoint = new MissionCheckpointCodec.Checkpoint(
                MISSION_ID, 4, PLAN_FINGERPRINT, INTENT_FINGERPRINT, CONTEXT_FINGERPRINT,
                9, 321, recovery,
                new MissionCheckpointCodec.ProgressSnapshot(
                        8, OptionalInt.of(4),
                        Optional.of(new MissionCheckpointCodec.Position(-12, -48, 31))),
                false,
                cursor(MISSION_ID, 4, PLAN_FINGERPRINT, 321));
        Map<String, String> encoded = MissionCheckpointCodec.encode(Map.of(), checkpoint);
        String original = encoded.get(MissionCheckpointCodec.RUNTIME_BUDGET_V3);
        Map<Integer, String> mutations = Map.ofEntries(
                Map.entry(2, "04"),
                Map.entry(6, "09"),
                Map.entry(7, "+321"),
                Map.entry(8, "07"),
                Map.entry(9, "+2"),
                Map.entry(10, "03"),
                Map.entry(11, "+8"),
                Map.entry(12, "04"),
                Map.entry(13, "-012"),
                Map.entry(14, "-048"),
                Map.entry(15, "+31"));
        Map<Integer, String> expectedReasons = Map.ofEntries(
                Map.entry(2, "runtime_plan_revision_noncanonical"),
                Map.entry(6, "runtime_completed_steps_noncanonical"),
                Map.entry(7, "runtime_elapsed_ticks_noncanonical"),
                Map.entry(8, "runtime_recovery_total_noncanonical"),
                Map.entry(9, "runtime_recovery_no_progress_noncanonical"),
                Map.entry(10, "runtime_recovery_postcondition_noncanonical"),
                Map.entry(11, "runtime_snapshot_steps_noncanonical"),
                Map.entry(12, "runtime_snapshot_target_count_noncanonical"),
                Map.entry(13, "runtime_snapshot_x_noncanonical"),
                Map.entry(14, "runtime_snapshot_y_noncanonical"),
                Map.entry(15, "runtime_snapshot_z_noncanonical"));

        for (Map.Entry<Integer, String> mutation : mutations.entrySet()) {
            String[] parts = original.split("\\|", -1);
            parts[mutation.getKey()] = mutation.getValue();
            MissionCheckpointCodec.DecodeResult decoded = MissionCheckpointCodec.decode(Map.of(
                    MissionCheckpointCodec.LEGACY_REVISION, "9",
                    MissionCheckpointCodec.ELAPSED_MISSION_TICKS, "321",
                    MissionCheckpointCodec.RUNTIME_BUDGET_V3, rechecksum(parts)));
            assertEquals(MissionCheckpointCodec.Status.CORRUPT,
                    decoded.status(), mutation.toString());
            assertEquals(expectedReasons.get(mutation.getKey()),
                    decoded.reason(), mutation.toString());
        }

        MissionCheckpointCodec.DecodeResult leadingAlias = MissionCheckpointCodec.decode(Map.of(
                MissionCheckpointCodec.LEGACY_REVISION, "09",
                MissionCheckpointCodec.ELAPSED_MISSION_TICKS, "321",
                MissionCheckpointCodec.RUNTIME_BUDGET_V3, original));
        MissionCheckpointCodec.DecodeResult signedAlias = MissionCheckpointCodec.decode(Map.of(
                MissionCheckpointCodec.LEGACY_REVISION, "9",
                MissionCheckpointCodec.ELAPSED_MISSION_TICKS, "+321",
                MissionCheckpointCodec.RUNTIME_BUDGET_V3, original));
        assertEquals("revision_noncanonical", leadingAlias.reason());
        assertEquals("elapsed_mission_ticks_noncanonical", signedAlias.reason());
    }

    @Test
    void checksummedV3RequiresSortedAttemptsCanonicalCountsAndBinaryBoolean() {
        RecoveryLedger.Snapshot recovery = new RecoveryLedger.Snapshot(
                Map.of(FIRST_FINGERPRINT, 2, SECOND_FINGERPRINT, 1), 0, 0, 0);
        MissionCheckpointCodec.Checkpoint checkpoint = new MissionCheckpointCodec.Checkpoint(
                MISSION_ID, 0, PLAN_FINGERPRINT, INTENT_FINGERPRINT, CONTEXT_FINGERPRINT,
                0, 0, recovery, MissionCheckpointCodec.ProgressSnapshot.legacy(0),
                true,
                cursor(MISSION_ID, 0, PLAN_FINGERPRINT, 0));
        String original = MissionCheckpointCodec.encode(Map.of(), checkpoint)
                .get(MissionCheckpointCodec.RUNTIME_BUDGET_V3);

        String[] unsorted = original.split("\\|", -1);
        unsorted[16] = SECOND_FINGERPRINT + "=1;" + FIRST_FINGERPRINT + "=2";
        MissionCheckpointCodec.DecodeResult unsortedResult = MissionCheckpointCodec.decode(Map.of(
                MissionCheckpointCodec.RUNTIME_BUDGET_V3, rechecksum(unsorted)));
        assertEquals(MissionCheckpointCodec.Status.CORRUPT, unsortedResult.status());
        assertEquals("runtime_skill_attempts_noncanonical", unsortedResult.reason());

        String[] paddedCount = original.split("\\|", -1);
        paddedCount[16] = FIRST_FINGERPRINT + "=02;" + SECOND_FINGERPRINT + "=1";
        MissionCheckpointCodec.DecodeResult paddedCountResult = MissionCheckpointCodec.decode(Map.of(
                MissionCheckpointCodec.RUNTIME_BUDGET_V3, rechecksum(paddedCount)));
        assertEquals(MissionCheckpointCodec.Status.CORRUPT, paddedCountResult.status());
        assertEquals("runtime_skill_attempt_count_noncanonical", paddedCountResult.reason());

        for (String invalid : List.of("", "true", "false", "01", "+1", "2", "-1")) {
            String[] booleanParts = original.split("\\|", -1);
            booleanParts[17] = invalid;
            MissionCheckpointCodec.DecodeResult decoded = MissionCheckpointCodec.decode(Map.of(
                    MissionCheckpointCodec.RUNTIME_BUDGET_V3, rechecksum(booleanParts)));
            assertEquals(MissionCheckpointCodec.Status.CORRUPT,
                    decoded.status(), invalid);
            assertEquals("runtime_replan_after_interrupt_noncanonical",
                    decoded.reason(), invalid);
        }
    }

    @Test
    void v2MigrationStillAcceptsItsHistoricalNoncanonicalRepresentations() {
        String payload = v2Payload(MISSION_ID.toString(), "+4", PLAN_FINGERPRINT,
                INTENT_FINGERPRINT, 0, 0, 0)
                + SECOND_FINGERPRINT + "=01;" + FIRST_FINGERPRINT + "=02";

        MissionCheckpointCodec.DecodeResult decoded = MissionCheckpointCodec.decode(Map.of(
                MissionCheckpointCodec.RUNTIME_BUDGET_V2, payload));

        assertTrue(decoded.valid());
        assertEquals(2, decoded.version());
        assertEquals(Map.of(FIRST_FINGERPRINT, 2, SECOND_FINGERPRINT, 1),
                decoded.checkpoint().recovery().attemptsBySkill());
        assertFalse(decoded.checkpoint().replanAfterInterrupt());
    }

    @Test
    void runtimeKeysAreCanonicalAndFutureVersionsAreUnsupported() {
        for (String noncanonical : List.of(
                "runtime_budget_v03",
                "runtime_budget_v+3",
                "runtime_budget_v3x",
                "runtime_budget_v0")) {
            MissionCheckpointCodec.DecodeResult decoded = MissionCheckpointCodec.decode(Map.of(
                    noncanonical, "3|anything"));
            assertEquals(MissionCheckpointCodec.Status.CORRUPT,
                    decoded.status(), noncanonical);
            assertEquals("runtime_budget_key_noncanonical", decoded.reason(), noncanonical);
        }

        int future = MissionCheckpointCodec.CURRENT_VERSION + 1;
        MissionCheckpointCodec.DecodeResult futurePayload = MissionCheckpointCodec.decode(Map.of(
                MissionCheckpointCodec.RUNTIME_BUDGET_V3,
                future + "|future|shape|may|change"));
        MissionCheckpointCodec.DecodeResult futureKey = MissionCheckpointCodec.decode(Map.of(
                "runtime_budget_v" + future,
                future + "|anything"));
        assertEquals(MissionCheckpointCodec.Status.UNSUPPORTED_VERSION, futurePayload.status());
        assertEquals(future, futurePayload.version());
        assertEquals(MissionCheckpointCodec.Status.UNSUPPORTED_VERSION, futureKey.status());
        assertEquals(future, futureKey.version());
    }

    @Test
    void multipleRuntimeKeysAreCorruptAndEncoderRejectsUnknownFutureKey() {
        MissionCheckpointCodec.DecodeResult decoded = MissionCheckpointCodec.decode(Map.of(
                MissionCheckpointCodec.RUNTIME_BUDGET_V2,
                v2Payload(MISSION_ID.toString(), "0", PLAN_FINGERPRINT,
                        INTENT_FINGERPRINT, 0, 0, 0),
                MissionCheckpointCodec.RUNTIME_BUDGET_V3, "3|future"));
        assertEquals(MissionCheckpointCodec.Status.CORRUPT, decoded.status());
        assertEquals("multiple_runtime_budget_payloads", decoded.reason());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> MissionCheckpointCodec.encode(
                        Map.of("runtime_budget_v4", "4|future"), currentCheckpoint()));
        assertEquals("unknown_runtime_budget_key", failure.getMessage());
    }

    @Test
    void completedAndSnapshotCountersAreBoundedAcrossEveryGeneration() {
        int tooMany = MissionCheckpointCodec.MAX_COMPLETED_STEPS + 1;
        assertEquals(MissionCheckpointCodec.Status.CORRUPT,
                MissionCheckpointCodec.decode(Map.of(
                        MissionCheckpointCodec.LEGACY_REVISION, Integer.toString(tooMany))).status());
        assertEquals(MissionCheckpointCodec.Status.CORRUPT,
                MissionCheckpointCodec.decode(Map.of(
                        MissionCheckpointCodec.RUNTIME_BUDGET_V1,
                        "1|" + tooMany + "|0|0|0|0|0|||||")).status());
        assertEquals(MissionCheckpointCodec.Status.CORRUPT,
                MissionCheckpointCodec.decode(Map.of(
                        MissionCheckpointCodec.RUNTIME_BUDGET_V2,
                        v2Payload(MISSION_ID.toString(), "0", PLAN_FINGERPRINT,
                                INTENT_FINGERPRINT, tooMany, 0, 0))).status());
        assertEquals(MissionCheckpointCodec.Status.CORRUPT,
                MissionCheckpointCodec.decode(Map.of(
                        MissionCheckpointCodec.RUNTIME_BUDGET_V3,
                        v3Payload(MISSION_ID.toString(), "0", PLAN_FINGERPRINT,
                                INTENT_FINGERPRINT, CONTEXT_FINGERPRINT,
                                1, 0, tooMany,
                                cursor(MISSION_ID, 0, PLAN_FINGERPRINT, 0)))).status());

        assertThrows(IllegalArgumentException.class, () -> new MissionCheckpointCodec.Checkpoint(
                tooMany,
                0,
                RecoveryLedger.Snapshot.empty(),
                MissionCheckpointCodec.ProgressSnapshot.legacy(0)));
        assertThrows(IllegalArgumentException.class,
                () -> MissionCheckpointCodec.ProgressSnapshot.legacy(tooMany));
    }

    @Test
    void attemptSnapshotEntryLimitRejectsThe257thEntry() {
        StringBuilder attempts = new StringBuilder();
        for (int index = 1; index <= 257; index++) {
            if (!attempts.isEmpty()) {
                attempts.append(';');
            }
            attempts.append("%064x".formatted(index)).append("=1");
        }
        String payload = v2Payload(MISSION_ID.toString(), "0", PLAN_FINGERPRINT,
                INTENT_FINGERPRINT, 0, 0, 0) + attempts;

        MissionCheckpointCodec.DecodeResult decoded = MissionCheckpointCodec.decode(Map.of(
                MissionCheckpointCodec.RUNTIME_BUDGET_V2, payload));

        assertEquals(MissionCheckpointCodec.Status.CORRUPT, decoded.status());
        assertEquals("too_many_skill_attempt_snapshots", decoded.reason());
    }

    @Test
    void checkpointRequiresCompleteCanonicalV3Binding() {
        assertThrows(IllegalArgumentException.class, () -> new MissionCheckpointCodec.Checkpoint(
                MISSION_ID, 0, PLAN_FINGERPRINT, INTENT_FINGERPRINT, "short",
                0, 0, RecoveryLedger.Snapshot.empty(),
                MissionCheckpointCodec.ProgressSnapshot.legacy(0),
                false,
                cursor(MISSION_ID, 0, PLAN_FINGERPRINT, 0)));
        assertThrows(IllegalArgumentException.class, () -> new MissionCheckpointCodec.Checkpoint(
                MISSION_ID, 0, "short", INTENT_FINGERPRINT, CONTEXT_FINGERPRINT,
                0, 0, RecoveryLedger.Snapshot.empty(),
                MissionCheckpointCodec.ProgressSnapshot.legacy(0),
                false,
                cursor(MISSION_ID, 0, PLAN_FINGERPRINT, 0)));
        assertThrows(IllegalArgumentException.class, () -> new MissionCheckpointCodec.Checkpoint(
                MISSION_ID, 0, PLAN_FINGERPRINT, INTENT_FINGERPRINT, CONTEXT_FINGERPRINT,
                0, 1, RecoveryLedger.Snapshot.empty(),
                MissionCheckpointCodec.ProgressSnapshot.legacy(0),
                false,
                cursor(MISSION_ID, 0, PLAN_FINGERPRINT, 0)));
        assertEquals("mission_checkpoint_runtime_state_missing", assertThrows(
                IllegalArgumentException.class,
                () -> new MissionCheckpointCodec.Checkpoint(
                        MISSION_ID, 0, PLAN_FINGERPRINT, INTENT_FINGERPRINT,
                        CONTEXT_FINGERPRINT, 0, 0, null,
                        MissionCheckpointCodec.ProgressSnapshot.legacy(0),
                        false,
                        cursor(MISSION_ID, 0, PLAN_FINGERPRINT, 0))).getMessage());
        assertEquals("mission_checkpoint_runtime_state_missing", assertThrows(
                IllegalArgumentException.class,
                () -> new MissionCheckpointCodec.Checkpoint(
                        MISSION_ID, 0, PLAN_FINGERPRINT, INTENT_FINGERPRINT,
                        CONTEXT_FINGERPRINT, 0, 0, RecoveryLedger.Snapshot.empty(), null,
                        false,
                        cursor(MISSION_ID, 0, PLAN_FINGERPRINT, 0))).getMessage());
    }

    private static MissionCheckpointCodec.Checkpoint currentCheckpoint() {
        return new MissionCheckpointCodec.Checkpoint(
                MISSION_ID,
                0,
                PLAN_FINGERPRINT,
                INTENT_FINGERPRINT,
                CONTEXT_FINGERPRINT,
                0,
                0,
                RecoveryLedger.Snapshot.empty(),
                MissionCheckpointCodec.ProgressSnapshot.legacy(0),
                false,
                cursor(MISSION_ID, 0, PLAN_FINGERPRINT, 0));
    }

    private static CursorCheckpoint cursor(UUID missionId,
                                           int planRevision,
                                           String planFingerprint,
                                           long tick) {
        return new CursorCheckpoint(
                CursorCheckpoint.CURRENT_VERSION,
                missionId,
                planRevision,
                planFingerprint,
                tick,
                Map.of("root", new CursorCheckpoint.NodeState(
                        CursorCheckpoint.NodePhase.ACTIVE,
                        0,
                        0,
                        -1,
                        -1,
                        false,
                        null,
                        null)),
                Map.of("step.one", 1),
                Set.of(),
                Set.of());
    }

    private static String v2Payload(String missionId,
                                    String planRevision,
                                    String planFingerprint,
                                    String intentFingerprint,
                                    int completedSteps,
                                    int elapsedTicks,
                                    int snapshotSteps) {
        return "2|" + missionId + '|' + planRevision + '|' + planFingerprint
                + '|' + intentFingerprint
                + '|' + completedSteps + '|' + elapsedTicks
                + "|0|0|0|" + snapshotSteps + "|||||";
    }

    private static String v3Payload(String missionId,
                                    String planRevision,
                                    String planFingerprint,
                                    String intentFingerprint,
                                    String contextFingerprint,
                                    int completedSteps,
                                    int elapsedTicks,
                                    int snapshotSteps,
                                    CursorCheckpoint cursor) {
        String payload = "3|" + missionId + '|' + planRevision + '|' + planFingerprint
                + '|' + intentFingerprint + '|' + contextFingerprint
                + '|' + completedSteps + '|' + elapsedTicks
                + "|0|0|0|" + snapshotSteps + "||||||0|"
                + CursorCheckpointCodec.encode(cursor);
        return payload + '|' + checksum(payload);
    }

    private static String rechecksum(String[] parts) {
        String payload = String.join("|", Arrays.asList(parts).subList(0, 19));
        parts[19] = checksum(payload);
        return String.join("|", parts);
    }

    private static String checksum(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
