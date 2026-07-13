package io.github.greytaiwolf.fakeaiplayer.perception.focus;

/** Lifecycle state of the Bot's independent semantic-gaze sensor. */
public enum FocusState {
    DISABLED,
    NO_TARGET,
    ACQUIRING,
    TRACKING,
    LOST_GRACE
}
