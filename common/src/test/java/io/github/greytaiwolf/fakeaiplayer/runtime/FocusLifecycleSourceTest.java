package io.github.greytaiwolf.fakeaiplayer.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusLifecycleSourceTest {
    private static final Path MAIN = Path.of(System.getProperty(
            "fakeaiplayer.mainSourceDir",
            "src/main/java/io/github/greytaiwolf/fakeaiplayer"));

    @Test
    void focusCacheParticipatesInBotAndWorldCleanup() throws IOException {
        String lifecycle = Files.readString(MAIN.resolve("runtime/RuntimeLifecycleCoordinator.java"));

        assertTrue(lifecycle.contains("FocusTracker.INSTANCE.clear(bot)"));
        assertTrue(lifecycle.contains("FocusTracker.INSTANCE.clearAll()"));
    }

    @Test
    void semanticEyeRemainsIndependentFromMainTaskSlot() throws IOException {
        Path focus = MAIN.resolve("perception/focus");
        try (var sources = Files.walk(focus)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source);
                assertFalse(text.contains("import io.github.greytaiwolf.fakeaiplayer.task.TaskManager"), source.toString());
            }
        }
    }
}
