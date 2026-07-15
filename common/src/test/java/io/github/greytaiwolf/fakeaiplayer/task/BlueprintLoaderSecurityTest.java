package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.platform.PlatformEnvironment;
import io.github.greytaiwolf.fakeaiplayer.platform.PlatformServices;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueprintLoaderSecurityTest {
    private static Path gameDirectory;

    @BeforeAll
    static void initializePlatformDirectory() throws IOException {
        gameDirectory = Files.createTempDirectory("fakeaiplayer-blueprint-load-");
        PlatformServices.initialize(new PlatformEnvironment() {
            @Override
            public Path gameDirectory() {
                return gameDirectory;
            }

            @Override
            public Path configDirectory() {
                return gameDirectory.resolve("config");
            }

            @Override
            public String modVersion() {
                return "test";
            }

            @Override
            public String loaderName() {
                return "junit";
            }
        });
    }

    @Test
    void rejectsPathSeparatorsBeforeResolvingAFile() {
        IOException slash = assertThrows(
                IOException.class,
                () -> BlueprintLoader.load("../outside"));
        IOException backslash = assertThrows(
                IOException.class,
                () -> BlueprintLoader.load("..\\outside"));

        assertTrue(slash.getMessage().contains("path_separators"));
        assertTrue(backslash.getMessage().contains("path_separators"));
    }

    @Test
    void rejectsFilesLargerThanTheBoundBeforeParsing() throws IOException {
        Path directory = Files.createDirectories(gameDirectory.resolve("blueprints"));
        Path oversized = directory.resolve("oversized.json");
        byte[] oneMegabyte = new byte[1024 * 1024];
        try (OutputStream output = Files.newOutputStream(oversized)) {
            for (int index = 0; index < 5; index++) {
                output.write(oneMegabyte);
            }
        }

        IOException exception = assertThrows(
                IOException.class,
                () -> BlueprintLoader.load("oversized"));

        assertTrue(exception.getMessage().contains("blueprint_file_too_large"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void doesNotFollowASymlinkDisguisedAsABlueprint() throws IOException {
        Path directory = Files.createDirectories(gameDirectory.resolve("blueprints"));
        Path target = gameDirectory.resolve("outside.json");
        Files.writeString(target, "{}");
        Path link = directory.resolve("linked.json");
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, target);

        IOException exception = assertThrows(
                IOException.class,
                () -> BlueprintLoader.load("linked"));

        assertTrue(exception.getMessage().contains("blueprint_not_found"));
    }
}
