package sh.harold.blackbox.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.blackbox.core.jfr.BlackboxConnectionEvent;
import sh.harold.blackbox.core.jfr.BlackboxPluginEvent;
import sh.harold.blackbox.core.jfr.BlackboxPluginMetricEvent;
import sh.harold.blackbox.core.jfr.BlackboxSystemTickEvent;
import sh.harold.blackbox.core.jfr.BlackboxWorldTickEvent;

class JfrTimelineTest {

    @Test
    void decodesJvmArrayDescriptors() {
        assertEquals("long[]", JfrTimelineParser.decodeTypeName("[J"));
        assertEquals("byte[]", JfrTimelineParser.decodeTypeName("[B"));
        assertEquals("int[][]", JfrTimelineParser.decodeTypeName("[[I"));
        assertEquals("java.lang.Object[]", JfrTimelineParser.decodeTypeName("[Ljava.lang.Object;"));
        assertEquals("java.lang.String", JfrTimelineParser.decodeTypeName("java.lang.String"));
        assertNull(JfrTimelineParser.decodeTypeName(null));
    }

    @Test
    void parsesWorldTickSeriesAndWindow(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(BlackboxWorldTickEvent.class).withoutStackTrace();
            recording.start();
            for (int i = 0; i < 4; i++) {
                BlackboxWorldTickEvent event = new BlackboxWorldTickEvent();
                event.world = "default";
                event.tps = 30 - i;
                event.mspt = 33 + i;
                event.players = 5 + i;
                event.entities = 100 + i;
                event.commit();
                Thread.sleep(60);
            }
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        assertNotNull(timeline.start());
        assertNotNull(timeline.end());
        assertTrue(timeline.end().isAfter(timeline.start()));
        assertTrue(timeline.ticks().containsKey("default"));

        double[] tps = timeline.ticks().get("default").tps();
        assertEquals(JfrTimeline.BUCKETS, tps.length);
        assertEquals(30.0, tps[0], 0.001);
        assertEquals(27.0, tps[tps.length - 1], 0.001);

        double[] players = timeline.playersSeries();
        assertNotNull(players);
        assertEquals(8.0, players[players.length - 1], 0.001);

        double[] entities = timeline.entitiesSeries();
        assertNotNull(entities);
        assertEquals(JfrTimeline.BUCKETS, entities.length);
        assertEquals(100.0, entities[0], 0.001);
        assertEquals(103.0, entities[entities.length - 1], 0.001);

        assertNull(timeline.hostMemSeries());
        assertNull(timeline.heapCommittedSeries());
        assertTrue(timeline.swapTotal() <= 0);
        assertTrue(timeline.netOthers().isEmpty());
    }

    @Test
    void parsesPingSeriesAndPluginEvents(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(BlackboxWorldTickEvent.class).withoutStackTrace();
            recording.enable(BlackboxPluginEvent.class).withoutStackTrace();
            recording.start();
            for (int i = 0; i < 4; i++) {
                BlackboxWorldTickEvent event = new BlackboxWorldTickEvent();
                event.world = "default";
                event.tps = 30;
                event.mspt = 33;
                event.players = 10;
                event.entities = -1;
                event.avgPingMs = 40 + i;
                event.commit();
                if (i == 2) {
                    BlackboxPluginEvent plugin = new BlackboxPluginEvent();
                    plugin.category = "AiTickThrottler";
                    plugin.message = "throttled 42 entities";
                    plugin.commit();
                }
                Thread.sleep(60);
            }
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        double[] ping = timeline.pingSeries();
        assertNotNull(ping);
        assertEquals(JfrTimeline.BUCKETS, ping.length);
        assertEquals(40.0, ping[0], 0.001);
        assertEquals(43.0, ping[ping.length - 1], 0.001);

        assertNull(timeline.entitiesSeries());

        assertEquals(1, timeline.pluginEvents().size());
        JfrTimeline.PluginEvent event = timeline.pluginEvents().get(0);
        assertEquals("AiTickThrottler", event.category());
        assertEquals("throttled 42 entities", event.message());
        assertTrue(event.timeMs() >= timeline.start().toEpochMilli());
        assertTrue(event.timeMs() <= timeline.end().toEpochMilli());
    }

