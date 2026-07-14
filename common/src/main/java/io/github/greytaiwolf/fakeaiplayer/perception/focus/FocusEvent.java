package io.github.greytaiwolf.fakeaiplayer.perception.focus;

/** Meaningful transitions retained in the bounded focus history and structured log. */
public enum FocusEvent {
    NONE,
    ACQUIRED,
    CHANGED,
    UPDATED,
    LOST
}
