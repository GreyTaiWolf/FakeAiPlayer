package io.github.greytaiwolf.fakeaiplayer.building.preview.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.fakeaiplayer.building.preview.client.PreviewRenderBudget.Cost;
import org.junit.jupiter.api.Test;

class PreviewRenderBudgetTest {
    @Test
    void defaultBakedModelLimitStaysConservative() {
        assertEquals(256, PreviewRenderBudget.DEFAULT_MODEL_LIMIT);
        assertTrue(PreviewRenderBudget.DEFAULT_MODEL_LIMIT
                < PreviewRenderBudget.DEFAULT_RENDER_LIMIT);
    }

    @Test
    void scanLimitIsExact() {
        PreviewRenderBudget budget = new PreviewRenderBudget(2, 4, 2);
        assertTrue(budget.tryScan());
        assertTrue(budget.tryScan());
        assertFalse(budget.tryScan());
        assertEquals(2, budget.scanned());
    }

    @Test
    void modelLimitFallsBackWithoutConsumingTotalSlot() {
        PreviewRenderBudget budget = new PreviewRenderBudget(8, 3, 1);
        assertTrue(budget.tryRender(Cost.MODEL));
        assertFalse(budget.tryRender(Cost.MODEL));
        assertEquals(1, budget.rendered());
        assertTrue(budget.tryRender(Cost.LINE));
        assertTrue(budget.tryRender(Cost.LINE));
        assertFalse(budget.tryRender(Cost.LINE));
        assertEquals(3, budget.rendered());
        assertEquals(1, budget.models());
    }

    @Test
    void invalidLimitsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new PreviewRenderBudget(-1, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PreviewRenderBudget(1, 1, 2));
    }
}
