package sh.harold.blackbox.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.harold.blackbox.core.incident.DiagnosticSection;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

class StalledThreadTest {

    @Test
    void buildsSectionForLiveThread() {
        long id = Thread.currentThread().threadId();
        TriggerEvent event = new TriggerEvent(TriggerKind.HEARTBEAT_STALL, "world",
            Instant.parse("2026-01-11T00:00:00Z"),
            Map.of("stallMs", "300000", "threadId", Long.toString(id)));

        List<DiagnosticSection> sections = StalledThread.build(event, List.of());

        assertEquals(1, sections.size());
        DiagnosticSection section = sections.get(0);
        assertEquals("Stalled thread", section.title());
        assertEquals(Thread.currentThread().getName(), section.entries().get("Thread"));
        assertEquals("300000 ms", section.entries().get("Stalled for"));
        assertNotNull(section.preformatted());
        assertTrue(section.preformatted().contains("StalledThreadTest"));
    }

    @Test
    void buildsSectionsForDeadlockThreadIds() {
        long id = Thread.currentThread().threadId();
        TriggerEvent event = new TriggerEvent(TriggerKind.DEADLOCK, "jvm",
            Instant.parse("2026-01-11T00:00:00Z"),
            Map.of("threads", "1", "threadIds", Long.toString(id)));

        List<DiagnosticSection> sections = StalledThread.build(event, List.of());

        assertEquals(1, sections.size());
        assertEquals("Deadlocked thread", sections.get(0).title());
        assertEquals(Thread.currentThread().getName(), sections.get(0).entries().get("Thread"));
    }

    @Test
    void returnsEmptyWithoutThreadId() {
        TriggerEvent event = new TriggerEvent(TriggerKind.HEARTBEAT_STALL, "world",
            Instant.parse("2026-01-11T00:00:00Z"), Map.of("stallMs", "300000"));
        assertTrue(StalledThread.build(event, List.of()).isEmpty());
    }

    @Test
    void returnsEmptyForUnknownThread() {
        TriggerEvent event = new TriggerEvent(TriggerKind.HEARTBEAT_STALL, "world",
            Instant.parse("2026-01-11T00:00:00Z"), Map.of("threadId", "999999999"));
        assertTrue(StalledThread.build(event, List.of()).isEmpty());
    }

    @Test
    void topSuspectFrameSkipsFrameworkToNamePlugin() {
        StackTraceElement[] stack = {
            new StackTraceElement("jdk.internal.misc.Unsafe", "park", "Unsafe.java", -2),
            new StackTraceElement("java.util.concurrent.locks.StampedLock", "acquireRead", "StampedLock.java", 1418),
            new StackTraceElement("com.hypixel.hytale.server.core.universe.world.storage.ChunkStore",
                "getChunkReference", "ChunkStore.java", 563),
            new StackTraceElement("dev.sanandrea.hytale.sprinkler.event.SprinklerLifecycleHandler",
                "onEntityRemove", "SprinklerLifecycleHandler.java", 82),
            new StackTraceElement("com.hypixel.hytale.component.Store", "removeEntity", "Store.java", 832),
        };

        assertEquals("com.hypixel.hytale.server.core.universe.world.storage.ChunkStore.getChunkReference",
            StalledThread.topSuspectFrame(stack, List.of()));
        assertEquals("dev.sanandrea.hytale.sprinkler.event.SprinklerLifecycleHandler.onEntityRemove",
            StalledThread.topSuspectFrame(stack, List.of("com.hypixel.hytale.")));
    }
}
