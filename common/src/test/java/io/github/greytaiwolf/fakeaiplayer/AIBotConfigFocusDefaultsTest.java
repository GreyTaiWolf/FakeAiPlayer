package io.github.greytaiwolf.fakeaiplayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIBotConfigFocusDefaultsTest {
    @Test
    void missingLegacyFocusFieldsMergeToEnabledDefaults() {
        AIBotConfig.Focus defaults = AIBotConfig.defaults().perception().focus();
        AIBotConfig.Focus partial = new AIBotConfig.Focus(null, 0, 0, 0, 0, 0, 0);

        AIBotConfig.Focus merged = partial.withDefaults(defaults);

        assertTrue(merged.enabledValue());
        assertEquals(8, merged.range());
        assertEquals(2, merged.sampleIntervalTicks());
        assertEquals(4096, merged.maxDetailChars());
    }

    @Test
    void legacyPerceptionWithoutFocusObjectReceivesEnabledDefaults() {
        AIBotConfig.Perception legacy = new AIBotConfig.Perception(16, 20, 10, 10, false, null);

        AIBotConfig.Perception merged = legacy.withDefaults(AIBotConfig.defaults().perception());

        assertTrue(merged.focus().enabledValue());
        assertEquals(8, merged.focus().range());
    }

    @Test
    void explicitDisableSurvivesDefaultMerge() {
        AIBotConfig.Focus disabled = new AIBotConfig.Focus(false, 8, 2, 2, 4, 128, 4096);

        AIBotConfig.Focus merged = disabled.withDefaults(AIBotConfig.defaults().perception().focus());

        assertFalse(merged.enabledValue());
    }

    @Test
    void resourceLimitsAreClampedToDefensiveBounds() {
        AIBotConfig.Focus extreme = new AIBotConfig.Focus(true, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

        AIBotConfig.Focus merged = extreme.withDefaults(AIBotConfig.defaults().perception().focus());

        assertEquals(64, merged.range());
        assertEquals(200, merged.sampleIntervalTicks());
        assertEquals(20, merged.stableSamples());
        assertEquals(100, merged.lostGraceSamples());
        assertEquals(1024, merged.historySize());
        assertEquals(16384, merged.maxDetailChars());
    }
}