    @Test
    void parsesConnectionEvents(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(BlackboxConnectionEvent.class).withoutStackTrace();
            recording.start();
            BlackboxConnectionEvent connect = new BlackboxConnectionEvent();
            connect.player = "Wrex";
            connect.phase = "connect";
            connect.commit();
            Thread.sleep(60);
            BlackboxConnectionEvent join = new BlackboxConnectionEvent();
            join.player = "Shepard";
            join.phase = "join";
            join.detail = "arena10 · joined in 2.1s";
            join.commit();
            Thread.sleep(60);
            BlackboxConnectionEvent leave = new BlackboxConnectionEvent();
            leave.player = "Wrex";
            leave.phase = "setup disconnect";
            leave.detail = "noWorldAvailable";
            leave.commit();
            Thread.sleep(60);
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        assertEquals(3, timeline.connectionEvents().size());
        JfrTimeline.ConnEvent first = timeline.connectionEvents().get(0);
        assertEquals("Wrex", first.player());
        assertEquals("connect", first.phase());
        JfrTimeline.ConnEvent last = timeline.connectionEvents().get(2);
        assertEquals("setup disconnect", last.phase());
        assertEquals("noWorldAvailable", last.detail());
        assertEquals(1.0, sum(timeline.connectsSeries()), 0.001);
        assertEquals(1.0, sum(timeline.joinsSeries()), 0.001);
        assertNull(timeline.leavesSeries());
    }

    private static double sum(double[] series) {
        assertNotNull(series);
        double total = 0;
        for (double v : series) {
            total += v;
        }
        return total;
    }

    @Test
    void parsesPluginMetricSeries(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(BlackboxPluginMetricEvent.class).withoutStackTrace();
            recording.start();
            commitMetric("ChunkUnloader unloaded", 120, true);
            commitMetric("AiTickThrottler frozen", 42, false);
            Thread.sleep(120);
            commitMetric("ChunkUnloader unloaded", 60, true);
            commitMetric("AiTickThrottler frozen", 50, false);
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        assertEquals(2, timeline.pluginMetrics().size());

        JfrTimeline.PluginMetric count = metric(timeline, "ChunkUnloader unloaded");
        assertEquals("count", count.kind());
        assertEquals(JfrTimeline.BUCKETS, count.series().length);
        assertEquals(120.0, count.series()[0], 0.001);
        assertEquals(180.0, java.util.Arrays.stream(count.series()).sum(), 0.001);
        assertEquals(60.0, lastNonZero(count.series()), 0.001);
        assertEquals(0.0, count.series()[JfrTimeline.BUCKETS / 2], 0.001);

        JfrTimeline.PluginMetric gauge = metric(timeline, "AiTickThrottler frozen");
        assertEquals("gauge", gauge.kind());
        assertEquals(42.0, gauge.series()[0], 0.001);
        assertEquals(42.0, gauge.series()[JfrTimeline.BUCKETS / 2], 0.001);
        assertEquals(50.0, gauge.series()[gauge.series().length - 1], 0.001);

        assertTrue(timeline.retained().isEmpty());
    }

    @Test
    void parsesSystemTickSeries(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(BlackboxSystemTickEvent.class).withoutStackTrace();
            recording.start();
            commitSystemTick("EntityTickingSystem", "default", 5);
            commitSystemTick("FluidSystems", "default", 1);
            Thread.sleep(120);
            commitSystemTick("EntityTickingSystem", "default", 9);
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        assertEquals(1, timeline.tickSeries().size());
        JfrTimeline.SystemTick tick = timeline.tickSeries().get(0);
        assertEquals("EntityTickingSystem @ default", tick.name());
        assertEquals(JfrTimeline.BUCKETS, tick.series().length);
        assertEquals(5.0, tick.series()[0], 0.001);
        assertEquals(9.0, tick.series()[tick.series().length - 1], 0.001);
    }

    private static void commitSystemTick(String system, String world, double avgMs) {
        BlackboxSystemTickEvent event = new BlackboxSystemTickEvent();
        event.world = world;
        event.system = system;
        event.avgMs = avgMs;
        event.commit();
    }

    private static void commitMetric(String name, double value, boolean counter) {
        BlackboxPluginMetricEvent event = new BlackboxPluginMetricEvent();
        event.name = name;
        event.value = value;
        event.counter = counter;
        event.commit();
    }

    private static JfrTimeline.PluginMetric metric(JfrTimeline timeline, String name) {
        return timeline.pluginMetrics().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow();
    }

    private static double lastNonZero(double[] series) {
        for (int i = series.length - 1; i >= 0; i--) {
            if (series[i] != 0) {
                return series[i];
            }
        }
        return 0;
    }

