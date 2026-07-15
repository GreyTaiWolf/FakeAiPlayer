package io.github.greytaiwolf.fakeaiplayer.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueprintGeneratedPersistenceTest {
    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.bootStrap();
    }

    @Test
    void writesValidatedExpandedBlueprintAndAllowsIdempotentRetry() throws Exception {
        BlueprintSchema source = new BlueprintSchema(
                "generated test",
                2,
                1,
                1,
                List.of(),
                List.of(new BlueprintSchema.Op(
                        "fill",
                        new int[]{0, 0, 0},
                        new int[]{1, 0, 0},
                        "minecraft:oak_planks",
                        null)));

        Path first = BlueprintLoader.saveGeneratedToDirectory(tempDir, "generated_owner_abc123", source);
        Path second = BlueprintLoader.saveGeneratedToDirectory(tempDir, "generated_owner_abc123", source);

        assertEquals(first, second);
        assertEquals(tempDir.resolve("generated_owner_abc123.json").toAbsolutePath(), first);
        String json = Files.readString(first);
        assertTrue(json.contains("\"placements\""));
        assertTrue(json.contains("\"dx\": 1"));
        assertTrue(json.contains("\"ops\": []"));
        try (var files = Files.list(tempDir)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".tmp-")));
        }
    }

    @Test
    void canonicalDigestBindsExpandedExecutionSemanticsNotJsonOrdering() throws Exception {
        BlueprintSchema opSource = new BlueprintSchema(
                "canonical",
                2,
                1,
                1,
                List.of(),
                List.of(new BlueprintSchema.Op(
                        "fill", new int[]{0, 0, 0}, new int[]{1, 0, 0},
                        "minecraft:oak_planks", null)));
        BlueprintSchema expanded = BlueprintLoader.expand(opSource);

        assertEquals(BlueprintLoader.canonicalDigest(opSource),
                BlueprintLoader.canonicalDigest(expanded));
        assertNotEquals(BlueprintLoader.canonicalDigest(expanded),
                BlueprintLoader.canonicalDigest(new BlueprintSchema(
                        "canonical", 2, 1, 1,
                        List.of(
                                new BlueprintSchema.BlockPlacement(0, 0, 0, "minecraft:oak_planks"),
                                new BlueprintSchema.BlockPlacement(1, 0, 0, "minecraft:stone")),
                        List.of())));
        assertThrows(IOException.class,
                () -> BlueprintLoader.verifyDigest(expanded, "00".repeat(32)));
    }

    @Test
    void prerequisiteOrderDoesNotChangeCanonicalDigest() throws Exception {
        BlueprintSchema first = sequencedBlueprint(List.of(0, 1));
        BlueprintSchema second = sequencedBlueprint(List.of(1, 0));

        assertEquals(BlueprintLoader.canonicalDigest(first), BlueprintLoader.canonicalDigest(second));
    }

    @Test
    void confirmedBuildTaskFreezesTheVerifiedProgramAtItsConstructorBoundary() throws Exception {
        List<BlueprintSchema.BlockPlacement> mutablePlacements = new ArrayList<>();
        mutablePlacements.add(new BlueprintSchema.BlockPlacement(
                0, 0, 0, "minecraft:oak_planks"));
        BlueprintSchema source = new BlueprintSchema(
                "bound", 1, 1, 1, mutablePlacements, List.of());
        String digest = BlueprintLoader.canonicalDigest(source);

        BuildTask task = new BuildTask(
                source, BlockPos.ZERO, false, false, digest, "minecraft:overworld");
        mutablePlacements.set(0, new BlueprintSchema.BlockPlacement(
                0, 0, 0, "minecraft:stone"));

        assertEquals(digest, BlueprintLoader.canonicalDigest(task.blueprint()));
        assertEquals("minecraft:oak_planks", task.blueprint().placements().get(0).blockId());
        assertThrows(UnsupportedOperationException.class,
                () -> task.blueprint().placements().add(new BlueprintSchema.BlockPlacement(
                        0, 0, 0, "minecraft:dirt")));
    }

    @Test
    void neverChangesMeaningOfANameReferencedByAnExistingMission() throws Exception {
        BlueprintLoader.saveGeneratedToDirectory(
                tempDir, "generated_owner_stable", oneBlock("first"));

        assertThrows(IOException.class, () -> BlueprintLoader.saveGeneratedToDirectory(
                tempDir,
                "generated_owner_stable",
                new BlueprintSchema(
                        "different",
                        1,
                        1,
                        1,
                        List.of(new BlueprintSchema.BlockPlacement(0, 0, 0, "minecraft:stone")),
                        List.of())));
        assertTrue(Files.readString(tempDir.resolve("generated_owner_stable.json"))
                .contains("minecraft:oak_planks"));
    }

    @Test
    void rejectsTraversalAndNamesOutsideGeneratedNamespace() {
        BlueprintSchema source = oneBlock("small");

        assertThrows(IOException.class, () -> BlueprintLoader.saveGeneratedToDirectory(
                tempDir, "../generated_escape", source));
        assertThrows(IOException.class, () -> BlueprintLoader.saveGeneratedToDirectory(
                tempDir, "small_hut", source));
        assertThrows(IOException.class, () -> BlueprintLoader.saveGeneratedToDirectory(
                tempDir, "generated_owner.json", source));
    }

    @Test
    void refusesToReplaceSymbolicLinkTarget() throws Exception {
        Path outside = tempDir.resolve("outside.json");
        Files.writeString(outside, "sentinel");
        Path target = tempDir.resolve("generated_owner_link.json");
        Files.createSymbolicLink(target, outside);

        assertThrows(IOException.class, () -> BlueprintLoader.saveGeneratedToDirectory(
                tempDir, "generated_owner_link", oneBlock("link")));
        assertEquals("sentinel", Files.readString(outside));
    }

    @Test
    void rejectsEncodedDocumentsBeyondLoaderLimitBeforeCreatingTarget() {
        String oversizedAtomicGroup = "x".repeat(4 * 1024 * 1024);
        BlueprintSchema oversized = new BlueprintSchema(
                "oversized",
                1,
                1,
                1,
                List.of(new BlueprintSchema.BlockPlacement(
                        0, 0, 0, "minecraft:oak_planks", null, java.util.Map.of(),
                        io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation.PLACE,
                        io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy.REPLACE_REPLACEABLE,
                        oversizedAtomicGroup,
                        0)),
                List.of());

        assertThrows(IOException.class, () -> BlueprintLoader.saveGeneratedToDirectory(
                tempDir, "generated_owner_oversized", oversized));
        assertFalse(Files.exists(tempDir.resolve("generated_owner_oversized.json")));
    }

    private static BlueprintSchema oneBlock(String name) {
        return new BlueprintSchema(
                name,
                1,
                1,
                1,
                List.of(new BlueprintSchema.BlockPlacement(0, 0, 0, "minecraft:oak_planks")),
                List.of());
    }

    private static BlueprintSchema sequencedBlueprint(List<Integer> finalPrerequisites) {
        return new BlueprintSchema(
                "sequenced",
                3,
                1,
                1,
                List.of(
                        placement(0, 0, List.of()),
                        placement(1, 1, List.of(0)),
                        placement(2, 2, finalPrerequisites)),
                List.of());
    }

    private static BlueprintSchema.BlockPlacement placement(int x,
                                                            int sequence,
                                                            List<Integer> prerequisites) {
        return new BlueprintSchema.BlockPlacement(
                x, 0, 0, "minecraft:oak_planks", null, java.util.Map.of(),
                io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation.PLACE,
                io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy.REPLACE_REPLACEABLE,
                "", sequence, prerequisites);
    }
}
