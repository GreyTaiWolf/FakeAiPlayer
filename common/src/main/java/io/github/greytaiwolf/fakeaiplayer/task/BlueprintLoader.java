package io.github.greytaiwolf.fakeaiplayer.task;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.greytaiwolf.fakeaiplayer.action.MaterialPalette;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateResolver;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy;
import io.github.greytaiwolf.fakeaiplayer.persist.AtomicSnapshotFile;
import io.github.greytaiwolf.fakeaiplayer.platform.PlatformServices;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class BlueprintLoader {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Shared upper bound for a compiled, reviewed building program. */
    private static final int MAX_EXPANDED_BLOCKS = 65_536;
    private static final long MAX_EXPANSION_WORK = MAX_EXPANDED_BLOCKS * 4L;
    private static final int MAX_BLUEPRINT_DIMENSION = 128;
    private static final long MAX_BLUEPRINT_FILE_BYTES = 32L * 1024L * 1024L;
    private static final Pattern GENERATED_BLUEPRINT_NAME =
            Pattern.compile("generated_[a-z0-9][a-z0-9_-]{0,86}");

    private BlueprintLoader() {
    }

    public static BlueprintSchema load(String name) throws IOException {
        // P3 参数化蓝图:名字编码 "custom:宽x深x高:材质"(如 custom:7x5x4:stone)直接生成,不读文件。
        // 走名字通道的好处:Goal.Build/GoalStep.tag/规划器/执行器零改动,队列与备料链天然适用。
        if (name != null && name.startsWith("custom:")) {
            BlueprintSchema custom = BlueprintSchema.parametricHouse(name);
            if (custom == null) {
                throw new IOException("blueprint_bad_custom_spec: " + name + " (expect custom:WxDxH:material)");
            }
            return expand(custom);
        }
        if ("hut_5x5".equals(name) || "small_hut".equals(name)) {
            ensureDefaultBlueprintsWritten();
        }
        if (name == null || name.isBlank()) {
            throw new IOException("blueprint_name_missing");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IOException("blueprint_name_must_not_contain_path_separators: " + name);
        }
        Path directory = blueprintDir().toAbsolutePath().normalize();
        Path path;
        try {
            path = directory.resolve(name + ".json").normalize();
        } catch (InvalidPathException exception) {
            throw new IOException("blueprint_invalid_path: " + name, exception);
        }
        if (!path.startsWith(directory)) {
            throw new IOException("blueprint_path_outside_directory: " + name);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("blueprint_not_found: " + name);
        }
        if (Files.size(path) > MAX_BLUEPRINT_FILE_BYTES) {
            throw new IOException("blueprint_file_too_large: " + Files.size(path));
        }
        try (InputStream input = Files.newInputStream(
                     path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             Reader reader = new InputStreamReader(
                     new BoundedInputStream(input, MAX_BLUEPRINT_FILE_BYTES), StandardCharsets.UTF_8)) {
            BlueprintSchema schema;
            try {
                schema = GSON.fromJson(reader, BlueprintSchema.class);
            } catch (JsonParseException exception) {
                if (exception.getCause() instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("blueprint_malformed_json: " + name, exception);
            }
            if (schema == null) {
                throw new IOException("blueprint_empty: " + name);
            }
            BlueprintSchema expanded = expand(schema);
            if (expanded.placements() == null || expanded.placements().isEmpty()) {
                throw new IOException("blueprint_empty: " + name);
            }
            return expanded;
        }
    }

    /** Loads a blueprint and proves that its canonical executable content is the reviewed one. */
    public static BlueprintSchema loadVerified(String name, String expectedDigest) throws IOException {
        BlueprintSchema blueprint = load(name);
        verifyDigest(blueprint, expectedDigest);
        return blueprint;
    }

    /**
     * SHA-256 over the validated, expanded executor program, independent of JSON whitespace,
     * property insertion order and prerequisite-set iteration order.
     */
    public static String canonicalDigest(BlueprintSchema schema) throws IOException {
        BlueprintSchema expanded = expand(schema);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digestField(digest, "fakeaiplayer-executable-blueprint-v1");
            digestField(digest, expanded.name());
            digestField(digest, Integer.toString(expanded.width()));
            digestField(digest, Integer.toString(expanded.height()));
            digestField(digest, Integer.toString(expanded.depth()));
            digestField(digest, Integer.toString(expanded.placements().size()));
            for (BlueprintSchema.BlockPlacement placement : expanded.placements()) {
                digestField(digest, Integer.toString(placement.dx()));
                digestField(digest, Integer.toString(placement.dy()));
                digestField(digest, Integer.toString(placement.dz()));
                digestField(digest, placement.blockId());
                digestField(digest, placement.palette());
                digestField(digest, Integer.toString(placement.properties().size()));
                for (Map.Entry<String, String> property : placement.properties().entrySet()) {
                    digestField(digest, property.getKey());
                    digestField(digest, property.getValue());
                }
                digestField(digest, placement.operation().name());
                digestField(digest, placement.replacePolicy().name());
                digestField(digest, placement.atomicGroup());
                digestField(digest, placement.sequence() == null
                        ? null : Integer.toString(placement.sequence()));
                digestField(digest, Integer.toString(placement.prerequisites().size()));
                for (Integer prerequisite : placement.prerequisites().stream().sorted().toList()) {
                    digestField(digest, Integer.toString(prerequisite));
                }
                // Preserve every legacy digest while binding the new terrain-support contract
                // whenever a reviewed building actually needs it.
                if (placement.requiresExternalSupport()) {
                    digestField(digest, "requires_external_support");
                }
                digestField(digest, "end_placement");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }

    public static void verifyDigest(BlueprintSchema schema, String expectedDigest) throws IOException {
        if (expectedDigest == null || !expectedDigest.matches("[0-9a-f]{64}")) {
            throw new IOException("blueprint_digest_missing_or_invalid");
        }
        String actualDigest = canonicalDigest(schema);
        if (!MessageDigest.isEqual(
                actualDigest.getBytes(StandardCharsets.US_ASCII),
                expectedDigest.getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("blueprint_digest_mismatch: expected="
                    + expectedDigest + " actual=" + actualDigest);
        }
    }

    public static boolean isGeneratedName(String name) {
        return name != null && GENERATED_BLUEPRINT_NAME.matcher(name).matches();
    }

    /**
     * Persist a server-generated, already reviewed blueprint for the normal Goal/mission pipeline.
     *
     * <p>The strict generated-only namespace deliberately excludes path separators, dots and
     * user-controlled Unicode. The schema is expanded and validated before any write, the encoded
     * document must fit the same bound as the loader, and an existing symbolic link is never
     * followed. The durable temporary-file replacement is shared with mission snapshots. A name
     * becomes immutable after its first write: identical retries are accepted, while different
     * content fails rather than changing an active mission underneath the executor.</p>
     *
     * @return the unchanged safe name, ready to be combined with the confirmed anchor, dimension
     *         and canonical digest in a {@code Goal.Build}
     */
    public static String saveGenerated(String name, BlueprintSchema schema) throws IOException {
        saveGeneratedToDirectory(blueprintDir(), name, schema);
        return name;
    }

    static synchronized Path saveGeneratedToDirectory(Path requestedDirectory,
                                                      String name,
                                                      BlueprintSchema schema) throws IOException {
        validateGeneratedName(name);
        if (requestedDirectory == null) {
            throw new IOException("blueprint_directory_missing");
        }
        BlueprintSchema expanded = expand(schema);
        String json = GSON.toJson(expanded);
        byte[] encoded = json.getBytes(StandardCharsets.UTF_8);
        long encodedBytes = encoded.length;
        if (encodedBytes > MAX_BLUEPRINT_FILE_BYTES) {
            throw new IOException("blueprint_file_too_large: " + encodedBytes);
        }

        Path directory;
        try {
            directory = requestedDirectory.toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new IOException("blueprint_invalid_directory", exception);
        }
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("blueprint_directory_not_real_directory: " + directory);
        }
        Path target = directory.resolve(name + ".json").normalize();
        if (!target.startsWith(directory)) {
            throw new IOException("blueprint_path_outside_directory: " + name);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("blueprint_target_not_regular_file: " + name);
        }
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            long currentBytes = Files.size(target);
            if (currentBytes > MAX_BLUEPRINT_FILE_BYTES
                    || currentBytes != encodedBytes
                    || !Arrays.equals(readAllBytesNoFollow(target), encoded)) {
                // Generated files can be referenced by active or queued persistent goals. Never
                // silently change the meaning of an existing name underneath such a mission.
                throw new IOException("generated_blueprint_name_collision: " + name);
            }
            verifyGeneratedReadBack(target, name, canonicalDigest(expanded));
            return target; // Idempotent retry after a lost acknowledgement or server restart.
        }
        AtomicSnapshotFile.write(target, json);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.size(target) > MAX_BLUEPRINT_FILE_BYTES) {
            throw new IOException("blueprint_generated_write_invalid: " + name);
        }
        verifyGeneratedReadBack(target, name, canonicalDigest(expanded));
        return target;
    }

    private static void verifyGeneratedReadBack(Path target,
                                                String name,
                                                String expectedDigest) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.size(target) > MAX_BLUEPRINT_FILE_BYTES) {
            throw new IOException("blueprint_generated_readback_invalid: " + name);
        }
        try (InputStream input = Files.newInputStream(
                     target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             Reader reader = new InputStreamReader(
                     new BoundedInputStream(input, MAX_BLUEPRINT_FILE_BYTES), StandardCharsets.UTF_8)) {
            BlueprintSchema readBack;
            try {
                readBack = GSON.fromJson(reader, BlueprintSchema.class);
            } catch (JsonParseException exception) {
                throw new IOException("blueprint_generated_readback_malformed: " + name, exception);
            }
            if (readBack == null) {
                throw new IOException("blueprint_generated_readback_empty: " + name);
            }
            verifyDigest(readBack, expectedDigest);
        }
    }

    private static byte[] readAllBytesNoFollow(Path target) throws IOException {
        try (InputStream input = Files.newInputStream(
                target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return new BoundedInputStream(input, MAX_BLUEPRINT_FILE_BYTES).readAllBytes();
        }
    }

    private static void digestField(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(new byte[]{-1, -1, -1, -1});
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static void validateGeneratedName(String name) throws IOException {
        if (name == null || !GENERATED_BLUEPRINT_NAME.matcher(name).matches()) {
            throw new IOException("invalid_generated_blueprint_name: " + name);
        }
    }

    public static BlueprintSchema expand(BlueprintSchema schema) throws IOException {
        validateSchema(schema);
        Map<Key, BlueprintSchema.BlockPlacement> placements = new LinkedHashMap<>();
        if (schema.ops() != null && schema.ops().size() > MAX_EXPANDED_BLOCKS) {
            throw new IOException("too_many_blueprint_ops: " + schema.ops().size());
        }
        if (schema.placements() != null && schema.placements().size() > MAX_EXPANDED_BLOCKS) {
            throw new IOException("too_many_blueprint_placements: " + schema.placements().size());
        }
        boolean sourceHasSequence = schema.placements() != null
                && schema.placements().stream()
                        .filter(java.util.Objects::nonNull)
                        .anyMatch(placement -> placement.sequence() != null);
        if (sourceHasSequence && schema.ops() != null && !schema.ops().isEmpty()) {
            // Op expansion has no dependency order. Letting sequenced explicit cells override all
            // or part of it can produce an apparently complete but different program.
            throw new IOException("blueprint_sequence_cannot_mix_with_ops");
        }
        long expansionWork = 0L;
        if (schema.ops() != null) {
            for (BlueprintSchema.Op op : schema.ops()) {
                expansionWork += validateOp(schema, op);
                if (expansionWork > MAX_EXPANSION_WORK) {
                    throw new IOException("blueprint_expansion_work_too_large: " + expansionWork);
                }
                for (BlueprintSchema.BlockPlacement placement : expandOp(op)) {
                    put(placements, placement);
                }
            }
        }
        if (schema.placements() != null) {
            Set<Key> sequencedCoordinates = new HashSet<>();
            for (BlueprintSchema.BlockPlacement placement : schema.placements()) {
                validatePlacement(schema, placement);
                Key key = new Key(placement.dx(), placement.dy(), placement.dz());
                if (placement.sequence() != null && !sequencedCoordinates.add(key)) {
                    throw new IOException("blueprint_sequenced_coordinate_duplicate: "
                            + placement.dx() + "," + placement.dy() + "," + placement.dz());
                }
                put(placements, placement);
            }
        }
        if (placements.size() > MAX_EXPANDED_BLOCKS) {
            throw new IOException("blueprint_too_large: " + placements.size());
        }
        for (BlueprintSchema.BlockPlacement placement : placements.values()) {
            validatePlacement(schema, placement);
        }
        List<BlueprintSchema.BlockPlacement> sorted = new ArrayList<>(placements.values());
        boolean sequenced = validateSequence(sorted);
        if (sequenced) {
            sorted.sort(Comparator.comparingInt(BlueprintSchema.BlockPlacement::sequence));
        } else {
            sorted.sort(Comparator
                    .comparingInt(BlueprintSchema.BlockPlacement::dy)
                    .thenComparingInt(BlueprintSchema.BlockPlacement::dx)
                    .thenComparingInt(BlueprintSchema.BlockPlacement::dz));
        }
        validateAtomicGroups(sorted);
        return new BlueprintSchema(
                schema.name(),
                schema.width(),
                schema.height(),
                schema.depth(),
                List.copyOf(sorted),
                List.of());
    }

    private static void put(Map<Key, BlueprintSchema.BlockPlacement> placements,
                            BlueprintSchema.BlockPlacement placement) throws IOException {
        Key key = new Key(placement.dx(), placement.dy(), placement.dz());
        placements.remove(key);
        placements.put(key, placement);
        if (placements.size() > MAX_EXPANDED_BLOCKS) {
            throw new IOException("blueprint_too_large: " + placements.size());
        }
    }

    private static List<BlueprintSchema.BlockPlacement> expandOp(BlueprintSchema.Op op) throws IOException {
        if (op.type() == null || op.from() == null || op.to() == null || op.from().length < 3 || op.to().length < 3) {
            throw new IOException("bad_blueprint_op");
        }
        int minX = Math.min(op.from()[0], op.to()[0]);
        int minY = Math.min(op.from()[1], op.to()[1]);
        int minZ = Math.min(op.from()[2], op.to()[2]);
        int maxX = Math.max(op.from()[0], op.to()[0]);
        int maxY = Math.max(op.from()[1], op.to()[1]);
        int maxZ = Math.max(op.from()[2], op.to()[2]);
        String block = op.block() == null || op.block().isBlank() ? fallbackBlock(op.palette()) : op.block();
        List<BlueprintSchema.BlockPlacement> placements = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!includes(op.type(), x, y, z, minX, minY, minZ, maxX, maxY, maxZ)) {
                        continue;
                    }
                    placements.add(new BlueprintSchema.BlockPlacement(
                            x, y, z, block, op.palette(), op.properties()));
                }
            }
        }
        return placements;
    }

    private static boolean includes(String type, int x, int y, int z, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) throws IOException {
        return switch (type) {
            case "box", "fill", "layer" -> true;
            case "hollow_box" -> x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
            default -> throw new IOException("unknown_blueprint_op: " + type);
        };
    }

    private static String fallbackBlock(String palette) {
        if (palette == null || palette.isBlank()) {
            return "minecraft:air";
        }
        return switch (palette) {
            case "planks" -> "minecraft:oak_planks";
            case "logs" -> "minecraft:oak_log";
            case "stone_like" -> "minecraft:cobblestone";
            case "dirt_like" -> "minecraft:dirt";
            case "glass" -> "minecraft:glass";
            default -> "minecraft:air";
        };
    }

    private static void validateSchema(BlueprintSchema schema) throws IOException {
        if (schema == null) {
            throw new IOException("blueprint_missing");
        }
        if (schema.name() == null || schema.name().isBlank()) {
            throw new IOException("blueprint_name_missing");
        }
        if (schema.width() < 1 || schema.width() > MAX_BLUEPRINT_DIMENSION
                || schema.height() < 1 || schema.height() > MAX_BLUEPRINT_DIMENSION
                || schema.depth() < 1 || schema.depth() > MAX_BLUEPRINT_DIMENSION) {
            throw new IOException("blueprint_invalid_dimensions: "
                    + schema.width() + "x" + schema.height() + "x" + schema.depth());
        }
        long flattenWork = (long) schema.width() * schema.depth() * (schema.height() + 1L);
        if (flattenWork > MAX_EXPANSION_WORK) {
            throw new IOException("blueprint_site_work_too_large: " + flattenWork);
        }
    }

    private static long validateOp(BlueprintSchema schema, BlueprintSchema.Op op) throws IOException {
        if (op == null || op.type() == null || op.from() == null || op.to() == null
                || op.from().length < 3 || op.to().length < 3) {
            throw new IOException("bad_blueprint_op");
        }
        if (!List.of("box", "fill", "layer", "hollow_box").contains(op.type())) {
            throw new IOException("unknown_blueprint_op: " + op.type());
        }
        int[] from = op.from();
        int[] to = op.to();
        int minX = Math.min(from[0], to[0]);
        int minY = Math.min(from[1], to[1]);
        int minZ = Math.min(from[2], to[2]);
        int maxX = Math.max(from[0], to[0]);
        int maxY = Math.max(from[1], to[1]);
        int maxZ = Math.max(from[2], to[2]);
        if (minX < 0 || maxX >= schema.width()
                || minY < 0 || maxY >= schema.height()
                || minZ < 0 || maxZ >= schema.depth()) {
            throw new IOException("blueprint_op_out_of_bounds: "
                    + minX + "," + minY + "," + minZ + ".." + maxX + "," + maxY + "," + maxZ);
        }
        long volume = (long) (maxX - minX + 1)
                * (maxY - minY + 1)
                * (maxZ - minZ + 1);
        if (volume > MAX_EXPANDED_BLOCKS) {
            throw new IOException("blueprint_op_too_large: " + volume);
        }
        String block = op.block() == null || op.block().isBlank() ? fallbackBlock(op.palette()) : op.block();
        validateState(block, op.palette(), op.properties(), "invalid_blueprint_op_state");
        return volume;
    }

    private static void validatePlacement(BlueprintSchema schema,
                                          BlueprintSchema.BlockPlacement placement) throws IOException {
        if (placement == null || placement.blockId() == null || placement.blockId().isBlank()) {
            throw new IOException("blueprint_placement_missing_block");
        }
        if (placement.dx() < 0 || placement.dx() >= schema.width()
                || placement.dy() < 0 || placement.dy() >= schema.height()
                || placement.dz() < 0 || placement.dz() >= schema.depth()) {
            throw new IOException("blueprint_placement_out_of_bounds: "
                    + placement.dx() + "," + placement.dy() + "," + placement.dz());
        }
        BlockState state = validateState(placement.blockId(), placement.palette(), placement.properties(),
                "invalid_blueprint_block_state");
        validateOperation(placement, state);
        if (placement.atomicGroup().length() > 128
                || placement.atomicGroup().codePoints().anyMatch(Character::isISOControl)) {
            throw new IOException("blueprint_invalid_atomic_group: " + placement.atomicGroup());
        }
        if (placement.prerequisites().size() > MAX_EXPANDED_BLOCKS) {
            throw new IOException("blueprint_too_many_prerequisites: "
                    + placement.prerequisites().size());
        }
        if (placement.sequence() == null && !placement.prerequisites().isEmpty()) {
            throw new IOException("blueprint_prerequisites_require_sequence");
        }
        if (placement.requiresExternalSupport()
                && placement.operation() != CellOperation.PLACE) {
            throw new IOException("blueprint_external_support_requires_place");
        }
    }

    private static BlockState validateState(String blockId,
                                            String palette,
                                            Map<String, String> properties,
                                            String errorCode) throws IOException {
        if (palette != null && !palette.isBlank() && !MaterialPalette.isKnown(palette)) {
            throw new IOException("unknown_palette: " + palette);
        }
        BlockState resolved;
        try {
            resolved = BlockStateResolver.resolve(new BlockStateSpec(blockId, properties));
        } catch (IllegalArgumentException exception) {
            throw new IOException(errorCode + ": " + exception.getMessage(), exception);
        }
        if (palette == null || palette.isBlank()) {
            return resolved;
        }
        if (!MaterialPalette.matchesBlock(resolved, palette)) {
            throw new IOException("blueprint_palette_block_mismatch: " + palette + " != " + blockId);
        }
        for (var item : MaterialPalette.GROUPS.get(palette)) {
            if (!(item instanceof BlockItem blockItem)) {
                continue;
            }
            try {
                BlockStateResolver.applyProperties(blockItem.getBlock().defaultBlockState(), properties);
            } catch (IllegalArgumentException exception) {
                throw new IOException("blueprint_palette_property_unsupported: "
                        + palette + ":" + item + ":" + exception.getMessage(), exception);
            }
        }
        return resolved;
    }

    private static void validateOperation(BlueprintSchema.BlockPlacement placement,
                                          BlockState state) throws IOException {
        CellOperation operation = placement.operation();
        ReplacePolicy policy = placement.replacePolicy();
        if (operation == null || policy == null) {
            throw new IOException("blueprint_operation_or_policy_missing");
        }
        if (operation == CellOperation.CLEAR && !state.isAir()) {
            throw new IOException("blueprint_clear_requires_air_state: " + placement.blockId());
        }
        if ((operation == CellOperation.PLACE || operation == CellOperation.TEMPORARY) && state.isAir()) {
            throw new IOException("blueprint_place_requires_non_air_state: " + placement.blockId());
        }
        boolean policyAllowed = switch (operation) {
            case PLACE -> policy == ReplacePolicy.REQUIRE_EMPTY
                    || policy == ReplacePolicy.REPLACE_REPLACEABLE
                    || policy == ReplacePolicy.REPLACE_NATURAL
                    || policy == ReplacePolicy.FORCE_AUTHORIZED;
            case CLEAR -> policy == ReplacePolicy.CLEAR_AUTHORIZED
                    || policy == ReplacePolicy.FORCE_AUTHORIZED;
            case PRESERVE -> policy == ReplacePolicy.PRESERVE_EXISTING;
            case TEMPORARY -> policy == ReplacePolicy.REQUIRE_EMPTY
                    || policy == ReplacePolicy.REPLACE_REPLACEABLE;
        };
        if (!policyAllowed) {
            throw new IOException("blueprint_operation_policy_mismatch: "
                    + operation + "+" + policy);
        }
        // These are valid plan-level concepts, but this executor has neither an operator
        // authorization token nor a cleanup phase. Reject instead of silently treating them as
        // ordinary PLACE cells.
        if (policy == ReplacePolicy.FORCE_AUTHORIZED) {
            throw new IOException("blueprint_force_policy_requires_authorized_executor");
        }
        if (operation == CellOperation.TEMPORARY) {
            throw new IOException("blueprint_temporary_requires_cleanup_executor");
        }
    }

    /**
     * Goal planning counts one inventory item per atomic group, while execution still verifies
     * every cell. Until a richer multi-item adapter exists, every member therefore has to describe
     * the same PLACE item and the same replacement contract. State properties may differ (for
     * example a door's lower/upper halves).
     */
    private static void validateAtomicGroups(List<BlueprintSchema.BlockPlacement> placements) throws IOException {
        Map<String, List<BlueprintSchema.BlockPlacement>> groups = new LinkedHashMap<>();
        for (BlueprintSchema.BlockPlacement placement : placements) {
            if (!placement.atomicGroup().isBlank()) {
                groups.computeIfAbsent(placement.atomicGroup(), ignored -> new ArrayList<>()).add(placement);
            }
        }
        for (Map.Entry<String, List<BlueprintSchema.BlockPlacement>> groupEntry : groups.entrySet()) {
            String group = groupEntry.getKey();
            List<BlueprintSchema.BlockPlacement> members = groupEntry.getValue();
            BlueprintSchema.BlockPlacement first = members.get(0);
            for (BlueprintSchema.BlockPlacement placement : members) {
                if (placement.operation() != CellOperation.PLACE) {
                    throw new IOException("blueprint_atomic_group_requires_place: " + group);
                }
                if (!first.blockId().equals(placement.blockId())) {
                    throw new IOException("blueprint_atomic_group_block_mismatch: " + group);
                }
                String firstPalette = first.palette() == null ? "" : first.palette();
                String palette = placement.palette() == null ? "" : placement.palette();
                if (!firstPalette.equals(palette)) {
                    throw new IOException("blueprint_atomic_group_palette_mismatch: " + group);
                }
                if (first.replacePolicy() != placement.replacePolicy()) {
                    throw new IOException("blueprint_atomic_group_policy_mismatch: " + group);
                }
                BlockState state = BlockStateResolver.resolve(
                        new BlockStateSpec(placement.blockId(), placement.properties()));
                if (!(state.getBlock() instanceof DoorBlock)) {
                    throw new IOException("blueprint_unsupported_atomic_group_block: " + group);
                }
            }
            if (members.size() != 2) {
                throw new IOException("blueprint_door_atomic_group_size: " + group);
            }
            BlueprintSchema.BlockPlacement lower = memberWithProperty(members, "half", "lower");
            BlueprintSchema.BlockPlacement upper = memberWithProperty(members, "half", "upper");
            if (lower == null || upper == null) {
                throw new IOException("blueprint_door_atomic_group_halves: " + group);
            }
            if (upper.dx() != lower.dx()
                    || upper.dy() != lower.dy() + 1
                    || upper.dz() != lower.dz()) {
                throw new IOException("blueprint_door_atomic_group_footprint: " + group);
            }
            if (members.indexOf(lower) > members.indexOf(upper)) {
                throw new IOException("blueprint_door_atomic_group_order: " + group);
            }
            for (String property : List.of("facing", "hinge", "open", "powered")) {
                String lowerValue = lower.properties().getOrDefault(property, "");
                String upperValue = upper.properties().getOrDefault(property, "");
                if (!lowerValue.equals(upperValue)) {
                    throw new IOException("blueprint_door_atomic_group_state_mismatch: "
                            + group + ":" + property);
                }
            }
        }
    }

    private static BlueprintSchema.BlockPlacement memberWithProperty(
            List<BlueprintSchema.BlockPlacement> members,
            String property,
            String expected) {
        for (BlueprintSchema.BlockPlacement member : members) {
            if (expected.equals(member.properties().get(property))) {
                return member;
            }
        }
        return null;
    }

    /**
     * Legacy files omit sequence entirely. V2 files must provide a complete, unique order;
     * accepting a partially serialized order would reconstruct a different building program.
     */
    private static boolean validateSequence(List<BlueprintSchema.BlockPlacement> placements) throws IOException {
        int sequenced = 0;
        Set<Integer> seen = new HashSet<>();
        for (BlueprintSchema.BlockPlacement placement : placements) {
            Integer sequence = placement.sequence();
            if (sequence == null) {
                continue;
            }
            sequenced++;
            if (sequence < 0) {
                throw new IOException("blueprint_sequence_negative: " + sequence);
            }
            if (!seen.add(sequence)) {
                throw new IOException("blueprint_sequence_duplicate: " + sequence);
            }
        }
        if (sequenced != 0 && sequenced != placements.size()) {
            throw new IOException("blueprint_sequence_mixed: " + sequenced + "/" + placements.size());
        }
        if (sequenced != 0) {
            for (int expected = 0; expected < placements.size(); expected++) {
                if (!seen.contains(expected)) {
                    throw new IOException("blueprint_sequence_not_contiguous: missing " + expected);
                }
            }
            for (BlueprintSchema.BlockPlacement placement : placements) {
                for (Integer prerequisite : placement.prerequisites()) {
                    if (!seen.contains(prerequisite)) {
                        throw new IOException("blueprint_prerequisite_missing: " + prerequisite);
                    }
                    if (prerequisite >= placement.sequence()) {
                        throw new IOException("blueprint_prerequisite_not_prior: "
                                + prerequisite + ">=" + placement.sequence());
                    }
                }
            }
        }
        return sequenced != 0;
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long count;

        private BoundedInputStream(InputStream input, long maxBytes) {
            super(input);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++count > maxBytes) {
                throw new IOException("blueprint_file_too_large_while_reading: " + count);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int allowed = (int) Math.min(length, Math.max(1L, maxBytes - count + 1L));
            int read = super.read(bytes, offset, allowed);
            if (read > 0 && (count += read) > maxBytes) {
                throw new IOException("blueprint_file_too_large_while_reading: " + count);
            }
            return read;
        }

        @Override
        public long skip(long requested) throws IOException {
            long allowed = Math.min(requested, Math.max(1L, maxBytes - count + 1L));
            long skipped = super.skip(allowed);
            if (skipped > 0 && (count += skipped) > maxBytes) {
                throw new IOException("blueprint_file_too_large_while_reading: " + count);
            }
            return skipped;
        }
    }

    private static void ensureDefaultBlueprintsWritten() throws IOException {
        writeIfMissing("hut_5x5.json", BlueprintSchema.hut5x5());
        writeIfMissing("small_hut.json", BlueprintSchema.smallHutOps());
    }

    private static void writeIfMissing(String fileName, BlueprintSchema schema) throws IOException {
        Path path = blueprintDir().resolve(fileName);
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(schema, writer);
            }
        }
    }

    private static Path blueprintDir() {
        return PlatformServices.gameDirectory().resolve("blueprints");
    }

    private record Key(int x, int y, int z) {
    }
}
