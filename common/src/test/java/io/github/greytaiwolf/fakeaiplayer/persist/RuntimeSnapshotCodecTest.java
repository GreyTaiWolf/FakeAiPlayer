package io.github.greytaiwolf.fakeaiplayer.persist;

import io.github.greytaiwolf.fakeaiplayer.goal.Goal;
import io.github.greytaiwolf.fakeaiplayer.mission.GoalSpec;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionPolicy;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSnapshotCodecTest {
    @Test
    void roundTripsCurrentSchema() {
        RuntimeSnapshot original = new RuntimeSnapshot(
                RuntimeSnapshot.CURRENT_SCHEMA,
                Instant.EPOCH.toString(),
                "test-build",
                "test-session",
                List.of(),
                List.of());

        RuntimeSnapshotCodec.DecodeResult decoded = RuntimeSnapshotCodec.decode(
                new StringReader(RuntimeSnapshotCodec.encode(original)));

        assertEquals(RuntimeSnapshotCodec.Status.OK, decoded.status());
        assertEquals(original, decoded.snapshot());
    }

    @Test
    void roundTripsBoundActiveAndQueuedMissionSpecsThroughGsonRecords() {
        MissionSpec active = MissionSpec.fromGoal(
                new Goal.Food(3), GoalSpec.Source.PLAYER_COMMAND, 91,
                MissionPolicy.standard());
        MissionSpec queued = MissionSpec.fromGoal(
                new Goal.Food(5), GoalSpec.Source.AI_PROPOSAL, 70);
        RuntimeSnapshot original = new RuntimeSnapshot(
                RuntimeSnapshot.CURRENT_SCHEMA,
                Instant.EPOCH.toString(),
                "test-build",
                "test-session",
                List.of(new PersistedBot(
                        new BotRecord("worker", "minecraft:overworld", 0, 64, 0,
                                0, 0, "survival", 20, 20, "", "worker", "", ""),
                        new MissionRuntimeRecord(
                                new MissionRecord(UUID.randomUUID().toString(), active, Map.of()),
                                List.of(queued),
                                true))),
                List.of());

        RuntimeSnapshotCodec.DecodeResult decoded = RuntimeSnapshotCodec.decode(
                new StringReader(RuntimeSnapshotCodec.encode(original)));

        assertEquals(RuntimeSnapshotCodec.Status.OK, decoded.status());
        MissionRuntimeRecord restored = decoded.snapshot().bots().getFirst().missions();
        assertEquals(active, restored.active().spec());
        assertEquals(queued, restored.queue().getFirst());
        assertTrue(restored.active().spec().bindingValid());
        assertTrue(restored.queue().getFirst().bindingValid());
    }

    @Test
    void rejectsFutureSchemaWithoutReturningPartialState() {
        RuntimeSnapshotCodec.DecodeResult decoded = RuntimeSnapshotCodec.decode(
                new StringReader("{\"schemaVersion\":999,\"bots\":[],\"jobs\":[]}"));

        assertEquals(RuntimeSnapshotCodec.Status.UNSUPPORTED_SCHEMA, decoded.status());
        assertEquals(999, decoded.foundSchema());
        assertNull(decoded.snapshot());
    }

    @Test
    void rejectsMalformedOrUnversionedDocuments() {
        assertEquals(RuntimeSnapshotCodec.Status.MALFORMED,
                RuntimeSnapshotCodec.decode(new StringReader("not-json")).status());
        assertEquals(RuntimeSnapshotCodec.Status.MALFORMED,
                RuntimeSnapshotCodec.decode(new StringReader("[]")).status());
        assertEquals(RuntimeSnapshotCodec.Status.MALFORMED,
                RuntimeSnapshotCodec.decode(new StringReader("{\"bots\":[]}")).status());
    }
}
