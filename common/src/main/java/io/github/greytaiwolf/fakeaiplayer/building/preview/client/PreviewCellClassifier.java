package io.github.greytaiwolf.fakeaiplayer.building.preview.client;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateResolver;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy;
import java.util.Objects;
import net.minecraft.world.level.block.state.BlockState;

/** One authoritative, loader-neutral classification for building preview cells. */
public final class PreviewCellClassifier {
    private PreviewCellClassifier() {
    }

    public static Kind classify(BlockState actual,
                                ResolvedPreviewPalette.Entry expected,
                                CellOperation operation,
                                ReplacePolicy replacePolicy) {
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(expected, "expected");
        BlockState expectedState = expected.state();
        boolean sameBlock = expectedState != null && actual.is(expectedState.getBlock());
        boolean propertiesMatch = sameBlock
                && BlockStateResolver.matchesProperties(actual, expected.spec().properties());
        return classify(operation, replacePolicy, new Facts(
                actual.isAir(),
                expectedState != null,
                sameBlock,
                propertiesMatch,
                actual.canBeReplaced(),
                actual.getFluidState().isEmpty()));
    }

    /**
     * Pure decision boundary used by both renderers and unit tests.
     *
     * <p>{@code propertiesMatch} is meaningful only when {@code sameBlock} is true. Keeping these
     * facts primitive makes every operation/policy branch testable without a running client.</p>
     */
    public static Kind classify(CellOperation operation, ReplacePolicy replacePolicy, Facts facts) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(replacePolicy, "replacePolicy");
        Objects.requireNonNull(facts, "facts");

        if (operation == CellOperation.CLEAR) {
            return facts.actualAir() ? Kind.HIDDEN : Kind.CLEAR_CONFLICT;
        }

        boolean matches = facts.expectedResolved()
                && facts.sameBlock()
                && facts.propertiesMatch();
        if (operation == CellOperation.PRESERVE) {
            return matches ? Kind.HIDDEN : Kind.PRESERVE_CONFLICT;
        }
        if (matches) {
            return Kind.HIDDEN;
        }
        if (facts.expectedResolved() && facts.sameBlock()) {
            return Kind.WRONG_STATE;
        }

        boolean executableDestination = switch (replacePolicy) {
            case REQUIRE_EMPTY -> facts.actualAir();
            // REPLACE_NATURAL is deliberately no broader on the client than the current executor.
            // Solid terrain still needs an explicit CLEAR cell and remains a red conflict.
            case REPLACE_REPLACEABLE, REPLACE_NATURAL -> facts.actualAir()
                    || facts.actualReplaceable() && facts.actualFluidEmpty();
            case CLEAR_AUTHORIZED, PRESERVE_EXISTING, FORCE_AUTHORIZED -> false;
        };
        if (!executableDestination) {
            return Kind.WRONG_BLOCK;
        }
        return operation == CellOperation.TEMPORARY ? Kind.TEMPORARY : Kind.MISSING;
    }

    public enum Kind {
        HIDDEN,
        MISSING,
        TEMPORARY,
        WRONG_STATE,
        WRONG_BLOCK,
        CLEAR_CONFLICT,
        PRESERVE_CONFLICT;

        public boolean wantsGhostModel() {
            return this == MISSING || this == TEMPORARY;
        }
    }

    public record Facts(
            boolean actualAir,
            boolean expectedResolved,
            boolean sameBlock,
            boolean propertiesMatch,
            boolean actualReplaceable,
            boolean actualFluidEmpty
    ) {
    }
}
