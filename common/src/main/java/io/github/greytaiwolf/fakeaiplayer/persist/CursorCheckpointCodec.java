package io.github.greytaiwolf.fakeaiplayer.persist;

import io.github.greytaiwolf.fakeaiplayer.mission.CursorCheckpoint;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionPlan;
import io.github.greytaiwolf.fakeaiplayer.mission.SkillOutcome;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Deterministic, checksummed, Java-standard-library codec for {@link CursorCheckpoint}.
 *
 * <p>The binary payload is wrapped as {@code cpc.BASE64URL.SHA256}. Collection entries are always
 * sorted before writing, every allocation is bounded before it happens, and the decoder rejects
 * both non-canonical encodings and trailing bytes.</p>
 */
public final class CursorCheckpointCodec {
    /** Binary layout version; intentionally independent so value-object changes cannot auto-bump it. */
    public static final int CURRENT_VERSION = 2;

    private static final int MAGIC = 0x4350434b; // CPCK
    private static final String PREFIX = "cpc";
    private static final int MAX_BINARY_LENGTH = 262_144;
    private static final int MAX_ENCODED_LENGTH = 360_000;
    private static final int MAX_STRING_BYTES = 8_192;
    private static final int MAX_EVIDENCE_ENTRIES = 128;
    private static final int MAX_COLLECTION_ENTRIES = MissionPlan.MAX_PLAN_NODES;
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();
    private static final HexFormat HEX = HexFormat.of();

    static {
        if (CursorCheckpoint.CURRENT_VERSION != CURRENT_VERSION) {
            throw new IllegalStateException("cursor_checkpoint_codec_version_out_of_sync");
        }
    }

    private CursorCheckpointCodec() {
    }

