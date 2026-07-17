package io.github.greytaiwolf.fakeaiplayer.building.catalog;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Stable public identity for a generated building design.
 *
 * <p>The initial namespace is {@code 0000..9999}. Once those codes are exhausted the canonical
 * sequence continues with {@code 10000}, {@code 10001}, and so on; an existing code is never
 * widened or renumbered. The textual form is retained exactly, so leading zeroes survive config,
 * chat and persistence round trips.</p>
 */
public record BuildingSeedCode(String value) implements Comparable<BuildingSeedCode> {
    public static final int INITIAL_WIDTH = 4;
    public static final int MAX_WIDTH = 32;
    public static final int INITIAL_CAPACITY = 10_000;

    private static final Pattern DIGITS = Pattern.compile("[0-9]{4,32}");
    private static final byte[] ENTROPY_PREFIX =
            "fakeaiplayer/building-code-entropy/v1\u0000".getBytes(StandardCharsets.UTF_8);

    public BuildingSeedCode {
        if (value == null
                || !DIGITS.matcher(value).matches()
                || (value.length() > INITIAL_WIDTH && value.charAt(0) == '0')) {
            throw new IllegalArgumentException("invalid_building_seed_code: " + value);
        }
    }

    public static BuildingSeedCode parse(String value) {
        return new BuildingSeedCode(value);
    }

    /** Returns the canonical initial-namespace code for an integer in {@code 0..9999}. */
    public static BuildingSeedCode fourDigit(int value) {
        if (value < 0 || value >= INITIAL_CAPACITY) {
            throw new IllegalArgumentException("four_digit_building_code_outside_0_9999: " + value);
        }
        return new BuildingSeedCode(String.format(Locale.ROOT, "%04d", value));
    }

    /**
     * Returns the canonical append-only sequence representation of a non-negative ordinal.
     * Ordinals below 10,000 are zero padded; later ordinals are never truncated.
     */
    public static BuildingSeedCode fromOrdinal(long ordinal) {
        if (ordinal < 0) {
            throw new IllegalArgumentException("building_code_ordinal_negative: " + ordinal);
        }
        String decimal = Long.toString(ordinal);
        if (decimal.length() < INITIAL_WIDTH) {
            decimal = "0".repeat(INITIAL_WIDTH - decimal.length()) + decimal;
        }
        return new BuildingSeedCode(decimal);
    }

    public boolean isInitialFourDigitCode() {
        return value.length() == INITIAL_WIDTH;
    }

    public BigInteger ordinal() {
        return new BigInteger(value);
    }

    /** The next canonical code; notably {@code 9999.next()} is {@code 10000}. */
    public BuildingSeedCode next() {
        BigInteger next = ordinal().add(BigInteger.ONE);
        String decimal = next.toString();
        if (decimal.length() < INITIAL_WIDTH) {
            decimal = "0".repeat(INITIAL_WIDTH - decimal.length()) + decimal;
        }
        if (decimal.length() > MAX_WIDTH) {
            throw new IllegalStateException("building_seed_code_namespace_exhausted");
        }
        return new BuildingSeedCode(decimal);
    }

    /**
     * Produces deterministic 64-bit entropy with explicit domain separation.
     *
     * <p>Using SHA-256 here prevents catalogue fields from becoming correlated and avoids JVM,
     * platform or invocation-order dependent pseudo-random state.</p>
     */
    public long entropy(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("building_entropy_domain_missing");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ENTROPY_PREFIX);
            digest.update(domain.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(value.getBytes(StandardCharsets.US_ASCII));
            return ByteBuffer.wrap(digest.digest()).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @Override
    public int compareTo(BuildingSeedCode other) {
        int ordinalComparison = ordinal().compareTo(other.ordinal());
        return ordinalComparison != 0 ? ordinalComparison : value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
