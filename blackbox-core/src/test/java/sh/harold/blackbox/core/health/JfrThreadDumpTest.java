package sh.harold.blackbox.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import sh.harold.blackbox.core.incident.DiagnosticSection;

class JfrThreadDumpTest {

    private static final String DUMP = String.join("\n",
        "2026-06-18 20:54:25",
        "Full thread dump OpenJDK 64-Bit Server VM",
        "",
        "\"pool-1-thread-1\" #10 daemon prio=5 waiting on condition",
        "   java.lang.Thread.State: WAITING (parking)",
        "\tat jdk.internal.misc.Unsafe.park(Native Method)",
        "\t- parking to wait for  <0x1> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)",
        "\tat java.util.concurrent.LinkedBlockingQueue.take(LinkedBlockingQueue.java:435)",
        "\tat java.util.concurrent.ThreadPoolExecutor.getTask(ThreadPoolExecutor.java:1016)",
        "",
        "\"WorldThread - Hidden Sanctuary\" #202 prio=5 waiting on condition",
        "   java.lang.Thread.State: WAITING (parking)",
        "\tat jdk.internal.misc.Unsafe.park(Native Method)",
        "\t- parking to wait for  <0x2> (a java.util.concurrent.locks.StampedLock)",
        "\tat java.util.concurrent.locks.StampedLock.acquireRead(StampedLock.java:1418)",
        "\tat com.hypixel.hytale.server.core.universe.world.storage.ChunkStore.getChunkReference(ChunkStore.java:563)",
        "\tat dev.sanandrea.hytale.sprinkler.event.SprinklerLifecycleHandler.onEntityRemove(SprinklerLifecycleHandler.java:82)",
        "\tat com.hypixel.hytale.server.core.universe.world.storage.ChunkStore.remove(ChunkStore.java:514)",
        "");

    @Test
    void stuckSectionFindsDeadlockedThreadAndNamesPlugin() {
        DiagnosticSection section = JfrThreadDump.stuckSection(DUMP, List.of("com.hypixel.hytale."));

        assertNotNull(section);
        assertEquals("Stalled thread", section.title());
        assertEquals("WorldThread - Hidden Sanctuary", section.entries().get("Thread"));
        assertEquals("WAITING (parking)", section.entries().get("State"));
        assertEquals("java.util.concurrent.locks.StampedLock", section.entries().get("Waiting on"));
        assertEquals("dev.sanandrea.hytale.sprinkler.event.SprinklerLifecycleHandler.onEntityRemove",
            section.entries().get("Blocked in"));
        assertTrue(section.preformatted().contains("ChunkStore.remove(ChunkStore.java:514)"));
    }

    @Test
    void withoutFrameworkPrefixesNamesEngineLockSite() {
        DiagnosticSection section = JfrThreadDump.stuckSection(DUMP, List.of());
        assertEquals("com.hypixel.hytale.server.core.universe.world.storage.ChunkStore.getChunkReference",
            section.entries().get("Blocked in"));
    }

    @Test
    void returnsNullWhenOnlyIdleThreads() {
        String idle = String.join("\n",
            "\"pool-1-thread-1\" #10 daemon waiting on condition",
            "   java.lang.Thread.State: WAITING (parking)",
            "\tat jdk.internal.misc.Unsafe.park(Native Method)",
            "\t- parking to wait for  <0x1> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)",
            "\tat java.util.concurrent.LinkedBlockingQueue.take(LinkedBlockingQueue.java:435)");
        assertNull(JfrThreadDump.stuckSection(idle, List.of()));
    }

    @Test
    void returnsNullForBlankDump() {
        assertNull(JfrThreadDump.stuckSection("", List.of()));
        assertNull(JfrThreadDump.stuckSection(null, List.of()));
    }
}
