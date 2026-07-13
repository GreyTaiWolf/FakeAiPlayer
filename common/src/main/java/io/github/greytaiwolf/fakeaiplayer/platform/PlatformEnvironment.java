package io.github.greytaiwolf.fakeaiplayer.platform;

import java.nio.file.Path;

/** Loader-owned paths and metadata required by the shared runtime. */
public interface PlatformEnvironment {
    Path gameDirectory();

    Path configDirectory();

    String modVersion();

    String loaderName();
}
