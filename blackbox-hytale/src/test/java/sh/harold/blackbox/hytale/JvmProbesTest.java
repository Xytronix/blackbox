package sh.harold.blackbox.hytale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JvmProbesTest {

    @Test
    void excludesGenerationalZgcConcurrentCycleBeans() {
        assertFalse(JvmProbes.isStopTheWorld("ZGC Minor Cycles"));
        assertFalse(JvmProbes.isStopTheWorld("ZGC Major Cycles"));
        assertTrue(JvmProbes.isStopTheWorld("ZGC Minor Pauses"));
        assertTrue(JvmProbes.isStopTheWorld("ZGC Major Pauses"));
    }

    @Test
    void keepsStopTheWorldCollectorsAcrossEngines() {
        assertTrue(JvmProbes.isStopTheWorld("G1 Young Generation"));
        assertTrue(JvmProbes.isStopTheWorld("G1 Old Generation"));
        assertTrue(JvmProbes.isStopTheWorld("PS Scavenge"));
        assertTrue(JvmProbes.isStopTheWorld("PS MarkSweep"));
        assertTrue(JvmProbes.isStopTheWorld("Copy"));
        assertTrue(JvmProbes.isStopTheWorld("MarkSweepCompact"));
        assertTrue(JvmProbes.isStopTheWorld("Shenandoah Pauses"));
    }

    @Test
    void excludesConcurrentPhaseBeans() {
        assertFalse(JvmProbes.isStopTheWorld("G1 Concurrent GC"));
        assertFalse(JvmProbes.isStopTheWorld("Shenandoah Cycles"));
        assertFalse(JvmProbes.isStopTheWorld("ConcurrentMarkSweep"));
    }

    @Test
    void nullBeanNameIsNotCounted() {
        assertFalse(JvmProbes.isStopTheWorld(null));
    }
}
