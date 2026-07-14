package io.github.greytaiwolf.fakeaiplayer.perception.focus;

/** Conservative, evidence-backed behavior labels; UNKNOWN is preferred to guessing intent. */
public enum ObservedBehavior {
    DEAD,
    SLEEPING,
    SWIMMING,
    BURNING,
    USING_ITEM,
    ATTACKING,
    CHASING,
    HURT,
    MOVING,
    IDLE,
    UNKNOWN
}
