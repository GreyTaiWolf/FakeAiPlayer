package io.github.greytaiwolf.fakeaiplayer.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Pure LIFO model used by TaskManager for nested safety preemption. */
public final class ExecutionStack<T> {
    private final Deque<Frame<T>> frames = new ArrayDeque<>();

    public Frame<T> push(T work, TaskOrigin origin) {
        return push(work, origin, PauseOwner.SYSTEM);
    }

    public Frame<T> push(T work, TaskOrigin origin, PauseOwner pauseOwner) {
        Frame<T> frame = new Frame<>(UUID.randomUUID(), work, origin, pauseOwner);
        frames.addLast(frame);
        return frame;
    }

    public Optional<Frame<T>> peek() {
        return Optional.ofNullable(frames.peekLast());
    }

    public Optional<Frame<T>> popResumable(boolean userPaused) {
        Frame<T> frame = frames.peekLast();
        if (frame == null || (userPaused && !frame.origin().safety())) {
            return Optional.empty();
        }
        return Optional.of(frames.removeLast());
    }

    public Optional<Frame<T>> pop() {
        return Optional.ofNullable(frames.pollLast());
    }

    public List<Frame<T>> drain() {
        List<Frame<T>> drained = new ArrayList<>();
        Frame<T> frame;
        while ((frame = frames.pollLast()) != null) {
            drained.add(frame);
        }
        return List.copyOf(drained);
    }

    /** Removes only frames owned by one cancellation transaction while preserving LIFO order. */
    public List<Frame<T>> removeMatching(Predicate<Frame<T>> predicate) {
        if (predicate == null || frames.isEmpty()) {
            return List.of();
        }
        List<Frame<T>> removed = new ArrayList<>();
        var iterator = frames.iterator();
        while (iterator.hasNext()) {
            Frame<T> frame = iterator.next();
            if (predicate.test(frame)) {
                iterator.remove();
                removed.add(frame);
            }
        }
        return List.copyOf(removed);
    }

    public boolean anyMatch(Predicate<Frame<T>> predicate) {
        return predicate != null && frames.stream().anyMatch(predicate);
    }

    /** Rewrites matching immutable frame origins without changing frame identity or LIFO order. */
    public int replaceOrigins(Predicate<TaskOrigin> predicate,
                              UnaryOperator<TaskOrigin> replacement) {
        if (predicate == null || replacement == null || frames.isEmpty()) {
            return 0;
        }
        Deque<Frame<T>> rebuilt = new ArrayDeque<>(frames.size());
        int changed = 0;
        for (Frame<T> frame : frames) {
            TaskOrigin origin = frame.origin();
            if (predicate.test(origin)) {
                TaskOrigin updated = replacement.apply(origin);
                if (updated == null) {
                    throw new IllegalArgumentException("replacement_origin_missing");
                }
                frame = new Frame<>(frame.frameId(), frame.work(), updated, frame.pauseOwner());
                changed++;
            }
            rebuilt.addLast(frame);
        }
        frames.clear();
        frames.addAll(rebuilt);
        return changed;
    }

    public int size() {
        return frames.size();
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    public record Frame<T>(UUID frameId, T work, TaskOrigin origin, PauseOwner pauseOwner) {
    }
}
