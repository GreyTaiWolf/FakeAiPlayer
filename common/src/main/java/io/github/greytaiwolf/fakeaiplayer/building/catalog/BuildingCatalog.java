package io.github.greytaiwolf.fakeaiplayer.building.catalog;

import io.github.greytaiwolf.fakeaiplayer.building.generator.BuildingRoofType;
import io.github.greytaiwolf.fakeaiplayer.building.generator.MultiStoreyBuildingGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Versioned, algorithmic catalogue for public building codes.
 *
 * <p>Version 1 deliberately uses fixed archetype and style slots rather than indexing a mutable
 * registry. New content therefore gets a new catalogue version or unused codes; it cannot silently
 * remap a code that a player has already shared.</p>
 */
public final class BuildingCatalog {
    public static final String V1_CATALOG_VERSION = "building-catalog-1";
    public static final String CATALOG_VERSION = V1_CATALOG_VERSION;

    private static final List<BuildingArchetype> ARCHETYPE_SLOTS = List.of(
            BuildingArchetype.TOWNHOUSE,
            BuildingArchetype.MANOR,
            BuildingArchetype.KEEP,
            BuildingArchetype.LODGE,
            BuildingArchetype.APARTMENT);

    private BuildingCatalog() {
    }

    public static BuildingCatalogEntry resolve(String code) {
        return resolve(BuildingSeedCode.parse(code));
    }

    public static BuildingCatalogEntry resolve(BuildingSeedCode code) {
        return resolve(code, CATALOG_VERSION);
    }

    /** Resolves a code through an explicit frozen historical catalogue implementation. */
    public static BuildingCatalogEntry resolve(BuildingSeedCode code, String catalogVersion) {
        if (code == null) {
            throw new IllegalArgumentException("building_seed_code_missing");
        }
        if (!V1_CATALOG_VERSION.equals(catalogVersion)) {
            throw new IllegalArgumentException(
                    "unsupported_building_catalog_version: " + catalogVersion);
        }
        return resolveV1(code);
    }

    private static BuildingCatalogEntry resolveV1(BuildingSeedCode code) {
        BuildingArchetype archetype = ARCHETYPE_SLOTS.get(choice(code, "archetype", ARCHETYPE_SLOTS.size()));
        Profile profile = profile(archetype, code);
        return new BuildingCatalogEntry(
                code,
                code.entropy("catalog/v1/design"),
                archetype,
                profile.styleId,
                ranged(code, "width", profile.minimumWidth, profile.maximumWidth),
                ranged(code, "depth", profile.minimumDepth, profile.maximumDepth),
                ranged(code, "floors", profile.minimumFloors, profile.maximumFloors),
                profile.roofType,
                V1_CATALOG_VERSION,
                MultiStoreyBuildingGenerator.GENERATOR_VERSION);
    }

    /** Lazily enumerates the original four-digit namespace in numeric order. */
    public static Stream<BuildingCatalogEntry> initialEntries() {
        return IntStream.range(0, BuildingSeedCode.INITIAL_CAPACITY)
                .mapToObj(BuildingSeedCode::fourDigit)
                .map(BuildingCatalog::resolve);
    }

    /**
     * Compact golden manifest for all original codes. A deliberate v1 content change must update
     * this value's test and therefore cannot silently remap a code during routine refactoring.
     */
    public static String initialNamespaceManifestSha256(String catalogVersion) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            manifestField(digest, "fakeaiplayer-building-catalog-manifest-v1");
            manifestField(digest, catalogVersion);
            for (int ordinal = 0; ordinal < BuildingSeedCode.INITIAL_CAPACITY; ordinal++) {
                BuildingCatalogEntry entry = resolve(
                        BuildingSeedCode.fourDigit(ordinal), catalogVersion);
                manifestField(digest, entry.seedCode().value());
                manifestField(digest, Long.toUnsignedString(entry.entropy()));
                manifestField(digest, entry.archetype().id());
                manifestField(digest, entry.styleId());
                manifestField(digest, Integer.toString(entry.width()));
                manifestField(digest, Integer.toString(entry.depth()));
                manifestField(digest, Integer.toString(entry.floors()));
                manifestField(digest, entry.roofType().id());
                manifestField(digest, entry.generatorVersion());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void manifestField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static Profile profile(BuildingArchetype archetype, BuildingSeedCode code) {
        return switch (archetype) {
            case TOWNHOUSE -> new Profile(
                    choose(code, "townhouse/style", "birch_townhouse", "oak_cottage"),
                    11, 24, 15, 36, 3, 8,
                    chooseRoof(code, "townhouse/roof"));
            case MANOR -> new Profile(
                    choose(code, "manor/style", "dark_oak_manor", "spruce_lodge"),
                    21, 48, 17, 48, 2, 5,
                    BuildingRoofType.STEPPED_GABLE);
            case KEEP -> new Profile(
                    "stone_keep",
                    17, 44, 17, 44, 3, 7,
                    chooseRoof(code, "keep/roof"));
            case LODGE -> new Profile(
                    choose(code, "lodge/style", "spruce_lodge", "dark_oak_manor"),
                    15, 36, 13, 36, 2, 5,
                    BuildingRoofType.STEPPED_GABLE);
            case APARTMENT -> new Profile(
                    choose(code, "apartment/style", "birch_townhouse", "stone_keep", "oak_cottage"),
                    19, 48, 17, 48, 4, 8,
                    BuildingRoofType.FLAT_PARAPET);
        };
    }

    private static BuildingRoofType chooseRoof(BuildingSeedCode code, String domain) {
        return choice(code, domain, 3) == 0
                ? BuildingRoofType.FLAT_PARAPET
                : BuildingRoofType.STEPPED_GABLE;
    }

    private static String choose(BuildingSeedCode code, String domain, String... values) {
        return values[choice(code, domain, values.length)];
    }

    private static int ranged(BuildingSeedCode code, String domain, int minimum, int maximum) {
        return minimum + choice(code, domain, maximum - minimum + 1);
    }

    private static int choice(BuildingSeedCode code, String domain, int bound) {
        return (int) Long.remainderUnsigned(code.entropy("catalog/v1/" + domain), bound);
    }

    private record Profile(
            String styleId,
            int minimumWidth,
            int maximumWidth,
            int minimumDepth,
            int maximumDepth,
            int minimumFloors,
            int maximumFloors,
            BuildingRoofType roofType
    ) {
    }
}
