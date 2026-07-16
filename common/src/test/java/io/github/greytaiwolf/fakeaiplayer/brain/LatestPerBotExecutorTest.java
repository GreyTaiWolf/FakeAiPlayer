package io.github.greytaiwolf.fakeaiplayer.brain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LatestPerBotExecutorTest {
    @Test
    void runsOneRequestPerBotAndKeepsOnlyLatestPending() throws Exception {
        LatestPerBotExecutor executor = new LatestPerBotExecutor(2, 2);
        UUID bot = UUID.randomUUID();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch latestFinished = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        List<Integer> calls = new CopyOnWriteArrayList<>();

        try {
            assertEquals(LatestPerBotExecutor.Submission.ACCEPTED, executor.submit(bot, () -> {
                int nowActive = active.incrementAndGet();
                maxActive.accumulateAndGet(nowActive, Math::max);
                calls.add(1);
                firstStarted.countDown();
                await(releaseFirst);
                active.decrementAndGet();
            }));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            assertEquals(LatestPerBotExecutor.Submission.QUEUED_LATEST,
                    executor.submit(bot, () -> calls.add(2)));
            assertEquals(LatestPerBotExecutor.Submission.REPLACED_PENDING,
                    executor.submit(bot, () -> {
                        int nowActive = active.incrementAndGet();
                        maxActive.accumulateAndGet(nowActive, Math::max);
                        calls.add(3);
                        active.decrementAndGet();
                        latestFinished.countDown();
                    }));

            releaseFirst.countDown();
            assertTrue(latestFinished.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1, 3), calls);
            assertEquals(1, maxActive.get());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsNewBotsWhenBoundedWorkerQueueIsFull() throws Exception {
        LatestPerBotExecutor executor = new LatestPerBotExecutor(1, 1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            assertEquals(LatestPerBotExecutor.Submission.ACCEPTED,
                    executor.submit(UUID.randomUUID(), () -> {
                        running.countDown();
                        await(release);
                    }));
            assertTrue(running.await(2, TimeUnit.SECONDS));
            assertEquals(LatestPerBotExecutor.Submission.ACCEPTED,
                    executor.submit(UUID.randomUUID(), () -> { }));
            assertEquals(LatestPerBotExecutor.Submission.REJECTED,
                    executor.submit(UUID.randomUUID(), () -> { }));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void pendingRequestCanBeDiscardedOnCredentialChange() throws Exception {
        LatestPerBotExecutor executor = new LatestPerBotExecutor(1, 1);
        UUID bot = UUID.randomUUID();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch markerFinished = new CountDownLatch(1);
        AtomicInteger pendingCalls = new AtomicInteger();

        try {
            executor.submit(bot, () -> {
                running.countDown();
                await(release);
            });
            assertTrue(running.await(2, TimeUnit.SECONDS));
            executor.submit(bot, pendingCalls::incrementAndGet);
            assertTrue(executor.discardPending(bot));
            assertFalse(executor.discardPending(bot));
            executor.submit(bot, markerFinished::countDown);
            release.countDown();
            assertTrue(markerFinished.await(2, TimeUnit.SECONDS));
            assertEquals(0, pendingCalls.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
