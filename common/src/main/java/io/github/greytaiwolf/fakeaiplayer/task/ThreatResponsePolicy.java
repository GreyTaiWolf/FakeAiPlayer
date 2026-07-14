package io.github.greytaiwolf.fakeaiplayer.task;

/**
 * Pure decision helpers shared by threat-response planning and tests.
 * Keeping the scoring here makes emergency behavior deterministic and reviewable.
 */
final class ThreatResponsePolicy {
    enum Fallback {
        BREAK_CONTACT,
        LAST_STAND,
        NONE
    }

    private ThreatResponsePolicy() {
    }

    static double escapeScore(double startDistance,
                              double endDistance,
                              double midRouteDistance,
                              int exits,
                              int pathLength,
                              double preferredAlignment,
                              int dangerSteps) {
        double distanceGain = endDistance - startDistance;
        double routeGain = midRouteDistance - startDistance;
        return distanceGain * 4.0D
                + routeGain * 2.0D
                + Math.max(0, exits) * 2.5D
                + clamp(preferredAlignment, -1.0D, 1.0D) * 3.0D
                - Math.max(0, pathLength) * 0.35D
                - Math.max(0, dangerSteps) * 4.0D;
    }

    static boolean materiallySafer(double startDistance,
                                    double endDistance,
                                    double midRouteDistance,
                                    int dangerSteps,
                                    int pathLength) {
        double requiredEndDistance = Math.max(8.0D, Math.min(14.0D, startDistance + 4.0D));
        int toleratedDangerSteps = Math.max(2, Math.max(0, pathLength) / 4);
        return endDistance >= requiredEndDistance
                && midRouteDistance >= Math.max(2.5D, startDistance - 1.5D)
                && dangerSteps <= toleratedDangerSteps;
    }

    static boolean meaningfullyImproves(double startDistance,
                                         double endDistance,
                                         double midRouteDistance) {
        return endDistance >= startDistance + 2.5D
                && midRouteDistance >= Math.max(2.0D, startDistance - 2.0D);
    }

    static boolean shouldCompleteRoute(boolean terminalRoute,
                                       boolean hostileThreat,
                                       boolean hostileSituationResolved) {
        return hostileSituationResolved || (terminalRoute && !hostileThreat);
    }

    static Fallback fallback(boolean hostileAvailable, float health, int failedEscapePlans) {
        if (!hostileAvailable) {
            return Fallback.NONE;
        }
        if (health <= 6.0F || failedEscapePlans >= 2) {
            return Fallback.LAST_STAND;
        }
        return Fallback.BREAK_CONTACT;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
