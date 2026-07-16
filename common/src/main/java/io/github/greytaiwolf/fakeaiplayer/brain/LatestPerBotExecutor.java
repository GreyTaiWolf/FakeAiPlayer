package io.github.greytaiwolf.fakeaiplayer.brain;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded scheduler that permits one running request per bot and retains only its latest pending
 * request. This prevents rapid chat input from building an unbounded HTTP request backlog.
 */
final class LatestPerBotExecutor {
    private final ThreadPoolExecutor executor;
    private final Map<UUID, Slot> slots = new HashMap<>();
    private boolean closed;

    LatestPerBotExecutor(int parallelism, int queueCapacity) {
        if (parallelism < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("executor_capacity_must_be_positive");
        }
        AtomicInteger threadSequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "fakeaiplayer-brain-" + threadSequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    synchronized Submission submit(UUID botId, Runnable request) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(request, "request");
        if (closed) {
            return Submission.REJECTED;
        }

        Slot existing = slots.get(botId);
        if (existing != null) {
            boolean replaced = existing.pending != null;
            existing.pending = request;
            return replaced ? Submission.REPLACED_PENDING : Submission.QUEUED_LATEST;
        }

        Slot slot = new Slot();
        slots.put(botId, slot);
        try {
            executor.execute(() -> drain(botId, slot, request));
            return Submission.ACCEPTED;
        } catch (RuntimeException rejected) {
            slots.remove(botId, slot);
            return Submission.REJECTED;
        }
    }

    synchronized void shutdownNow() {
        closed = true;
        slots.clear();
        executor.shutdownNow();
    }

    synchronized boolean discardPending(UUID botId) {
        Slot slot = slots.get(Objects.requireNonNull(botId, "botId"));
        if (slot == null || slot.pending == null) {
            return false;
        }
        slot.pending = null;
        return true;
    }

    private void drain(UUID botId, Slot slot, Runnable first) {
        Runnable current = first;
        while (current != null) {
            try {
                current.run();
            } finally {
                synchronized (this) {
                    if (closed) {
                        return;
                    }
                    current = slot.pending;
                    slot.pending = null;
                    if (current == null) {
                        slots.remove(botId, slot);
                    }
                }
            }
        }
    }

    enum Submission {
        ACCEPTED,
        QUEUED_LATEST,
        REPLACED_PENDING,
        REJECTED
    }

    private static final class Slot {
        private Runnable pending;
    }
}
