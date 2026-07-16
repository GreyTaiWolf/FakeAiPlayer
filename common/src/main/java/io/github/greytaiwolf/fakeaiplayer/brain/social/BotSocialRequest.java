package io.github.greytaiwolf.fakeaiplayer.brain.social;

import java.util.Objects;

/**
 * A bounded, non-command social turn. There is intentionally no tool-enabled policy value.
 * Integrations must submit these requests with an empty tool list and must never pass any returned
 * tool calls to the normal action dispatcher.
 */
public record BotSocialRequest(
        Trigger trigger,
        String prompt,
        ToolPolicy toolPolicy
) {
    public BotSocialRequest {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(prompt, "prompt");
        if (toolPolicy != ToolPolicy.NONE) {
            throw new IllegalArgumentException("proactive_social_tools_forbidden");
        }
    }

    public static BotSocialRequest withoutTools(Trigger trigger, String prompt) {
        return new BotSocialRequest(trigger, prompt, ToolPolicy.NONE);
    }

    public boolean toolsAllowed() {
        return false;
    }

    public enum ToolPolicy {
        NONE
    }

    public enum Trigger {
        CONNECTION_SUCCEEDED,
        OWNER_ONLINE,
        FIRST_OWNER_NEARBY,
        TASK_COMPLETED,
        TASK_FAILED
    }
}
