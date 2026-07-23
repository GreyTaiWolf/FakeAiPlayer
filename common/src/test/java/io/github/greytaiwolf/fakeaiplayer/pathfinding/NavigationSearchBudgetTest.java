package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class NavigationSearchBudgetTest {
    private static final int BOT_SEARCHES = 8;
    private static final int SERVER_SEARCHES = 32;
    private static final int BOT_NODES = 32_000;
    private static final int SERVER_NODES = 128_000;
    private static final long BOT_MILLIS = 80L;
    private static final long SERVER_MILLIS = 240L;

    @Test
    void ninthSearchForOneBotIsDenied() {
        NavigationSearchBudget.Ledger<String> ledger = ledger();
        UUID bot = id(0);
        for (int search = 0; search < BOT_SEARCHES; search++) {
            assertTrue(ledger.acquire("server", 10, bot, 1, 1L).granted());
        }

        NavigationSearchBudget.Permit denied = ledger.acquire("server", 10, bot, 1, 1L);
        assertFalse(denied.granted());
        assertEquals(FailureReason.SEARCH_BUDGET, denied.denialReason());
        assertEquals(BOT_SEARCHES, ledger.snapshot("server", 10, bot).botSearches());
    }

    @Test
    void thirtyThirdServerSearchIsDeniedAcrossFourBots() {
        NavigationSearchBudget.Ledger<String> ledger = ledger();
        for (int botIndex = 0; botIndex < 4; botIndex++) {
            UUID bot = id(botIndex);
            for (int search = 0; search < BOT_SEARCHES; search++) {
                assertTrue(ledger.acquire("server", 20, bot, 1, 1L).granted());
            }
        }

        NavigationSearchBudget.Permit denied =
                ledger.acquire("server", 20, id(5), 1, 1L);
        assertFalse(denied.granted());
        assertEquals(SERVER_SEARCHES,
                ledger.snapshot("server", 20, id(0)).serverSearches());
    }

    @Test
    void nodeCapsApplyPerBotAndPerServer() {
        NavigationSearchBudget.Ledger<String> ledger = ledger();
        NavigationSearchBudget.Permit botPermit =
                ledger.acquire("bot-node-server", 30, id(0), BOT_NODES, 1L);
        assertTrue(botPermit.granted());
        botPermit.complete(BOT_NODES, 0L);
        assertFalse(ledger.acquire("bot-node-server", 30, id(0), 1, 1L).granted());

        for (int botIndex = 0; botIndex < 4; botIndex++) {
            NavigationSearchBudget.Permit permit =
                    ledger.acquire("server-node-server", 30, id(botIndex), BOT_NODES, 1L);
            assertTrue(permit.granted());
            permit.complete(BOT_NODES, 0L);
        }
        assertFalse(ledger.acquire(
                "server-node-server", 30, id(5), 1, 1L).granted());
        assertEquals(SERVER_NODES, ledger.snapshot(
                "server-node-server", 30, id(0)).serverNodes());
    }

    @Test
    void millisecondCapsApplyPerBotAndPerServer() {
        NavigationSearchBudget.Ledger<String> ledger = ledger();
        NavigationSearchBudget.Permit botPermit =
                ledger.acquire("bot-time-server", 40, id(0), 1, BOT_MILLIS);
        assertTrue(botPermit.granted());
        botPermit.complete(0, BOT_MILLIS);
        assertFalse(ledger.acquire("bot-time-server", 40, id(0), 1, 1L).granted());

        for (int botIndex = 0; botIndex < 3; botIndex++) {
            NavigationSearchBudget.Permit permit =
                    ledger.acquire("server-time-server", 40, id(botIndex), 1, BOT_MILLIS);
            assertTrue(permit.granted());
            permit.complete(0, BOT_MILLIS);
        }
        assertFalse(ledger.acquire(
                "server-time-server", 40, id(4), 1, 1L).granted());
        assertEquals(SERVER_MILLIS, ledger.snapshot(
                "server-time-server", 40, id(0)).serverMillis());
    }

    @Test
    void newTickResetsBotAndServerUsage() {
        NavigationSearchBudget.Ledger<String> ledger = ledger();
        UUID bot = id(0);
        for (int search = 0; search < BOT_SEARCHES; search++) {
            assertTrue(ledger.acquire("server", 50, bot, 1, 1L).granted());
        }
        assertFalse(ledger.acquire("server", 50, bot, 1, 1L).granted());

        assertTrue(ledger.acquire("server", 51, bot, 1, 1L).granted());
        NavigationSearchBudget.Snapshot reset = ledger.snapshot("server", 51, bot);
        assertEquals(1, reset.botSearches());
        assertEquals(1, reset.serverSearches());
        assertEquals(0, reset.botNodes());
        assertEquals(0L, reset.botMillis());
    }

    @Test
    void completingPermitTwiceDoesNotDoubleChargeUsage() {
        NavigationSearchBudget.Ledger<String> ledger = ledger();
        UUID bot = id(0);
        NavigationSearchBudget.Permit permit =
                ledger.acquire("server", 60, bot, 100, 20L);
        assertTrue(permit.granted());

        permit.complete(75, 12L);
        permit.complete(75, 12L);

        NavigationSearchBudget.Snapshot usage = ledger.snapshot("server", 60, bot);
        assertEquals(75, usage.botNodes());
        assertEquals(12L, usage.botMillis());
        assertEquals(75, usage.serverNodes());
        assertEquals(12L, usage.serverMillis());
    }

    @Test
    void clippedPermitClassifiesQuotaExhaustionAsSearchBudget() {
        NavigationSearchBudget.Ledger<String> ledger = ledger();
        UUID bot = id(0);
        NavigationSearchBudget.Permit first =
                ledger.acquire("server", 70, bot, BOT_NODES - 20, BOT_MILLIS - 10L);
        first.complete(BOT_NODES - 20, BOT_MILLIS - 10L);

        NavigationSearchBudget.Permit clipped =
                ledger.acquire("server", 70, bot, 100, 20L);
        assertTrue(clipped.granted());
        assertEquals(20, clipped.maxNodes());
        assertEquals(10L, clipped.maxMillis());
        assertEquals(FailureReason.SEARCH_BUDGET,
                clipped.classifyExhaustion(FailureReason.SEARCH_LIMIT));
        assertEquals(FailureReason.SEARCH_BUDGET,
                clipped.classifyExhaustion(FailureReason.TIMEOUT));
        assertEquals(FailureReason.GOAL_UNREACHABLE,
                clipped.classifyExhaustion(FailureReason.GOAL_UNREACHABLE));
    }

    @Test
    void lateCompletionCannotChargeANewTick() {
        NavigationSearchBudget.Ledger<String> ledger = ledger();
        UUID bot = id(0);
        NavigationSearchBudget.Permit old =
                ledger.acquire("server", 80, bot, 100, 20L);
        assertTrue(old.granted());
        assertEquals(0, ledger.snapshot("server", 81, bot).botSearches());

        old.complete(100, 20L);

        NavigationSearchBudget.Snapshot fresh = ledger.snapshot("server", 81, bot);
        assertEquals(0, fresh.botNodes());
        assertEquals(0L, fresh.botMillis());
        assertEquals(0, fresh.serverNodes());
        assertEquals(0L, fresh.serverMillis());
    }

    private static NavigationSearchBudget.Ledger<String> ledger() {
        return new NavigationSearchBudget.Ledger<>(
                BOT_SEARCHES, SERVER_SEARCHES,
                BOT_NODES, SERVER_NODES,
                BOT_MILLIS, SERVER_MILLIS);
    }

    private static UUID id(int value) {
        return new UUID(0L, value + 1L);
    }
}
