package io.github.greytaiwolf.fakeaiplayer.runtime;

import java.util.Set;

/**
 * Identifies the subsystem that paused a bot's ordinary work.
 *
 * <p>USER and INVENTORY are persistent locks: no background watcher may release them. SAFETY and
 * SYSTEM are temporary preemptions and may be unwound automatically after the interrupting work
 * finishes.</p>
 */
public enum PauseOwner {
    USER(false, true),
    SAFETY(true, false),
    INVENTORY(false, true),
    SYSTEM(true, false);

    private final boolean automaticResumeAllowed;
    private final boolean persistentLock;

    PauseOwner(boolean automaticResumeAllowed, boolean persistentLock) {
        this.automaticResumeAllowed = automaticResumeAllowed;
        this.persistentLock = persistentLock;
    }

    public boolean automaticResumeAllowed() {
        return automaticResumeAllowed;
    }

    public boolean persistentLock() {
        return persistentLock;
    }

    /**
     * A paused frame may resume after the final persistent owner releases, regardless of which
     * persistent owner originally created that frame. This makes USER + INVENTORY lock ordering
     * commutative while still preventing either owner from releasing the other one early.
     */
    public static boolean resumeAllowedAfterPersistentRelease(PauseOwner frameOwner,
                                                               Set<PauseOwner> heldOwners) {
        boolean persistentLockRemains = heldOwners != null
                && heldOwners.stream().anyMatch(PauseOwner::persistentLock);
        return !persistentLockRemains
                && (frameOwner.persistentLock() || frameOwner.automaticResumeAllowed());
    }
}
