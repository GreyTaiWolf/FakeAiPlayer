package io.github.greytaiwolf.fakeaiplayer.pathfinding;

/** Central classification so interrupted or inconclusive navigation is never memorized as no-path. */
public final class NavigationOutcomePolicy {
    private NavigationOutcomePolicy() {
    }

    public static boolean mayRecordUnreachable(NavigationResult result) {
        return mayRecordUnreachable(result, AStarPathfinder.worldVersion());
    }

    public static boolean mayRecordUnreachable(NavigationResult result, long currentWorldVersion) {
        return result != null
                && result.state() == NavigationState.BLOCKED
                && result.failureReason() == FailureReason.GOAL_UNREACHABLE
                && result.unrestrictedEvidenceScope()
                && !result.requestedGoal().dynamic()
                && result.worldVersion() == currentWorldVersion;
    }
}