    @Test
    void parsesDiskIoRateSeries(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("recording.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(sh.harold.blackbox.core.jfr.BlackboxDiskEvent.class).withoutStackTrace();
            recording.start();
            long[] reads = {0L, 12_000_000L, 24_000_000L};
            long[] writes = {0L, 6_000_000L, 12_000_000L};
            for (int i = 0; i < 3; i++) {
                sh.harold.blackbox.core.jfr.BlackboxDiskEvent event =
                    new sh.harold.blackbox.core.jfr.BlackboxDiskEvent();
                event.freeBytes = 50_000_000_000L;
                event.totalBytes = 100_000_000_000L;
                event.readBytes = reads[i];
                event.writeBytes = writes[i];
                event.commit();
                Thread.sleep(60);
            }
            recording.dump(target);
        }

        JfrTimeline timeline = JfrTimeline.parse(target);

        assertNotNull(timeline);
        double[] read = timeline.diskReadSeries();
        double[] write = timeline.diskWriteSeries();
        assertNotNull(read);
        assertNotNull(write);
        assertEquals(JfrTimeline.BUCKETS, read.length);
        assertEquals(0.0, read[0], 0.001);

        double bucketSeconds = (timeline.end().toEpochMilli() - timeline.start().toEpochMilli())
            / 1000.0 / JfrTimeline.BUCKETS;
        double readTotalMb = java.util.Arrays.stream(read).sum() * bucketSeconds;
        double writeTotalMb = java.util.Arrays.stream(write).sum() * bucketSeconds;
        assertEquals(24.0, readTotalMb, 0.1);
        assertEquals(12.0, writeTotalMb, 0.1);
        assertTrue(java.util.Arrays.stream(read).allMatch(v -> v >= 0));

        assertTrue(timeline.slowIo().isEmpty());
    }

    @Test
    void returnsNullForUnreadableFile(@TempDir Path tempDir) throws Exception {
        Path bogus = tempDir.resolve("bogus.jfr");
        Files.write(bogus, new byte[] {1, 2, 3});

        assertNull(JfrTimeline.parse(bogus));
    }

    @Test
    void flamePrunesLowWeightSubtrees() {
        JfrTimelineParser.FlameNode root = new JfrTimelineParser.FlameNode("all");
        for (int i = 0; i < 7; i++) {
            JfrTimelineParser.flameInsert(root, java.util.List.of("Main.run", "Hot.path"));
        }
        for (int i = 0; i < 2; i++) {
            JfrTimelineParser.flameInsert(root, java.util.List.of("Main.run", "Cold.path"));
        }

        JfrTimeline.Flame flame = JfrTimelineParser.flamePrune(root, 9);

        assertNotNull(flame);
        assertEquals("all", flame.name());
        assertEquals(9, flame.samples());
        assertEquals(1, flame.children().size());
        JfrTimeline.Flame main = flame.children().get(0);
        assertEquals("Main.run", main.name());
        assertEquals(9, main.samples());
        assertEquals(1, main.children().size());
        assertEquals("Hot.path", main.children().get(0).name());
        assertEquals(7, main.children().get(0).samples());
    }

    @Test
    void flameNodeCapDoublesThresholdUntilItFits() {
        JfrTimelineParser.FlameNode root = new JfrTimelineParser.FlameNode("all");
        for (int p = 0; p < 100; p++) {
            java.util.List<String> path = new java.util.ArrayList<>();
            for (int d = 0; d < 64; d++) {
                path.add("P" + p + ".f" + d);
            }
            for (int i = 0; i < 100; i++) {
                JfrTimelineParser.flameInsert(root, path);
            }
        }

        JfrTimeline.Flame flame = JfrTimelineParser.flamePrune(root, root.samples);

        assertNotNull(flame);
        assertEquals(10000, flame.samples());
        assertTrue(countNodes(flame) <= 6000);
    }

    @Test
    void flamePruneIsNullWithoutSamples() {
        assertNull(JfrTimelineParser.flamePrune(new JfrTimelineParser.FlameNode("all"), 0));
    }

    @Test
    void bucketCountScalesWithWindowAndClamps() {
        assertEquals(240, JfrTimelineParser.bucketCount(0, 30 * 60 * 1000L));
        assertEquals(720, JfrTimelineParser.bucketCount(0, 2 * 60 * 60 * 1000L));
        assertEquals(960, JfrTimelineParser.bucketCount(0, 8 * 60 * 60 * 1000L));
        assertEquals(960, JfrTimelineParser.bucketCount(0, 7 * 24 * 60 * 60 * 1000L));
        assertEquals(240, JfrTimelineParser.bucketCount(5, 5));
        assertEquals(240, JfrTimelineParser.bucketCount(10, 5));
    }

    @Test
    void normalizesThreadNames() {
        assertEquals("ServerWorkerGroup", JfrTimelineParser.normalizeThreadName("ServerWorkerGroup-3"));
        assertEquals("pool-2-thread", JfrTimelineParser.normalizeThreadName("pool-2-thread-7"));
        assertEquals("Thread", JfrTimelineParser.normalizeThreadName("Thread#12"));
        assertEquals("WorldThread - Farmwelt", JfrTimelineParser.normalizeThreadName("WorldThread - Farmwelt"));
        assertEquals("main", JfrTimelineParser.normalizeThreadName("main"));
        assertEquals("Worker", JfrTimelineParser.normalizeThreadName("Worker"));
        assertEquals("io-worker", JfrTimelineParser.normalizeThreadName("io-worker-0"));
    }

    private static int countNodes(JfrTimeline.Flame node) {
        int n = 1;
        for (JfrTimeline.Flame child : node.children()) {
            n += countNodes(child);
        }
        return n;
    }
}
