package sh.harold.blackbox.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.harold.blackbox.core.health.HealthSnapshot;
import sh.harold.blackbox.core.incident.DiagnosticSection;
import sh.harold.blackbox.core.incident.IncidentId;
import sh.harold.blackbox.core.incident.IncidentIds;
import sh.harold.blackbox.core.incident.IncidentMetadata;
import sh.harold.blackbox.core.incident.IncidentReport;
import sh.harold.blackbox.core.incident.IncidentSummary;
import sh.harold.blackbox.core.incident.Severity;

class ReportHtmlTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static IncidentReport report(HealthSnapshot snapshot) {
        return report(snapshot, List.of());
    }

    private static IncidentReport report(HealthSnapshot snapshot, List<DiagnosticSection> diagnostics) {
        IncidentId id = IncidentIds.next(CLOCK);
        IncidentMetadata meta = new IncidentMetadata(
            id, CLOCK.instant(), Severity.DEGRADED, "HEARTBEAT_STALL", "default", "Test headline");
        IncidentSummary summary = new IncidentSummary("Unknown", List.of("x"), List.of("y"));
        return new IncidentReport(meta, summary, Map.of("stallMs", "2146"), snapshot, diagnostics);
    }

    private static String render(IncidentReport r) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ReportHtml.write(r, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void rendersSnapshotDataAndHeader() throws Exception {
        HealthSnapshot snap = new HealthSnapshot(
            30,
            new HealthSnapshot.Cpu(0.5, 0.7, 8),
            new HealthSnapshot.Memory(30_000_000_000L, 40_000_000_000L, 42_000_000_000L,
                500_000_000L, 600_000_000L),
            List.of(new HealthSnapshot.Gc("Shenandoah Pauses", 362, 44)),
            new HealthSnapshot.Sys("Linux 6.8 (amd64)", "OpenJDK 25", 64_000_000_000L, 3_600_000L),
            new HealthSnapshot.Disk(50_000_000_000L, 100_000_000_000L),
            new HealthSnapshot.Threads(238, Map.of("RUNNABLE", 44, "WAITING", 96),
                List.of(new HealthSnapshot.RunnableThread("WorldThread - default", "Foo.bar(Foo.java:1)"))),
            List.of(new HealthSnapshot.World("default", 10, 250, 1200, 25.1, 35.0,
                List.of("Shebao", "PianoManu"), -1, 33.2, 48.9, 112.5)),
            List.of(new HealthSnapshot.HotThread("WorldThread - default", 1234, "AStarBase.computePath")));

        String html = render(report(snap));

        assertTrue(html.contains("Blackbox Incident Report"));
        assertTrue(html.contains("Heartbeat stalled <span class=\"ms\">2146 ms</span>"));
        assertTrue(html.contains("DEGRADED"));
        assertTrue(html.contains("\"RUNNABLE\":44"));
        assertTrue(html.contains("WorldThread - default"));
        assertTrue(html.contains("AStarBase.computePath"));
        assertTrue(html.contains("Shebao"));
        assertTrue(html.contains("\"players\":10"));
        assertTrue(html.contains("\"entities\":250"));
        assertTrue(html.contains("\"chunks\":1200"));
        assertTrue(html.contains("\"msptP50\":33.2"));
        assertTrue(html.contains("\"msptP95\":48.9"));
        assertTrue(html.contains("\"msptMax\":112.5"));
        assertTrue(html.contains("\"committed\":40000000000"));
        assertTrue(html.contains("\"nonHeap\":{\"used\":500000000,\"committed\":600000000}"));
        assertTrue(html.contains("\"swap\":null"));
        assertTrue(html.contains("\"hostMemUsed\":null"));
        assertFalse(html.contains("\"arch\":null"));
        assertTrue(html.contains("\"loadAvg\""));
        assertTrue(html.contains("\"openFds\""));
        assertTrue(html.contains("\"cpuset\""));
        assertTrue(html.contains("\"gcByCollector\":[[\"Shenandoah Pauses\",362,44]]"));
        assertFalse(html.contains("<script src="));
    }

    @Test
    void rendersWithoutSnapshot() throws Exception {
        String html = render(report(null));

        assertTrue(html.contains("Heartbeat stalled <span class=\"ms\">2146 ms</span>"));
        assertTrue(html.contains("\"sys\":null"));
        assertTrue(html.contains("\"worlds\":[]"));
        assertTrue(html.contains("\"gcCauses\":null"));
        assertTrue(html.contains("\"gcOverlap\":null"));
        assertTrue(html.contains("\"flame\":null"));
        assertTrue(html.contains("\"threadTimeline\":null"));
        assertTrue(html.contains("\"heapHistogram\":null"));
        assertTrue(html.contains("\"entities\":null"));
        assertTrue(html.contains("\"memPools\":null"));
        assertTrue(html.contains("\"worldTps\":null"));
        assertTrue(html.contains("\"slowIo\":null"));
    }

    @Test
    void emitsRecoveryNoteOnlyForUncleanShutdown() throws Exception {
        IncidentId id = IncidentIds.next(CLOCK);
        IncidentSummary summary = new IncidentSummary(
            "Unknown", List.of("The JVM exited without a clean shutdown;", "rebuilt on startup."), List.of("y"));
        IncidentReport recovered = new IncidentReport(new IncidentMetadata(
            id, CLOCK.instant(), Severity.DEGRADED, "UNCLEAN_SHUTDOWN", null, "Recovered"), summary);

        String html = render(recovered);

        assertTrue(html.contains(
            "\"note\":\"The JVM exited without a clean shutdown; rebuilt on startup.\""));
        assertTrue(render(report(null)).contains("\"note\":null"));
    }

    @Test
    void routesMemoryPools() throws Exception {
        LinkedHashMap<String, String> pools = new LinkedHashMap<>();
        pools.put("G1 Eden Space", "1024 2048 4096 512");
        pools.put("Metaspace", "300 400 -1 -1");
        pools.put("broken.Pool", "not numbers");

        String html = render(report(null, List.of(new DiagnosticSection("Memory pools", pools))));

        assertTrue(html.contains(
            "\"memPools\":[[\"G1 Eden Space\",1024,2048,4096,512],[\"Metaspace\",300,400,-1,-1]]"));
        assertFalse(html.contains("\"title\":\"Memory pools\""));
    }

    @Test
    void stalledThreadSectionRendersStackAndHeaderHighlight() throws Exception {
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        entries.put("Thread", "WorldThread - default");
        entries.put("State", "WAITING");
        entries.put("Blocked in", "com.example.Plugin.onEntityRemove");
        String stack = "at jdk.internal.misc.Unsafe.park(Native Method)\n"
            + "at com.example.Plugin.onEntityRemove(Plugin.java:1)";

        String html = render(report(null, List.of(new DiagnosticSection("Stalled thread", entries, stack))));

        assertTrue(html.contains("\"title\":\"Stalled thread\""));
        assertTrue(html.contains("\"pre\":\"at jdk.internal.misc.Unsafe.park"));
        assertTrue(html.contains("Blocked in <code>com.example.Plugin.onEntityRemove</code>"));
    }

    @Test
    void routesSectionsByTitle() throws Exception {
        LinkedHashMap<String, String> mixins = new LinkedHashMap<>();
        mixins.put("Bootstrapper", "ExampleLoader 1.0.0");
        mixins.put("example.mixins.json", "MixinA, MixinB");
        mixins.put("Conflict: com.hypixel.hytale.X",
            "example.mixins.json → MixinX; other.mixins.json → MixinY; plain.mixins.json");
        LinkedHashMap<String, String> plugins = new LinkedHashMap<>();
        plugins.put("Harold:Blackbox", "0.2.0 @ >=0.5.0 @ COMPATIBLE");
        plugins.put("ExampleVendor:ExamplePlugin", "0.4.4 @ 0.5.3 @ INCOMPATIBLE");
        plugins.put("Hytale:NPC", "1.0.0");
        LinkedHashMap<String, String> pluginConfig = new LinkedHashMap<>();
        pluginConfig.put("ExampleFeature.Enabled", "true");
        LinkedHashMap<String, String> tickSystems = new LinkedHashMap<>();
        tickSystems.put("EntityTickingSystem @ default", "5.10 12.40 4.80 30.00");
        LinkedHashMap<String, String> modCpu = new LinkedHashMap<>();
        modCpu.put("sh.harold.blackbox", "412 samples · JfrController.dump");
        modCpu.put("com.example.plugin", "8123 samples · ExampleSystem.tick");
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        env.put("ExampleLoader", "installed (1.0.0)");
        env.put("Server name", "ExampleServer");
        env.put("Hytale version", "0.5.4");
        env.put("Container runtime", "Docker");
        String log = "[2026/06/06 15:41:09   WARN] [Spawning] Removing NPC\n"
            + "[2026/06/06 15:41:10 SEVERE] [ExamplePlugin] Uncaught exception\n"
            + "\tat com.example.plugin.ai.ExampleSystem.tick(ExampleSystem.java:142)\n"
            + "[2026/06/06 15:41:11   INFO] [Vote] Player ready";
        LinkedHashMap<String, String> heapHistogram = new LinkedHashMap<>();
        heapHistogram.put("[B", "1234567 987654321");
        heapHistogram.put("java.lang.String", "234 5678");
        heapHistogram.put("broken.Row", "not numbers");
        LinkedHashMap<String, String> entities = new LinkedHashMap<>();
        entities.put("default", "Zombie x12, Cow x5, malformed");

        IncidentReport r = report(null, List.of(
            new DiagnosticSection("Mixins", mixins),
            new DiagnosticSection("Plugins", plugins),
            new DiagnosticSection("Server log", Map.of(), log),
            new DiagnosticSection("ExamplePlugin", pluginConfig),
            new DiagnosticSection("Tick systems", tickSystems),
            new DiagnosticSection("Mod hot-path contribution (JFR)", modCpu),
            new DiagnosticSection("Heap histogram", heapHistogram),
            new DiagnosticSection("Entities", entities),
            new DiagnosticSection("Environment", env),
            new DiagnosticSection("mods/ExampleVendor_ExamplePlugin/config.json", Map.of(), "{\"a\":1}")));

        String html = render(r);

        assertTrue(html.contains("\"bootstrapper\":\"ExampleLoader 1.0.0\""));
        assertTrue(html.contains("[\"example.mixins.json\",[\"MixinA\",\"MixinB\"]]"));
        assertTrue(html.contains(
            "\"conflicts\":[[\"com.hypixel.hytale.X\",[[\"example.mixins.json\",\"MixinX\"],"
            + "[\"other.mixins.json\",\"MixinY\"],[\"plain.mixins.json\",null]]]]"));
        assertTrue(html.contains("[\"Harold\",\"Blackbox\",\"0.2.0\",false,\">=0.5.0\",\"COMPATIBLE\"]"));
        assertTrue(html.contains("[\"ExampleVendor\",\"ExamplePlugin\",\"0.4.4\",false,\"0.5.3\",\"INCOMPATIBLE\"]"));
        assertTrue(html.contains("[\"Hytale\",\"NPC\",\"1.0.0\",true,null,null]"));
        assertTrue(html.contains("[\"WARN\",\"Spawning\",\"Removing NPC\"]"));
        assertTrue(html.contains("\"ERROR\",\"ExamplePlugin\""));
        assertTrue(html.contains("ExampleSystem.tick"));
        assertTrue(html.contains("\"title\":\"mods/ExampleVendor_ExamplePlugin/config.json\""));
        assertTrue(html.contains("\"title\":\"ExamplePlugin\""));
        assertTrue(html.contains("ExampleFeature.Enabled"));
        assertTrue(html.contains("\"tickSystems\":[[\"EntityTickingSystem\",\"default\",5.1,12.4,4.8,30.0]]"));
        assertTrue(html.contains("\"tickSystemsNote\":null"));
        assertTrue(html.contains("\"modCpu\":[[\"com.example.plugin\",8123,\"ExampleSystem.tick\"],"
            + "[\"sh.harold.blackbox\",412,\"JfrController.dump\"]]"));
        assertFalse(html.contains("\"title\":\"Mod hot-path contribution (JFR)\""));
        assertTrue(html.contains(
            "\"heapHistogram\":[[\"[B\",1234567,987654321],[\"java.lang.String\",234,5678]]"));
        assertFalse(html.contains("\"title\":\"Heap histogram\""));
        assertTrue(html.contains("\"entities\":[[\"default\",[[\"Zombie\",12],[\"Cow\",5]]]]"));
        assertFalse(html.contains("\"title\":\"Entities\""));
        assertTrue(html.contains("\"loaders\":[[\"ExampleLoader\",\"1.0.0\"]]"));
        assertTrue(html.contains("\"server\":\"ExampleServer\""));
        assertTrue(html.contains("\"hytale\":\"0.5.4\""));
        assertTrue(html.contains(
            "\"container\":{\"runtime\":\"Docker\",\"type\":null,\"cpuLimit\":null,\"memLimit\":null}"));
        assertFalse(html.contains("Container runtime"));
        assertFalse(html.contains("\"title\":\"Environment\""));
    }

    @Test
    void tickSystemsNoteRoutesWithoutRows() throws Exception {
        String html = render(report(null, List.of(new DiagnosticSection("Tick systems",
            Map.of("Note", "system metrics disabled")))));

        assertTrue(html.contains("\"tickSystems\":null"));
        assertTrue(html.contains("\"tickSystemsNote\":\"system metrics disabled\""));
    }

    @Test
    void mixinConflictsNoneRendersEmptyList() throws Exception {
        LinkedHashMap<String, String> mixins = new LinkedHashMap<>();
        mixins.put("Bootstrapper", "ExampleLoader");
        mixins.put("a.mixins.json", "MixinA");
        mixins.put("Conflicts", "none");

        String html = render(report(null, List.of(new DiagnosticSection("Mixins", mixins))));

        assertTrue(html.contains("\"conflicts\":[]"));
        assertFalse(html.contains("\"Conflicts\""));
    }

    @Test
    void parsesHytaleLogFormat() {
        List<ReportHtml.LogLine> entries = ReportHtml.parseLog(
            "[2026/06/06 15:41:09   WARN] [Spawning] Removing NPC 'Boar'\n"
            + "[2026/06/06 15:41:10 SEVERE] [ExamplePlugin] boom\n"
            + "\tat a.b.C.d(C.java:1)\n"
            + "Caused by: java.lang.IllegalStateException\n"
            + "[2026/06/06 15:41:11   INFO] [Vote] ok");

        assertEquals(3, entries.size());
        assertEquals("WARN", entries.get(0).level());
        assertEquals("Spawning", entries.get(0).source());
        assertEquals("Removing NPC 'Boar'", entries.get(0).message());
        assertEquals(Instant.parse("2026-06-06T15:41:09Z").toEpochMilli(), entries.get(0).epochMs());
        assertEquals("ERROR", entries.get(1).level());
        assertTrue(entries.get(1).message().contains("Caused by"));
        assertEquals("INFO", entries.get(2).level());
    }

    @Test
    void parsesLogLinesWithRedactedTimestamp() {
        List<ReportHtml.LogLine> entries = ReportHtml.parseLog(
            "[2026/06/17 [REDACTED]   INFO] [HOSStatus|P] ONLINE\n"
            + "[2026/06/17 [REDACTED]   WARN] [Spawning] Removing NPC\n"
            + "[2026/06/17 [REDACTED] SEVERE] [ExamplePlugin] boom");

        assertEquals(3, entries.size());
        assertEquals("INFO", entries.get(0).level());
        assertEquals("HOSStatus|P", entries.get(0).source());
        assertEquals("ONLINE", entries.get(0).message());
        assertEquals(-1, entries.get(0).epochMs());
        assertEquals("WARN", entries.get(1).level());
        assertEquals("Removing NPC", entries.get(1).message());
        assertEquals("ERROR", entries.get(2).level());
    }

    @Test
    void classifiesConnectionLogLines() {
        List<ReportHtml.LogLine> entries = ReportHtml.parseLog(
            "[2026/06/11 17:02:08   INFO] [Hytale] Starting authenticated flow from Quic...\n"
            + "[2026/06/11 17:02:14   INFO] [Hytale] Disconnecting Wrex with the message: x\n"
            + "[2026/06/11 17:02:14   INFO] [Hytale] {Playing(null)} was closed.\n"
            + "[2026/06/11 17:02:14   INFO] [Vote] Disconnecting nothing");

        assertEquals("conn", ReportHtml.connectionEventType(entries.get(0)));
        assertEquals("conn", ReportHtml.connectionEventType(entries.get(1)));
        assertNull(ReportHtml.connectionEventType(entries.get(2)));
        assertNull(ReportHtml.connectionEventType(entries.get(3)));
    }

    @Test
    void escapesScriptCloseInsideData() throws Exception {
        IncidentReport r = report(null, List.of(
            new DiagnosticSection("Server log", Map.of(),
                "[2026/06/06 15:41:09   WARN] [X] payload </script> attack")));

        String html = render(r);

        int first = html.indexOf("</script>");
        int last = html.lastIndexOf("</script>");
        assertEquals(first, last);
        assertTrue(html.contains("<\\/script>"));
    }
}
