package io.github.greytaiwolf.fakeaiplayer.mission;

import java.util.Map;

/** Declarative Mission request produced by a player, local intent parser, or AI proposal. */
public record GoalSpec(
        String type,
        Source source,
        int priority,
        String successPredicate,
        String dimension,
        MissionPolicy policy,
        Map<String, String> attributes
) {
    public GoalSpec {
        type = requireToken(type, "goal_type");
        source = source == null ? Source.LEGACY : source;
        if (priority < 0 || priority > 100) {
            throw new IllegalArgumentException("goal_priority_out_of_range");
        }
        successPredicate = requireText(successPredicate, "goal_success_predicate");
        dimension = requireText(dimension, "goal_dimension");
        policy = policy == null ? MissionPolicy.standard() : policy;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static int defaultPriority(Source source) {
        Source resolved = source == null ? Source.LEGACY : source;
        return switch (resolved) {
            case PLAYER_CONFIRMED, PLAYER_COMMAND -> 90;
            case AI_PROPOSAL -> 70;
            case RESTORED -> 65;
            case AUTONOMOUS -> 40;
            case LEGACY -> 60;
        };
    }

    private static String requireToken(String value, String field) {
        String normalized = requireText(value, field).toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException(field + "_invalid");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "_missing");
        }
        return value.trim();
    }

    public enum Source {
        PLAYER_CONFIRMED,
        PLAYER_COMMAND,
        AI_PROPOSAL,
        AUTONOMOUS,
        RESTORED,
        LEGACY
    }
}
