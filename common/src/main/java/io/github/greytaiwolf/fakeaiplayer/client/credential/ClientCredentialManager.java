package io.github.greytaiwolf.fakeaiplayer.client.credential;

import io.github.greytaiwolf.fakeaiplayer.client.ClientNetworkServices;
import io.github.greytaiwolf.fakeaiplayer.network.payload.RestoreBotAiCredentialC2S;
import io.github.greytaiwolf.fakeaiplayer.platform.PlatformServices;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.storage.LevelResource;

/** Client-only lifecycle and error boundary around the pure Java credential store. */
public final class ClientCredentialManager {
    private static final int RESTORE_DELAY_TICKS = 40;
    public enum StorageResult {
        OK,
        INVALID,
        UNAVAILABLE
    }

    private static ClientCredentialStore store;
    private static boolean storeLoadAttempted;
    private static ClientPacketListener activeConnection;
    private static String activeServerScope = "";
    private static boolean restoredForConnection;
    private static int connectionTicks;

    private ClientCredentialManager() {
    }

    /** Called from the normal client tick. A saved token is restored at most once per connection. */
    public static synchronized void tick(Minecraft client) {
        ClientPacketListener connection = client.getConnection();
        if (connection != activeConnection) {
            activeConnection = connection;
            activeServerScope = "";
            restoredForConnection = false;
            connectionTicks = 0;
        }
        if (connection == null) {
            return;
        }
        connectionTicks++;
        String scope = resolveServerScope(client);
        if (!scope.isBlank()) {
            activeServerScope = scope;
        }
        if (restoredForConnection || connectionTicks < RESTORE_DELAY_TICKS
                || client.player == null || activeServerScope.isBlank()
                || !ClientNetworkServices.canSend(RestoreBotAiCredentialC2S.ID)) {
            return;
        }
        for (ClientCredentialStore.Credential credential : credentials(activeServerScope)) {
            ClientNetworkServices.send(
                    new RestoreBotAiCredentialC2S(credential.botName(), credential.apiKey()));
        }
        restoredForConnection = true;
    }

    public static synchronized String currentServerScope(Minecraft client) {
        String resolved = resolveServerScope(client);
        if (!resolved.isBlank()) {
            activeServerScope = resolved;
            return resolved;
        }
        return client.getConnection() == activeConnection ? activeServerScope : "";
    }

    public static synchronized Optional<String> find(String serverScope, String botName) {
        ClientCredentialStore value = store();
        if (value == null) {
            return Optional.empty();
        }
        try {
            return value.find(serverScope, botName);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public static synchronized StorageResult save(String serverScope, String botName, String apiKey) {
        ClientCredentialStore value = store();
        if (value == null) {
            return StorageResult.UNAVAILABLE;
        }
        try {
            value.put(serverScope, botName, apiKey);
            return StorageResult.OK;
        } catch (IllegalArgumentException invalid) {
            return StorageResult.INVALID;
        } catch (IOException failed) {
            return StorageResult.UNAVAILABLE;
        }
    }

    public static synchronized StorageResult forget(String serverScope, String botName) {
        ClientCredentialStore value = store();
        if (value == null) {
            return StorageResult.UNAVAILABLE;
        }
        try {
            value.remove(serverScope, botName);
            return StorageResult.OK;
        } catch (IllegalArgumentException invalid) {
            return StorageResult.INVALID;
        } catch (IOException failed) {
            return StorageResult.UNAVAILABLE;
        }
    }

    private static List<ClientCredentialStore.Credential> credentials(String serverScope) {
        ClientCredentialStore value = store();
        if (value == null) {
            return List.of();
        }
        try {
            return value.forServer(serverScope);
        } catch (IllegalArgumentException invalid) {
            return List.of();
        }
    }

    private static ClientCredentialStore store() {
        if (!storeLoadAttempted) {
            storeLoadAttempted = true;
            try {
                store = new ClientCredentialStore(
                        PlatformServices.configDirectory().resolve(ClientCredentialStore.FILE_NAME));
            } catch (IOException | RuntimeException unavailable) {
                // Fail closed and surface only a fixed local UI status. Never log exception values.
                store = null;
            }
        }
        return store;
    }

    private static String resolveServerScope(Minecraft client) {
        if (client == null || client.getConnection() == null) {
            return "";
        }
        java.util.UUID profileId = client.getUser().getProfileId();
        if (profileId == null) {
            return "";
        }
        String account = profileId.toString().toLowerCase(Locale.ROOT);
        if (client.hasSingleplayerServer()) {
            String worldIdentity = client.getSingleplayerServer() == null
                    ? "local"
                    : sha256(client.getSingleplayerServer()
                            .getWorldPath(LevelResource.ROOT)
                            .toAbsolutePath()
                            .normalize()
                            .toString());
            return scopedToAccount(account, cleanScopePart("singleplayer", worldIdentity));
        }
        ServerData server = client.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) {
            return "";
        }
        return scopedToAccount(account, cleanScopePart("server", server.ip));
    }

    private static String scopedToAccount(String account, String server) {
        if (account == null || account.isBlank() || server.isBlank()) {
            return "";
        }
        return "account:" + account + "|" + server;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String cleanScopePart(String kind, String value) {
        String cleaned = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (cleaned.isEmpty() || cleaned.length() > 480) {
            return "";
        }
        for (int index = 0; index < cleaned.length(); index++) {
            if (Character.isISOControl(cleaned.charAt(index))) {
                return "";
            }
        }
        return kind + ":" + cleaned;
    }
}
