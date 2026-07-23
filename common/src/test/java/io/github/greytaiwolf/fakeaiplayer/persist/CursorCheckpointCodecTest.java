package io.github.greytaiwolf.fakeaiplayer.persist;

import io.github.greytaiwolf.fakeaiplayer.mission.CursorCheckpoint;
import io.github.greytaiwolf.fakeaiplayer.mission.SkillOutcome;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorCheckpointCodecTest {
    private static final UUID MISSION_ID =
            UUID.fromString("12345678-1234-5678-9abc-def012345678");
    private static final String PLAN_FINGERPRINT = "a".repeat(64);
    private static final Base64.Encoder BASE64_ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void roundTripsEveryCursorFieldIncludingTypedFailureEvidence() {
        SkillOutcome failure = new SkillOutcome(
                SkillOutcome.Status.BLOCKED,
                SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE,
                "需要更多 iron | ore",
                7,
                Map.of("z-last", "值", "a-first", "one"));
        Map<String, CursorCheckpoint.NodeState> nodes = new LinkedHashMap<>();
        nodes.put("root/1", new CursorCheckpoint.NodeState(
                CursorCheckpoint.NodePhase.FAILED,
                0,
                2,
                -1,
                -1,
                false,
                "root/1",
                failure));
        nodes.put("root", new CursorCheckpoint.NodeState(
                CursorCheckpoint.NodePhase.ACTIVE,
                1,
                0,
                100,
                23,
                true,
                null,
                null));
        Map<String, Integer> activations = new LinkedHashMap<>();
        activations.put("step.z", 3);
        activations.put("step.a", 1);
        Set<String> reached = new LinkedHashSet<>();
        reached.add("checkpoint.z");
        reached.add("checkpoint.a");
        Set<String> waiting = new LinkedHashSet<>();
        waiting.add("event.z");
        waiting.add("event.a");
        CursorCheckpoint original = new CursorCheckpoint(
                CursorCheckpoint.CURRENT_VERSION,
                MISSION_ID,
                6,
                PLAN_FINGERPRINT,
                123,
                nodes,
                activations,
                reached,
                waiting);

        String encoded = CursorCheckpointCodec.encode(original);
        CursorCheckpointCodec.DecodeResult decoded = CursorCheckpointCodec.decode(encoded);

        assertTrue(decoded.valid());
        assertEquals(CursorCheckpoint.CURRENT_VERSION, decoded.version());
        assertEquals(original, decoded.checkpoint());
        assertEquals(encoded, CursorCheckpointCodec.encode(decoded.checkpoint()));
    }

    @Test
    void encodingIsDeterministicAcrossMapAndSetInsertionOrders() {
        Map<String, CursorCheckpoint.NodeState> forwardNodes = new LinkedHashMap<>();
        forwardNodes.put("root", activeState());
        forwardNodes.put("root/1", succeededState());
        Map<String, CursorCheckpoint.NodeState> reverseNodes = new LinkedHashMap<>();
        reverseNodes.put("root/1", succeededState());
        reverseNodes.put("root", activeState());

        CursorCheckpoint forward = checkpoint(
                forwardNodes,
                linkedMap("step.a", 1, "step.z", 2),
                linkedSet("checkpoint.a", "checkpoint.z"),
                linkedSet("event.a", "event.z"));
        CursorCheckpoint reverse = checkpoint(
                reverseNodes,
                linkedMap("step.z", 2, "step.a", 1),
                linkedSet("checkpoint.z", "checkpoint.a"),
                linkedSet("event.z", "event.a"));

        assertEquals(forward, reverse);
        assertEquals(CursorCheckpointCodec.encode(forward), CursorCheckpointCodec.encode(reverse));
    }

    @Test
    void checksumCorruptionFailsClosed() {
        String encoded = CursorCheckpointCodec.encode(simpleCheckpoint());
        String corrupt = encoded.substring(0, encoded.length() - 1)
                + (encoded.endsWith("0") ? "1" : "0");

        CursorCheckpointCodec.DecodeResult decoded = CursorCheckpointCodec.decode(corrupt);

        assertEquals(CursorCheckpointCodec.Status.CORRUPT, decoded.status());
        assertEquals("cursor_checkpoint_checksum_mismatch", decoded.reason());
        assertNull(decoded.checkpoint());
    }

    @Test
    void strictDecoderRejectsValidlyChecksummedTrailingBytes() {
        String encoded = CursorCheckpointCodec.encode(simpleCheckpoint());
        byte[] payload = payload(encoded);
        byte[] withTail = java.util.Arrays.copyOf(payload, payload.length + 1);
        withTail[withTail.length - 1] = 42;

        CursorCheckpointCodec.DecodeResult decoded = CursorCheckpointCodec.decode(
                envelope(withTail));

        assertEquals(CursorCheckpointCodec.Status.CORRUPT, decoded.status());
        assertEquals("cursor_checkpoint_trailing_bytes", decoded.reason());
    }

    @Test
    void validlyChecksummedButUnsortedCollectionsAreNoncanonical() {
        CursorCheckpoint checkpoint = checkpoint(
                Map.of("root", activeState()),
                Map.of("step.a", 1, "step.z", 2),
                Set.of(),
                Set.of());
        byte[] payload = payload(CursorCheckpointCodec.encode(checkpoint));
        byte[] first = "step.a".getBytes(StandardCharsets.UTF_8);
        byte[] second = "step.z".getBytes(StandardCharsets.UTF_8);
        int firstOffset = indexOf(payload, first) - Integer.BYTES;
        int secondOffset = indexOf(payload, second) - Integer.BYTES;
        int entryLength = Integer.BYTES + first.length + Integer.BYTES;
        swap(payload, firstOffset, secondOffset, entryLength);

        CursorCheckpointCodec.DecodeResult decoded = CursorCheckpointCodec.decode(
                envelope(payload));

        assertEquals(CursorCheckpointCodec.Status.CORRUPT, decoded.status());
        assertEquals("cursor_checkpoint_encoding_noncanonical", decoded.reason());
    }

    @Test
    void validlyChecksummedFutureSchemaIsUnsupportedWithoutPartialState() {
        String encoded = CursorCheckpointCodec.encode(simpleCheckpoint());
        byte[] payload = payload(encoded);
        int future = CursorCheckpoint.CURRENT_VERSION + 1;
        ByteBuffer.wrap(payload).putInt(4, future);

        CursorCheckpointCodec.DecodeResult decoded = CursorCheckpointCodec.decode(
                envelope(payload));

        assertEquals(CursorCheckpointCodec.Status.UNSUPPORTED_VERSION, decoded.status());
        assertEquals(future, decoded.version());
        assertNull(decoded.checkpoint());
    }

    @Test
    void truncatedAndNoncanonicalBase64AreRejectedEvenWithOtherwiseValidEnvelope() {
        String encoded = CursorCheckpointCodec.encode(simpleCheckpoint());
        byte[] payload = payload(encoded);
        byte[] truncated = java.util.Arrays.copyOf(payload, payload.length - 1);
        CursorCheckpointCodec.DecodeResult truncatedResult = CursorCheckpointCodec.decode(
                envelope(truncated));
        assertEquals(CursorCheckpointCodec.Status.CORRUPT, truncatedResult.status());

        byte[] paddingCandidate = payload.length % 3 == 0
                ? java.util.Arrays.copyOf(payload, payload.length + 1)
                : payload;
        String[] parts = envelope(paddingCandidate).split("\\.", -1);
        String padding = switch (parts[1].length() % 4) {
            case 2 -> "==";
            case 3 -> "=";
            default -> throw new AssertionError("test payload unexpectedly needs no padding");
        };
        String padded = parts[0] + '.' + parts[1] + padding + '.' + parts[2];
        CursorCheckpointCodec.DecodeResult noncanonical = CursorCheckpointCodec.decode(padded);
        assertEquals(CursorCheckpointCodec.Status.CORRUPT, noncanonical.status());
        assertEquals("cursor_checkpoint_base64_noncanonical", noncanonical.reason());
    }

    @Test
    void oversizedStringsAreRejectedBeforeWritingAnUnboundedPayload() {
        SkillOutcome oversized = new SkillOutcome(
                SkillOutcome.Status.BLOCKED,
                SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE,
                "x".repeat(8_193),
                0,
                Map.of());
        CursorCheckpoint checkpoint = new CursorCheckpoint(
                CursorCheckpoint.CURRENT_VERSION,
                MISSION_ID,
                0,
                PLAN_FINGERPRINT,
                0,
                Map.of("root", new CursorCheckpoint.NodeState(
                        CursorCheckpoint.NodePhase.FAILED,
                        0,
                        0,
                        -1,
                        -1,
                        false,
                        "root",
                        oversized)),
                Map.of(),
                Set.of(),
                Set.of());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> CursorCheckpointCodec.encode(checkpoint));
        assertEquals("cursor_outcome_reason_too_large", failure.getMessage());
    }

    @Test
    void outcomeEvidenceEntryLimitIsEnforcedBeforeSortingOrWriting() {
        Map<String, String> evidence = new LinkedHashMap<>();
        for (int index = 0; index < 129; index++) {
            evidence.put("key." + index, "value");
        }
        SkillOutcome oversized = new SkillOutcome(
                SkillOutcome.Status.BLOCKED,
                SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE,
                "too much evidence",
                0,
                evidence);
        CursorCheckpoint checkpoint = new CursorCheckpoint(
                CursorCheckpoint.CURRENT_VERSION,
                MISSION_ID,
                0,
                PLAN_FINGERPRINT,
                0,
                Map.of("root", new CursorCheckpoint.NodeState(
                        CursorCheckpoint.NodePhase.FAILED,
                        0,
                        0,
                        -1,
                        -1,
                        false,
                        "root",
                        oversized)),
                Map.of(),
                Set.of(),
                Set.of());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> CursorCheckpointCodec.encode(checkpoint));
        assertEquals("cursor_outcome_evidence_count_invalid", failure.getMessage());
    }

    @Test
    void oldOrFutureInMemorySchemasAreNeverWritten() {
        CursorCheckpoint future = new CursorCheckpoint(
                CursorCheckpoint.CURRENT_VERSION + 1,
                MISSION_ID,
                0,
                PLAN_FINGERPRINT,
                0,
                Map.of("root", activeState()),
                Map.of(),
                Set.of(),
                Set.of());
        assertEquals("cursor_checkpoint_version_not_writable", assertThrows(
                IllegalArgumentException.class,
                () -> CursorCheckpointCodec.encode(future)).getMessage());
    }

    private static CursorCheckpoint simpleCheckpoint() {
        return checkpoint(
                Map.of("root", activeState()),
                Map.of("step.one", 1),
                Set.of(),
                Set.of());
    }

    private static CursorCheckpoint checkpoint(Map<String, CursorCheckpoint.NodeState> nodes,
                                               Map<String, Integer> activations,
                                               Set<String> reached,
                                               Set<String> waiting) {
        return new CursorCheckpoint(
                CursorCheckpoint.CURRENT_VERSION,
                MISSION_ID,
                0,
                PLAN_FINGERPRINT,
                123,
                nodes,
                activations,
                reached,
                waiting);
    }

    private static CursorCheckpoint.NodeState activeState() {
        return new CursorCheckpoint.NodeState(
                CursorCheckpoint.NodePhase.ACTIVE,
                0,
                0,
                -1,
                -1,
                false,
                null,
                null);
    }

    private static CursorCheckpoint.NodeState succeededState() {
        return new CursorCheckpoint.NodeState(
                CursorCheckpoint.NodePhase.SUCCEEDED,
                0,
                0,
                -1,
                -1,
                true,
                null,
                null);
    }

    private static Map<String, Integer> linkedMap(String firstKey,
                                                  int firstValue,
                                                  String secondKey,
                                                  int secondValue) {
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put(firstKey, firstValue);
        values.put(secondKey, secondValue);
        return values;
    }

    private static Set<String> linkedSet(String first, String second) {
        Set<String> values = new LinkedHashSet<>();
        values.add(first);
        values.add(second);
        return values;
    }

    private static byte[] payload(String encoded) {
        String[] parts = encoded.split("\\.", -1);
        return BASE64_DECODER.decode(parts[1]);
    }

    private static String envelope(byte[] payload) {
        return "cpc." + BASE64_ENCODER.encodeToString(payload)
                + '.' + HEX.formatHex(sha256(payload));
    }

    private static int indexOf(byte[] source, byte[] target) {
        for (int offset = 0; offset <= source.length - target.length; offset++) {
            boolean matches = true;
            for (int index = 0; index < target.length; index++) {
                if (source[offset + index] != target[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return offset;
            }
        }
        throw new AssertionError("test byte sequence missing");
    }

    private static void swap(byte[] values, int first, int second, int length) {
        if (first < 0 || second < 0 || first + length > second) {
            throw new AssertionError("test ranges invalid");
        }
        for (int index = 0; index < length; index++) {
            byte value = values[first + index];
            values[first + index] = values[second + index];
            values[second + index] = value;
        }
    }

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
