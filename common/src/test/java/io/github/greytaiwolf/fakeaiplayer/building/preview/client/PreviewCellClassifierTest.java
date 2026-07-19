package io.github.greytaiwolf.fakeaiplayer.building.preview.client;

import static io.github.greytaiwolf.fakeaiplayer.building.preview.client.PreviewCellClassifier.Kind.CLEAR_CONFLICT;
import static io.github.greytaiwolf.fakeaiplayer.building.preview.client.PreviewCellClassifier.Kind.HIDDEN;
import static io.github.greytaiwolf.fakeaiplayer.building.preview.client.PreviewCellClassifier.Kind.MISSING;
import static io.github.greytaiwolf.fakeaiplayer.building.preview.client.PreviewCellClassifier.Kind.PRESERVE_CONFLICT;
import static io.github.greytaiwolf.fakeaiplayer.building.preview.client.PreviewCellClassifier.Kind.TEMPORARY;
import static io.github.greytaiwolf.fakeaiplayer.building.preview.client.PreviewCellClassifier.Kind.WRONG_BLOCK;
import static io.github.greytaiwolf.fakeaiplayer.building.preview.client.PreviewCellClassifier.Kind.WRONG_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy;
import io.github.greytaiwolf.fakeaiplayer.building.preview.client.PreviewCellClassifier.Facts;
import org.junit.jupiter.api.Test;

class PreviewCellClassifierTest {
    @Test
    void clearAndPreserveHaveOperationSpecificConflicts() {
        assertEquals(HIDDEN, classify(CellOperation.CLEAR, ReplacePolicy.CLEAR_AUTHORIZED,
                facts(true, false, false, false, true, true)));
        assertEquals(CLEAR_CONFLICT, classify(CellOperation.CLEAR, ReplacePolicy.CLEAR_AUTHORIZED,
                facts(false, false, false, false, false, true)));
        assertEquals(HIDDEN, classify(CellOperation.PRESERVE, ReplacePolicy.PRESERVE_EXISTING,
                facts(false, true, true, true, false, true)));
        assertEquals(PRESERVE_CONFLICT, classify(CellOperation.PRESERVE, ReplacePolicy.PRESERVE_EXISTING,
                facts(false, true, true, false, false, true)));
    }

    @Test
    void placeSeparatesExactStateMissingAndConflicts() {
        assertEquals(HIDDEN, classify(CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY,
                facts(false, true, true, true, false, true)));
        assertEquals(WRONG_STATE, classify(CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY,
                facts(false, true, true, false, false, true)));
        assertEquals(MISSING, classify(CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY,
                facts(true, true, false, false, true, true)));
        assertEquals(WRONG_BLOCK, classify(CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY,
                facts(false, true, false, false, false, true)));
    }

    @Test
    void replaceableDestinationMustBeDryAndTemporaryKeepsItsStyle() {
        assertEquals(MISSING, classify(CellOperation.PLACE, ReplacePolicy.REPLACE_REPLACEABLE,
                facts(false, true, false, false, true, true)));
        assertEquals(WRONG_BLOCK, classify(CellOperation.PLACE, ReplacePolicy.REPLACE_REPLACEABLE,
                facts(false, true, false, false, true, false)));
        assertEquals(TEMPORARY, classify(CellOperation.TEMPORARY, ReplacePolicy.REQUIRE_EMPTY,
                facts(true, true, false, false, true, true)));
    }

    @Test
    void unresolvedPaletteEntryStaysVisibleAndFallsBackToWireframe() {
        assertEquals(MISSING, classify(CellOperation.PLACE, ReplacePolicy.REQUIRE_EMPTY,
                facts(true, false, false, false, true, true)));
    }

    private static PreviewCellClassifier.Kind classify(
            CellOperation operation, ReplacePolicy policy, Facts facts) {
        return PreviewCellClassifier.classify(operation, policy, facts);
    }

    private static Facts facts(boolean actualAir,
                               boolean expectedResolved,
                               boolean sameBlock,
                               boolean propertiesMatch,
                               boolean actualReplaceable,
                               boolean actualFluidEmpty) {
        return new Facts(actualAir, expectedResolved, sameBlock, propertiesMatch,
                actualReplaceable, actualFluidEmpty);
    }
}
