package io.github.greytaiwolf.fakeaiplayer.platform;

import java.nio.file.Path;
import java.util.Objects;

/** Fail-closed holder initialized exactly once by Fabric or NeoForge. */
public final class PlatformServices {
    private static volatile PlatformEnvironment environment;

    private PlatformServices() {
    }

    public static synchronized void initialize(PlatformEnvironment value) {
        Objects.requireNonNull(value, "value");
        if (environment == null) {
            environment = value;
        }
    }

    public static Path gameDirectory() {
        return get().gameDirectory();
    }

    public static Path configDirectory() {
        return get().configDirectory();
    }

    public static String modVersion() {
        return get().modVersion();
    }

    public static String loaderName() {
        return get().loaderName();
    }

    private static PlatformEnvironment get() {
        PlatformEnvironment value = environment;
        if (value == null) {
            throw new IllegalStateException("PlatformServices has not been initialized");
        }
        return value;
    }
}
