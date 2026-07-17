package io.github.greytaiwolf.fakeaiplayer.building.site;

import io.github.greytaiwolf.fakeaiplayer.building.plan.PlanTransform;

/** One deterministic, fully surveyed placement candidate. */
public record BuildingSiteCandidate(
        BuildingSiteSurvey survey,
        PlanTransform transform,
        double score,
        String strategy
) {
    public BuildingSiteCandidate {
        if (survey == null || transform == null || !Double.isFinite(score)) {
            throw new IllegalArgumentException("invalid_building_site_candidate");
        }
        strategy = strategy == null || strategy.isBlank() ? "STILTS" : strategy;
    }
}