    public static String encode(CursorCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("cursor_checkpoint_missing");
        }
        if (checkpoint.schemaVersion() != CURRENT_VERSION) {
            throw new IllegalArgumentException("cursor_checkpoint_version_not_writable");
        }
        final byte[] payload;
        try {
            ByteArrayOutputStream bytes = new LimitedByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeCheckpoint(output, checkpoint);
            }
            payload = bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("cursor_checkpoint_encode_failed", exception);
        }
        if (payload.length > MAX_BINARY_LENGTH) {
            throw new IllegalArgumentException("cursor_checkpoint_payload_too_large");
        }
        String encoded = PREFIX + '.' + BASE64_ENCODER.encodeToString(payload)
                + '.' + HEX.formatHex(sha256(payload));
        if (encoded.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("cursor_checkpoint_encoding_too_large");
        }
        return encoded;
    }

    public static DecodeResult decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_ENCODED_LENGTH) {
            return DecodeResult.corrupt(-1, "cursor_checkpoint_encoding_invalid");
        }
        String[] parts = encoded.split("\\.", 4);
        if (parts.length != 3 || !PREFIX.equals(parts[0])
                || parts[1].isEmpty() || !parts[2].matches("[0-9a-f]{64}")) {
            return DecodeResult.corrupt(-1, "cursor_checkpoint_envelope_invalid");
        }

        final byte[] payload;
        try {
            payload = BASE64_DECODER.decode(parts[1]);
        } catch (IllegalArgumentException exception) {
            return DecodeResult.corrupt(-1, "cursor_checkpoint_base64_invalid");
        }
        if (payload.length == 0 || payload.length > MAX_BINARY_LENGTH
                || !BASE64_ENCODER.encodeToString(payload).equals(parts[1])) {
            return DecodeResult.corrupt(-1, "cursor_checkpoint_base64_noncanonical");
        }
        byte[] expectedChecksum;
        try {
            expectedChecksum = HEX.parseHex(parts[2]);
        } catch (IllegalArgumentException exception) {
            return DecodeResult.corrupt(-1, "cursor_checkpoint_checksum_invalid");
        }
        if (!MessageDigest.isEqual(expectedChecksum, sha256(payload))) {
            return DecodeResult.corrupt(-1, "cursor_checkpoint_checksum_mismatch");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                return DecodeResult.corrupt(-1, "cursor_checkpoint_magic_invalid");
            }
            int version = input.readInt();
            if (version > CURRENT_VERSION) {
                return DecodeResult.unsupported(version);
            }
            if (version != CURRENT_VERSION) {
                return DecodeResult.corrupt(version, "cursor_checkpoint_version_invalid");
            }
            CursorCheckpoint checkpoint = readCheckpoint(input, version);
            if (input.read() != -1) {
                return DecodeResult.corrupt(version, "cursor_checkpoint_trailing_bytes");
            }
            if (!encode(checkpoint).equals(encoded)) {
                return DecodeResult.corrupt(version, "cursor_checkpoint_encoding_noncanonical");
            }
            return DecodeResult.ok(version, checkpoint);
        } catch (EOFException exception) {
            return DecodeResult.corrupt(-1, "cursor_checkpoint_truncated");
        } catch (IOException | RuntimeException exception) {
            return DecodeResult.corrupt(-1,
                    exception.getMessage() == null || exception.getMessage().isBlank()
                            ? "cursor_checkpoint_payload_invalid" : exception.getMessage());
        }
    }

    private static void writeCheckpoint(DataOutputStream output,
                                        CursorCheckpoint checkpoint) throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(checkpoint.schemaVersion());
        output.writeLong(checkpoint.missionId().getMostSignificantBits());
        output.writeLong(checkpoint.missionId().getLeastSignificantBits());
        output.writeInt(checkpoint.planRevision());
        writeString(output, checkpoint.planFingerprint(), "cursor_plan_fingerprint");
        output.writeLong(checkpoint.tick());

        TreeMap<String, CursorCheckpoint.NodeState> nodes =
                new TreeMap<>(checkpoint.nodeStates());
        writeCount(output, nodes.size(), MAX_COLLECTION_ENTRIES, "cursor_node_count");
        for (Map.Entry<String, CursorCheckpoint.NodeState> entry : nodes.entrySet()) {
            writeString(output, entry.getKey(), "cursor_node_path");
            writeNodeState(output, entry.getValue());
        }

        TreeMap<String, Integer> activations = new TreeMap<>(checkpoint.activationCounts());
        writeCount(output, activations.size(), MAX_COLLECTION_ENTRIES,
                "cursor_activation_count");
        for (Map.Entry<String, Integer> entry : activations.entrySet()) {
            writeString(output, entry.getKey(), "cursor_activation_id");
            output.writeInt(entry.getValue());
        }
        writeSet(output, checkpoint.reachedCheckpoints(), "cursor_reached_checkpoint");
        writeSet(output, checkpoint.waitingEvents(), "cursor_waiting_event");
    }

    private static CursorCheckpoint readCheckpoint(DataInputStream input,
                                                   int version) throws IOException {
        UUID missionId = new UUID(input.readLong(), input.readLong());
        int planRevision = readNonNegativeInt(input, "cursor_plan_revision_negative");
        String planFingerprint = readString(input, "cursor_plan_fingerprint");
        long tick = input.readLong();
        if (tick < 0) {
            throw new IllegalArgumentException("cursor_tick_negative");
        }

        int nodeCount = readCount(input, MAX_COLLECTION_ENTRIES, "cursor_node_count_invalid");
        Map<String, CursorCheckpoint.NodeState> nodes = new LinkedHashMap<>();
        for (int index = 0; index < nodeCount; index++) {
            String path = readString(input, "cursor_node_path");
            if (nodes.putIfAbsent(path, readNodeState(input)) != null) {
                throw new IllegalArgumentException("cursor_node_path_duplicate");
            }
        }

        int activationCount = readCount(
                input, MAX_COLLECTION_ENTRIES, "cursor_activation_count_invalid");
        Map<String, Integer> activations = new LinkedHashMap<>();
        for (int index = 0; index < activationCount; index++) {
            String invocationId = readString(input, "cursor_activation_id");
            int count = input.readInt();
            if (count < 1 || activations.putIfAbsent(invocationId, count) != null) {
                throw new IllegalArgumentException("cursor_activation_invalid");
            }
        }
        Set<String> reached = readSet(input, "cursor_reached_checkpoint");
        Set<String> waiting = readSet(input, "cursor_waiting_event");
        return new CursorCheckpoint(version, missionId, planRevision, planFingerprint, tick,
                nodes, activations, reached, waiting);
    }

    private static void writeNodeState(DataOutputStream output,
                                       CursorCheckpoint.NodeState state) throws IOException {
        writeString(output, state.phase().name(), "cursor_node_phase");
        output.writeInt(state.childIndex());
        output.writeInt(state.retryAttempt());
        output.writeLong(state.timeoutStartedTick());
        output.writeLong(state.timeoutElapsedTicks());
        output.writeBoolean(state.checkpointReached());
        boolean failed = state.failureOutcome() != null;
        output.writeBoolean(failed);
        if (failed) {
            writeString(output, state.failurePath(), "cursor_failure_path");
            writeOutcome(output, state.failureOutcome());
        }
    }

    private static CursorCheckpoint.NodeState readNodeState(DataInputStream input)
            throws IOException {
        CursorCheckpoint.NodePhase phase = requiredEnum(
                CursorCheckpoint.NodePhase.class,
                readString(input, "cursor_node_phase"),
                "cursor_node_phase_invalid");
        int childIndex = readNonNegativeInt(input, "cursor_child_index_negative");
        int retryAttempt = readNonNegativeInt(input, "cursor_retry_attempt_negative");
        long timeoutStartedTick = input.readLong();
        long timeoutElapsedTicks = input.readLong();
        boolean checkpointReached = readBoolean(input, "cursor_checkpoint_reached_invalid");
        boolean failed = readBoolean(input, "cursor_failure_marker_invalid");
        String failurePath = null;
        SkillOutcome failureOutcome = null;
        if (failed) {
            failurePath = readString(input, "cursor_failure_path");
            failureOutcome = readOutcome(input);
        }
        return new CursorCheckpoint.NodeState(
                phase,
                childIndex,
                retryAttempt,
                timeoutStartedTick,
                timeoutElapsedTicks,
                checkpointReached,
                failurePath,
                failureOutcome);
    }

    private static void writeOutcome(DataOutputStream output,
                                     SkillOutcome outcome) throws IOException {
        writeString(output, outcome.status().name(), "cursor_outcome_status");
        writeString(output, outcome.failureKind().name(), "cursor_outcome_failure_kind");
        writeString(output, outcome.reason(), "cursor_outcome_reason");
        output.writeInt(outcome.progress());
        if (outcome.evidence().size() > MAX_EVIDENCE_ENTRIES) {
            throw new IllegalArgumentException("cursor_outcome_evidence_count_invalid");
        }
        TreeMap<String, String> evidence = new TreeMap<>(outcome.evidence());
        writeCount(output, evidence.size(), MAX_EVIDENCE_ENTRIES,
                "cursor_outcome_evidence_count");
        for (Map.Entry<String, String> entry : evidence.entrySet()) {
            writeString(output, entry.getKey(), "cursor_outcome_evidence_key");
            writeString(output, entry.getValue(), "cursor_outcome_evidence_value");
        }
    }

    private static SkillOutcome readOutcome(DataInputStream input) throws IOException {
        SkillOutcome.Status status = requiredEnum(
                SkillOutcome.Status.class,
                readString(input, "cursor_outcome_status"),
                "cursor_outcome_status_invalid");
        SkillOutcome.FailureKind failureKind = requiredEnum(
                SkillOutcome.FailureKind.class,
                readString(input, "cursor_outcome_failure_kind"),
                "cursor_outcome_failure_kind_invalid");
        String reason = readString(input, "cursor_outcome_reason");
        int progress = readNonNegativeInt(input, "cursor_outcome_progress_negative");
        int evidenceCount = readCount(
                input, MAX_EVIDENCE_ENTRIES, "cursor_outcome_evidence_count_invalid");
        Map<String, String> evidence = new LinkedHashMap<>();
        for (int index = 0; index < evidenceCount; index++) {
            String key = readString(input, "cursor_outcome_evidence_key");
            String value = readString(input, "cursor_outcome_evidence_value");
            if (evidence.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("cursor_outcome_evidence_key_duplicate");
            }
        }
        return new SkillOutcome(status, failureKind, reason, progress, evidence);
    }

    private static void writeSet(DataOutputStream output,
                                 Set<String> source,
                                 String key) throws IOException {
        java.util.List<String> sorted = source.stream().sorted().toList();
        writeCount(output, sorted.size(), MAX_COLLECTION_ENTRIES, key + "_count");
        for (String value : sorted) {
            writeString(output, value, key);
        }
    }

    private static Set<String> readSet(DataInputStream input, String key) throws IOException {
        int count = readCount(input, MAX_COLLECTION_ENTRIES, key + "_count_invalid");
        Set<String> values = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            if (!values.add(readString(input, key))) {
                throw new IllegalArgumentException(key + "_duplicate");
            }
        }
        return values;
    }

    private static void writeCount(DataOutputStream output,
                                   int count,
                                   int maximum,
                                   String key) throws IOException {
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException(key + "_invalid");
        }
        output.writeInt(count);
    }

    private static int readCount(DataInputStream input, int maximum, String reason)
            throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException(reason);
        }
        return count;
    }

    private static int readNonNegativeInt(DataInputStream input, String reason)
            throws IOException {
        int value = input.readInt();
        if (value < 0) {
            throw new IllegalArgumentException(reason);
        }
        return value;
    }

    private static boolean readBoolean(DataInputStream input, String reason) throws IOException {
        int value = input.readUnsignedByte();
        if (value != 0 && value != 1) {
            throw new IllegalArgumentException(reason);
        }
        return value == 1;
    }

    private static void writeString(DataOutputStream output, String value, String key)
            throws IOException {
        if (value == null) {
            throw new IllegalArgumentException(key + "_missing");
        }
        if (value.length() > MAX_STRING_BYTES) {
            throw new IllegalArgumentException(key + "_too_large");
        }
        byte[] bytes = encodeUtf8(value, key);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException(key + "_too_large");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, String key) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException(key + "_length_invalid");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(key + "_utf8_invalid", exception);
        }
    }

    private static byte[] encodeUtf8(String value, String key) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(key + "_utf16_invalid", exception);
        }
    }

    private static <E extends Enum<E>> E requiredEnum(Class<E> type,
                                                       String value,
                                                       String reason) {
        try {
            E decoded = Enum.valueOf(type, value);
            if (!decoded.name().equals(value)) {
                throw new IllegalArgumentException(reason);
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(reason, exception);
        }
    }

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha256_unavailable", exception);
        }
    }

    /** Prevents a valid-looking object graph from allocating past the durable payload budget. */
    private static final class LimitedByteArrayOutputStream extends ByteArrayOutputStream {
        private LimitedByteArrayOutputStream() {
            super(1_024);
        }

        @Override
        public synchronized void write(int value) {
            requireCapacity(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            if (bytes == null || offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException();
            }
            requireCapacity(length);
            super.write(bytes, offset, length);
        }

        private void requireCapacity(int additionalBytes) {
            if (additionalBytes > MAX_BINARY_LENGTH - count) {
                throw new IllegalArgumentException("cursor_checkpoint_payload_too_large");
            }
        }
    }

    public enum Status {
        OK,
        CORRUPT,
        UNSUPPORTED_VERSION
    }

    public record DecodeResult(
            Status status,
            int version,
            CursorCheckpoint checkpoint,
            String reason
    ) {
        public DecodeResult {
            if (status == null || reason == null) {
                throw new IllegalArgumentException("invalid_cursor_checkpoint_decode_result");
            }
            if ((status == Status.OK) != (checkpoint != null)) {
                throw new IllegalArgumentException("cursor_checkpoint_decode_result_mismatch");
            }
        }

        public boolean valid() {
            return status == Status.OK;
        }

        private static DecodeResult ok(int version, CursorCheckpoint checkpoint) {
            return new DecodeResult(Status.OK, version, checkpoint, "");
        }

        private static DecodeResult corrupt(int version, String reason) {
            return new DecodeResult(Status.CORRUPT, version, null,
                    reason == null || reason.isBlank()
                            ? "cursor_checkpoint_corrupt" : reason);
        }

        private static DecodeResult unsupported(int version) {
            return new DecodeResult(Status.UNSUPPORTED_VERSION, version, null,
                    "cursor_checkpoint_version_unsupported");
        }
    }
}
