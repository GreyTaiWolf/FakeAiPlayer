package io.github.greytaiwolf.fakeaiplayer.client.credential;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.greytaiwolf.fakeaiplayer.network.payload.PayloadLimits;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure-Java, client-local credential persistence keyed by server scope and bot name.
 *
 * <p>The requested storage is plaintext JSON, so the file is kept owner-readable/writable where
 * POSIX permissions are available. Writes use a same-directory temporary file and atomic replace.
 * No value-bearing type in this class exposes a credential through {@code toString()}.</p>
 */
public final class ClientCredentialStore {
    public static final String FILE_NAME = "fakeaiplayer-client.json";
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_SERVER_SCOPE_LENGTH = 512;
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private Map<CredentialId, Credential> credentials;

    public ClientCredentialStore(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        if (Files.exists(this.file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(this.file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("client credential path is not a regular file");
            }
            applyOwnerOnlyPermissions(this.file);
        }
        this.credentials = read(this.file);
    }

    public synchronized Optional<String> find(String serverScope, String botName) {
        Credential credential = credentials.get(id(serverScope, botName));
        return credential == null ? Optional.empty() : Optional.of(credential.apiKey());
    }

    public synchronized List<Credential> forServer(String serverScope) {
        String normalizedScope = normalizeScope(serverScope);
        return credentials.values().stream()
                .filter(credential -> credential.normalizedServerScope().equals(normalizedScope))
                .sorted(Comparator.comparing(Credential::normalizedBotName))
                .map(Credential::copy)
                .toList();
    }

    public synchronized void put(String serverScope, String botName, String apiKey) throws IOException {
        validateApiKey(apiKey);
        Credential credential = new Credential(
                normalizeScope(serverScope), cleanBotName(botName), apiKey);
        Map<CredentialId, Credential> updated = new LinkedHashMap<>(credentials);
        updated.put(credential.id(), credential);
        write(updated);
        credentials = updated;
    }

    public synchronized boolean remove(String serverScope, String botName) throws IOException {
        CredentialId id = id(serverScope, botName);
        if (!credentials.containsKey(id)) {
            return false;
        }
        Map<CredentialId, Credential> updated = new LinkedHashMap<>(credentials);
        updated.remove(id);
        write(updated);
        credentials = updated;
        return true;
    }

    public Path file() {
        return file;
    }

    private void write(Map<CredentialId, Credential> updated) throws IOException {
        Path directory = file.getParent();
        if (directory == null) {
            throw new IOException("credential file has no parent directory");
        }
        Files.createDirectories(directory);
        Path temporary = createSecureTemporaryFile(directory);
        boolean moved = false;
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(toJson(updated), writer);
            }
            applyOwnerOnlyPermissions(temporary);
            try {
                Files.move(temporary, file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            // The same-directory temporary file was already 0600. A rename preserves its mode;
            // do not add a fallible post-commit step that could desynchronize disk and memory.
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static Map<CredentialId, Credential> read(Path file) throws IOException {
        Map<CredentialId, Credential> loaded = new LinkedHashMap<>();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return loaded;
        }
        JsonElement parsed;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            parsed = JsonParser.parseReader(reader);
        } catch (RuntimeException malformed) {
            throw new IOException("client credential file is not valid JSON", malformed);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IOException("client credential file root is not an object");
        }
        JsonObject root = parsed.getAsJsonObject();
        JsonElement version = root.get("version");
        int formatVersion;
        try {
            formatVersion = version == null || !version.isJsonPrimitive()
                    ? -1
                    : version.getAsInt();
        } catch (RuntimeException malformedVersion) {
            throw new IOException("unsupported client credential file version", malformedVersion);
        }
        if (formatVersion != FORMAT_VERSION) {
            throw new IOException("unsupported client credential file version");
        }
        JsonElement serversElement = root.get("servers");
        if (serversElement == null) {
            return loaded;
        }
        if (!serversElement.isJsonObject()) {
            throw new IOException("client credential servers field is not an object");
        }
        for (Map.Entry<String, JsonElement> serverEntry
                : serversElement.getAsJsonObject().entrySet()) {
            if (!serverEntry.getValue().isJsonObject()) {
                continue;
            }
            String serverScope;
            try {
                serverScope = normalizeScope(serverEntry.getKey());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            for (Map.Entry<String, JsonElement> botEntry
                    : serverEntry.getValue().getAsJsonObject().entrySet()) {
                if (!botEntry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject value = botEntry.getValue().getAsJsonObject();
                String botName = stringField(value, "botName");
                String apiKey = stringField(value, "apiKey");
                try {
                    Credential credential = new Credential(serverScope, cleanBotName(botName), apiKey);
                    validateApiKey(credential.apiKey());
                    loaded.put(credential.id(), credential);
                } catch (IllegalArgumentException ignored) {
                    // Skip only the malformed entry. Never include its value in an exception or log.
                }
            }
        }
        return loaded;
    }

    private static JsonObject toJson(Map<CredentialId, Credential> credentials) {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        JsonObject servers = new JsonObject();
        List<Credential> ordered = new ArrayList<>(credentials.values());
        ordered.sort(Comparator.comparing(Credential::normalizedServerScope)
                .thenComparing(Credential::normalizedBotName));
        for (Credential credential : ordered) {
            JsonObject bots = servers.has(credential.serverScope())
                    ? servers.getAsJsonObject(credential.serverScope())
                    : new JsonObject();
            JsonObject value = new JsonObject();
            value.addProperty("botName", credential.botName());
            value.addProperty("apiKey", credential.apiKey());
            bots.add(credential.normalizedBotName(), value);
            servers.add(credential.serverScope(), bots);
        }
        root.add("servers", servers);
        return root;
    }

    private static String stringField(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : "";
    }

    private static Path createSecureTemporaryFile(Path directory) throws IOException {
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            FileAttribute<Set<PosixFilePermission>> permissions =
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY);
            return Files.createTempFile(directory, ".fakeaiplayer-client-", ".tmp", permissions);
        }
        return Files.createTempFile(directory, ".fakeaiplayer-client-", ".tmp");
    }

    private static void applyOwnerOnlyPermissions(Path path) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        }
    }

    private static CredentialId id(String serverScope, String botName) {
        return new CredentialId(normalizeScope(serverScope), normalizeBotName(botName));
    }

    private static String cleanScope(String value) {
        String cleaned = value == null ? "" : value.strip();
        if (cleaned.isEmpty() || cleaned.length() > MAX_SERVER_SCOPE_LENGTH) {
            throw new IllegalArgumentException("invalid server scope");
        }
        rejectControls(cleaned, "server scope");
        return cleaned;
    }

    private static String cleanBotName(String value) {
        String cleaned = value == null ? "" : value.strip();
        if (!PayloadLimits.validBotName(cleaned)) {
            throw new IllegalArgumentException("invalid bot name");
        }
        rejectControls(cleaned, "bot name");
        return cleaned;
    }

    private static void validateApiKey(String apiKey) {
        if (!PayloadLimits.validApiKey(apiKey)) {
            throw new IllegalArgumentException("invalid API credential");
        }
    }

    private static String normalizeScope(String value) {
        return cleanScope(value).toLowerCase(Locale.ROOT);
    }

    private static String normalizeBotName(String value) {
        return cleanBotName(value).toLowerCase(Locale.ROOT);
    }

    private static void rejectControls(String value, String field) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException("invalid " + field);
            }
        }
    }

    private record CredentialId(String serverScope, String botName) {
    }

    public static final class Credential {
        private final String serverScope;
        private final String botName;
        private final String apiKey;

        private Credential(String serverScope, String botName, String apiKey) {
            this.serverScope = serverScope;
            this.botName = botName;
            this.apiKey = apiKey;
        }

        public String serverScope() {
            return serverScope;
        }

        public String botName() {
            return botName;
        }

        public String apiKey() {
            return apiKey;
        }

        private CredentialId id() {
            return new CredentialId(normalizedServerScope(), normalizedBotName());
        }

        private String normalizedServerScope() {
            return serverScope.toLowerCase(Locale.ROOT);
        }

        private String normalizedBotName() {
            return botName.toLowerCase(Locale.ROOT);
        }

        private Credential copy() {
            return new Credential(serverScope, botName, apiKey);
        }

        @Override
        public String toString() {
            return "Credential[serverScope=" + serverScope + ", botName=" + botName
                    + ", apiKey=<redacted>]";
        }
    }
}
