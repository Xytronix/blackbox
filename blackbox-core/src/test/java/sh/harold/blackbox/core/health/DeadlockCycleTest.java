package sh.harold.blackbox.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import sh.harold.blackbox.core.incident.DiagnosticSection;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

class DeadlockCycleTest {

    @Test
    void returnsNullForNonDeadlockEvent() {
        TriggerEvent event = new TriggerEvent(TriggerKind.HEARTBEAT_STALL, "world", Instant.EPOCH,
            Map.of("threadIds", "1,2"));
        assertNull(DeadlockCycle.build(event));
    }

    @Test
    void returnsNullWhenNoThreadIds() {
        TriggerEvent event = new TriggerEvent(TriggerKind.DEADLOCK, "jvm", Instant.EPOCH, Map.of());
        assertNull(DeadlockCycle.build(event));
    }

    @Test
    void rendersWaitForChainForRealDeadlock() throws Exception {
        Object lockA = new Object();
        Object lockB = new Object();
        CountDownLatch bothHoldFirst = new CountDownLatch(2);

        Thread one = new Thread(() -> {
            synchronized (lockA) {
                bothHoldFirst.countDown();
                awaitQuietly(bothHoldFirst);
                synchronized (lockB) {
                    lockB.hashCode();
                }
            }
        }, "deadlock-one");
        Thread two = new Thread(() -> {
            synchronized (lockB) {
                bothHoldFirst.countDown();
                awaitQuietly(bothHoldFirst);
                synchronized (lockA) {
                    lockA.hashCode();
                }
            }
        }, "deadlock-two");
        one.setDaemon(true);
        two.setDaemon(true);
        one.start();
        two.start();

        long[] ids = awaitDeadlock(5_000);
        assertNotNull(ids, "JVM did not report a deadlock in time");
        String joined = Arrays.stream(ids).sorted().mapToObj(Long::toString).collect(Collectors.joining(","));

        TriggerEvent event = new TriggerEvent(TriggerKind.DEADLOCK, "jvm", Instant.EPOCH,
            Map.of("threadIds", joined));
        DiagnosticSection section = DeadlockCycle.build(event);

        assertNotNull(section);
        assertEquals("Deadlock cycle", section.title());
        assertEquals(2, section.entries().size());
        assertTrue(section.preformatted().contains("waiting on"), section.preformatted());
        assertTrue(section.preformatted().contains("held by"), section.preformatted());
    }

    private static long[] awaitDeadlock(long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
            if (ids != null && ids.length >= 2) {
                return ids;
            }
            Thread.sleep(50);
        }
        return null;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
