package io.github.greytaiwolf.fakeaiplayer.building.catalog;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingSeedCodeTest {
    @Test
    void preservesLeadingZeroesAndExpandsWithoutRenumberingOldCodes() {
        BuildingSeedCode code = BuildingSeedCode.parse("0042");

        assertEquals("0042", code.value());
        assertEquals(new BigInteger("42"), code.ordinal());
        assertEquals("0043", code.next().value());
        assertEquals("9999", BuildingSeedCode.fourDigit(9999).value());
        assertEquals("10000", BuildingSeedCode.fourDigit(9999).next().value());
        assertEquals("10000", BuildingSeedCode.fromOrdinal(10_000).value());
        assertEquals("0000", BuildingSeedCode.fromOrdinal(0).value());
        assertTrue(code.isInitialFourDigitCode());
    }

    @Test
    void entropyIsDomainSeparatedAndHasAStableGoldenValue() {
        BuildingSeedCode code = BuildingSeedCode.parse("0042");

        assertEquals(-6_930_649_001_066_360_724L, code.entropy("catalog/v1/design"));
        assertEquals(7_150_677_988_083_188_664L, code.entropy("catalog/v1/archetype"));
        assertEquals(code.entropy("catalog/v1/design"), code.entropy("catalog/v1/design"));
        assertNotEquals(code.entropy("catalog/v1/design"), code.entropy("generator/layout"));
        assertNotEquals(code.entropy("catalog/v1/design"),
                BuildingSeedCode.parse("0043").entropy("catalog/v1/design"));
    }

    @Test
    void rejectsAmbiguousOrOutOfRangeForms() {
        assertThrows(IllegalArgumentException.class, () -> BuildingSeedCode.parse("42"));
        assertThrows(IllegalArgumentException.class, () -> BuildingSeedCode.parse("-042"));
        assertThrows(IllegalArgumentException.class, () -> BuildingSeedCode.parse("abcd"));
        assertThrows(IllegalArgumentException.class, () -> BuildingSeedCode.parse("00000"));
        assertThrows(IllegalArgumentException.class, () -> BuildingSeedCode.parse("09999"));
        assertThrows(IllegalArgumentException.class, () -> BuildingSeedCode.fourDigit(10_000));
        assertThrows(IllegalArgumentException.class, () -> BuildingSeedCode.fromOrdinal(-1));
        assertThrows(IllegalArgumentException.class,
                () -> BuildingSeedCode.parse("0042").entropy(" "));
    }
}
