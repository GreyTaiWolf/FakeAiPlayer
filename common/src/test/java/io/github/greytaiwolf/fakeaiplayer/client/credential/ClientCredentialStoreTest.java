package io.github.greytaiwolf.fakeaiplayer.client.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientCredentialStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsByNormalizedServerAndBotWithoutLeakingThroughToString() throws IOException {
        Path file = temporaryDirectory.resolve("config").resolve(ClientCredentialStore.FILE_NAME);
        ClientCredentialStore store = new ClientCredentialStore(file);

        store.put("server:Example.COM:25565", "HelperBot", "sk-private-one");
        store.put("server:other.example:25565", "HelperBot", "sk-private-two");

        assertEquals("sk-private-one",
                store.find("SERVER:example.com:25565", "helperbot").orElseThrow());
        assertEquals("sk-private-two",
                store.find("server:OTHER.EXAMPLE:25565", "HELPERBOT").orElseThrow());
        assertTrue(store.find("server:missing.example", "HelperBot").isEmpty());

        ClientCredentialStore reloaded = new ClientCredentialStore(file);
        List<ClientCredentialStore.Credential> credentials =
                reloaded.forServer("server:example.com:25565");
        assertEquals(1, credentials.size());
        assertEquals("HelperBot", credentials.getFirst().botName());
        assertEquals("sk-private-one", credentials.getFirst().apiKey());
        assertFalse(credentials.getFirst().toString().contains("sk-private-one"));
        assertTrue(credentials.getFirst().toString().contains("<redacted>"));
    }

    @Test
    void replacesAtomicallyAndUsesOwnerOnlyPermissionsWhereSupported() throws IOException {
        Path file = temporaryDirectory.resolve("config").resolve(ClientCredentialStore.FILE_NAME);
        ClientCredentialStore store = new ClientCredentialStore(file);
        store.put("server:example.com", "Bot", "sk-first");
        store.put("server:example.com", "Bot", "sk-second");

        assertEquals("sk-second", new ClientCredentialStore(file)
                .find("server:example.com", "Bot").orElseThrow());
        try (var children = Files.list(file.getParent())) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file));
        }
    }

    @Test
    void removeIsScopedAndPersists() throws IOException {
        Path file = temporaryDirectory.resolve("config").resolve(ClientCredentialStore.FILE_NAME);
        ClientCredentialStore store = new ClientCredentialStore(file);
        store.put("server:first", "Bot", "sk-first");
        store.put("server:second", "Bot", "sk-second");

        assertTrue(store.remove("SERVER:FIRST", "bot"));
        assertFalse(store.remove("server:first", "Bot"));
        ClientCredentialStore reloaded = new ClientCredentialStore(file);
        assertTrue(reloaded.find("server:first", "Bot").isEmpty());
        assertEquals("sk-second", reloaded.find("server:second", "Bot").orElseThrow());
    }

    @Test
    void rejectsControlCharactersAndNeverIncludesCredentialInErrors() throws IOException {
        ClientCredentialStore store = new ClientCredentialStore(
                temporaryDirectory.resolve(ClientCredentialStore.FILE_NAME));
        String credential = "sk-private\nvalue";

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> store.put("server:example", "Bot", credential));
        assertFalse(failure.getMessage().contains(credential));
        assertFalse(Files.exists(store.file()));
    }

    @Test
    void malformedFileFailsWithoutEchoingStoredText() throws IOException {
        Path file = temporaryDirectory.resolve(ClientCredentialStore.FILE_NAME);
        Files.writeString(file, "{\"apiKey\":\"sk-do-not-echo\"", StandardCharsets.UTF_8);

        IOException failure = assertThrows(IOException.class, () -> new ClientCredentialStore(file));
        assertFalse(failure.getMessage().contains("sk-do-not-echo"));
    }
}
