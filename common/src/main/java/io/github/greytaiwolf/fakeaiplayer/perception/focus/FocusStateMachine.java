package io.github.greytaiwolf.fakeaiplayer.perception.focus;

/**
 * Small deterministic debounce state machine for gaze samples. It is intentionally independent
 * from Minecraft classes so transition rules can be unit tested without a running world.
 */
public final class FocusStateMachine {
    private final int requiredStableSamples;
    private final int lostGraceSamples;

    private FocusState state = FocusState.NO_TARGET;
    private String activeKey;
    private String candidateKey;
    private int candidateSamples;
    private int missingSamples;

    public FocusStateMachine(int requiredStableSamples, int lostGraceSamples) {
        this.requiredStableSamples = Math.max(1, requiredStableSamples);
        this.lostGraceSamples = Math.max(1, lostGraceSamples);
    }

    public Update sample(String visibleKey) {
        if (visibleKey == null || visibleKey.isBlank()) {
            return miss();
        }
        missingSamples = 0;

        if (activeKey != null && visibleKey.equals(activeKey)) {
            state = FocusState.TRACKING;
            candidateKey = null;
            candidateSamples = 0;
            return new Update(state, activeKey, FocusEvent.NONE, false);
        }

        if (state != FocusState.ACQUIRING || !visibleKey.equals(candidateKey)) {
            candidateKey = visibleKey;
            candidateSamples = 1;
        } else {
            candidateSamples++;
        }
        state = FocusState.ACQUIRING;

        if (candidateSamples < requiredStableSamples) {
            return new Update(state, candidateKey, FocusEvent.NONE, false);
        }

        boolean changed = activeKey != null && !activeKey.equals(candidateKey);
        activeKey = candidateKey;
        candidateKey = null;
        candidateSamples = 0;
        state = FocusState.TRACKING;
        return new Update(state, activeKey, changed ? FocusEvent.CHANGED : FocusEvent.ACQUIRED, false);
    }

    public Update disable() {
        reset();
        state = FocusState.DISABLED;
        return new Update(state, null, FocusEvent.NONE, false);
    }

    public void reset() {
        state = FocusState.NO_TARGET;
        activeKey = null;
        candidateKey = null;
        candidateSamples = 0;
        missingSamples = 0;
    }

    public FocusState state() {
        return state;
    }

    private Update miss() {
        candidateKey = null;
        candidateSamples = 0;

        if (activeKey == null) {
            state = FocusState.NO_TARGET;
            return new Update(state, null, FocusEvent.NONE, false);
        }

        missingSamples++;
        if (missingSamples <= lostGraceSamples) {
            state = FocusState.LOST_GRACE;
            return new Update(state, activeKey, FocusEvent.NONE, true);
        }

        String lostKey = activeKey;
        activeKey = null;
        missingSamples = 0;
        state = FocusState.NO_TARGET;
        return new Update(state, lostKey, FocusEvent.LOST, false);
    }

    public record Update(FocusState state, String targetKey, FocusEvent event, boolean stale) {
    }
}
