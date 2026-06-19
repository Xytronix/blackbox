package sh.harold.blackbox.core.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import sh.harold.blackbox.core.health.HealthSnapshot;
import sh.harold.blackbox.core.health.JfrTimeline;
import sh.harold.blackbox.core.incident.IncidentMetadata;
import sh.harold.blackbox.core.json.JsonWriter;

final class ReportActivityWriter {

    private ReportActivityWriter() {
    }

    record TickSelection(String world, double[] tps, double[] mspt) {}

    static TickSelection selectTicks(IncidentMetadata meta, HealthSnapshot snapshot, JfrTimeline timeline) {
        if (timeline == null || timeline.ticks().isEmpty()) {
            return null;
        }
        Map<String, JfrTimeline.WorldTicks> ticks = timeline.ticks();
        String world = null;
        if (meta.world() != null && ticks.containsKey(meta.world())) {
            world = meta.world();
        } else {
            long best = -1;
            for (String candidate : ticks.keySet()) {
                long samples = samplesOrZero(timeline, candidate);
                if (samples > best) {
                    best = samples;
                    world = candidate;
                }
            }
        }
        JfrTimeline.WorldTicks wt = ticks.get(world);
        return wt == null ? null : new TickSelection(world, wt.tps(), wt.mspt());
    }

    static void writeTps(JsonWriter json, TickSelection tick, HealthSnapshot snapshot) throws IOException {
        if (tick == null || tick.tps() == null) {
            json.name("tps").nullValue();
            return;
        }
        json.name("tps").beginObject();
        json.name("world").value(tick.world());
        ReportJson.writeNullable(json, "target", snapshot != null && snapshot.targetTps() > 0
            ? (long) snapshot.targetTps() : null);
        json.name("avg").value(ReportJson.round2(ReportJson.avg(tick.tps())));
        json.name("min").value(ReportJson.round2(ReportJson.min(tick.tps())));
        json.endObject();
    }

    static void writeSeries(JsonWriter json, JfrTimeline timeline, TickSelection tick) throws IOException {
        json.name("series").beginObject();
        if (timeline != null) {
            ReportJson.writeDoubleSeries(json, "cpu", timeline.cpuMachineSeries());
            ReportJson.writeDoubleSeries(json, "cpuJvm", timeline.cpuJvmSeries());
            ReportJson.writeLongSeries(json, "heap", timeline.heapSeries());
            ReportJson.writeLongSeries(json, "heapCommitted", timeline.heapCommittedSeries());
            ReportJson.writeLongSeries(json, "rss", timeline.rssSeries());
            ReportJson.writeLongSeries(json, "hostMem", timeline.hostMemSeries());
            ReportJson.writeDoubleSeries(json, "entities", timeline.entitiesSeries());
            ReportJson.writeDoubleSeries(json, "chunks", timeline.chunksSeries());
            ReportJson.writeDoubleSeries(json, "chunksGenerated", timeline.chunksGeneratedSeries());
            ReportJson.writeDoubleSeries(json, "chunksLoaded", timeline.chunksLoadedSeries());
            ReportJson.writeDoubleSeries(json, "netIn", timeline.netInSeries());
            ReportJson.writeDoubleSeries(json, "netOut", timeline.netOutSeries());
            ReportJson.writeLongSeries(json, "threads", timeline.threadSeries());
            ReportJson.writeDoubleSeries(json, "gcPause", timeline.gcPauseSeries());
            ReportJson.writeDoubleSeries(json, "players", timeline.playersSeries());
            ReportJson.writeDoubleSeries(json, "exceptions", timeline.exceptionsSeries());
            ReportJson.writeDoubleSeries(json, "connects", timeline.connectsSeries());
            ReportJson.writeDoubleSeries(json, "joins", timeline.joinsSeries());
            ReportJson.writeDoubleSeries(json, "leaves", timeline.leavesSeries());
            ReportJson.writeDoubleSeries(json, "ping", timeline.pingSeries());
            ReportJson.writeLongSeries(json, "disk", timeline.diskFreeSeries());
            ReportJson.writeDoubleSeries(json, "diskRead", timeline.diskReadSeries());
            ReportJson.writeDoubleSeries(json, "diskWrite", timeline.diskWriteSeries());
        }
        if (tick != null) {
            ReportJson.writeDoubleSeries(json, "tps", tick.tps());
            ReportJson.writeDoubleSeries(json, "mspt", tick.mspt());
        }
        json.endObject();
    }

    static void writePluginMetrics(JsonWriter json, JfrTimeline timeline) throws IOException {
        List<JfrTimeline.PluginMetric> metrics = timeline == null ? List.of() : timeline.pluginMetrics();
        if (metrics.isEmpty()) {
            json.name("pluginMetrics").nullValue();
            return;
        }
        json.name("pluginMetrics").beginArray();
        for (JfrTimeline.PluginMetric metric : metrics) {
            json.beginObject();
            json.name("name").value(metric.name());
            json.name("kind").value(metric.kind());
            ReportJson.writeDoubleSeries(json, "series", metric.series());
            json.endObject();
        }
        json.endArray();
    }

    private record TickSystemRow(String system, String world, double avg1m, double max1m,
                                 double avg5m, double max5m) {}

    static void writeTickSystems(JsonWriter json, Map<String, String> tickSystems) throws IOException {
        String note = null;
        List<TickSystemRow> rows = new ArrayList<>();
        if (tickSystems != null) {
            for (Map.Entry<String, String> e : tickSystems.entrySet()) {
                if ("Note".equals(e.getKey())) {
                    note = e.getValue();
                    continue;
                }
                String key = e.getKey().replaceFirst(" #\\d+$", "");
                int at = key.lastIndexOf(" @ ");
                if (at < 0) {
                    continue;
                }
                String[] parts = e.getValue().trim().split("\\s+");
                if (parts.length < 4) {
                    continue;
                }
                try {
                    rows.add(new TickSystemRow(
                        key.substring(0, at), key.substring(at + " @ ".length()),
                        Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]), Double.parseDouble(parts[3])));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        rows.sort((a, b) -> Double.compare(b.avg1m(), a.avg1m()));
        if (rows.isEmpty()) {
            json.name("tickSystems").nullValue();
        } else {
            json.name("tickSystems").beginArray();
            for (int i = 0; i < rows.size() && i < 20; i++) {
                TickSystemRow row = rows.get(i);
                json.beginArray();
                json.value(row.system());
                json.value(row.world());
                json.value(ReportJson.round2(row.avg1m()));
                json.value(ReportJson.round2(row.max1m()));
                json.value(ReportJson.round2(row.avg5m()));
                json.value(ReportJson.round2(row.max5m()));
                json.endArray();
            }
            json.endArray();
        }
        json.name("tickSystemsNote").value(note);
    }

    static void writeTickSeries(JsonWriter json, JfrTimeline timeline) throws IOException {
        List<JfrTimeline.SystemTick> series = timeline == null ? List.of() : timeline.tickSeries();
        if (series.isEmpty()) {
            json.name("tickSeries").nullValue();
            return;
        }
        json.name("tickSeries").beginArray();
        for (JfrTimeline.SystemTick entry : series) {
            json.beginObject();
            json.name("name").value(entry.name());
            ReportJson.writeDoubleSeries(json, "series", entry.series());
            json.endObject();
        }
        json.endArray();
    }

    static void writeModCpu(JsonWriter json, JfrTimeline timeline, Map<String, String> modCpu)
        throws IOException {
        if (timeline != null && !timeline.cpuByMod().isEmpty()) {
            json.name("modCpu").beginArray();
            for (JfrTimeline.ModCpu mod : timeline.cpuByMod()) {
                json.beginArray();
                json.value(mod.id());
                json.value(mod.samples());
                json.value(mod.method());
                json.beginArray();
                for (JfrTimeline.ThreadShare ts : mod.threads()) {
                    json.beginArray();
                    json.value(ts.thread());
                    json.value(ts.samples());
                    json.endArray();
                }
                json.endArray();
                json.endArray();
            }
            json.endArray();
            return;
        }
        if (modCpu == null || modCpu.isEmpty()) {
            json.name("modCpu").nullValue();
            return;
        }
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : modCpu.entrySet()) {
            String value = e.getValue();
            long samples = 0;
            String method = value;
            int idx = value.indexOf(" samples · ");
            if (idx > 0) {
                try {
                    samples = Long.parseLong(value.substring(0, idx).trim());
                    method = value.substring(idx + " samples · ".length()).trim();
                } catch (NumberFormatException ignored) {
                }
            }
            rows.add(new Object[] {e.getKey(), samples, method});
        }
        rows.sort((a, b) -> Long.compare((long) b[1], (long) a[1]));
        json.name("modCpu").beginArray();
        for (Object[] row : rows) {
            json.beginArray();
            json.value((String) row[0]);
            json.value((long) row[1]);
            json.value((String) row[2]);
            json.endArray();
        }
        json.endArray();
    }

    static void writeFlame(JsonWriter json, JfrTimeline timeline) throws IOException {
        JfrTimeline.Flame flame = timeline == null ? null : timeline.flame();
        json.name("flame");
        if (flame == null) {
            json.nullValue();
            return;
        }
        writeFlameNode(json, flame);
    }

    private static void writeFlameNode(JsonWriter json, JfrTimeline.Flame node) throws IOException {
        json.beginArray();
        json.value(node.name());
        json.value(node.samples());
        json.beginArray();
        for (JfrTimeline.Flame child : node.children()) {
            writeFlameNode(json, child);
        }
        json.endArray();
        json.value(node.owner());
        json.endArray();
    }

    static void writeThreadTimeline(JsonWriter json, JfrTimeline timeline) throws IOException {
        if (timeline == null || timeline.threadTimeline().isEmpty()) {
            json.name("threadTimeline").nullValue();
            return;
        }
        json.name("threadTimeline").beginArray();
        for (JfrTimeline.ThreadLane lane : timeline.threadTimeline()) {
            json.beginObject();
            json.name("name").value(lane.name());
            json.name("states").beginArray();
            for (int state : lane.states()) {
                json.value(state);
            }
            json.endArray();
            json.endObject();
        }
        json.endArray();
    }

    static void writeEntities(JsonWriter json, Map<String, String> entities) throws IOException {
        if (entities == null || entities.isEmpty()) {
            json.name("entities").nullValue();
            return;
        }
        json.name("entities").beginArray();
        for (Map.Entry<String, String> e : entities.entrySet()) {
            json.beginArray();
            json.value(e.getKey());
            json.beginArray();
            for (String part : e.getValue().split(", ")) {
                int idx = part.lastIndexOf(" x");
                if (idx <= 0) {
                    continue;
                }
                try {
                    long count = Long.parseLong(part.substring(idx + 2).trim());
                    json.beginArray();
                    json.value(part.substring(0, idx).trim());
                    json.value(count);
                    json.endArray();
                } catch (NumberFormatException ignored) {
                }
            }
            json.endArray();
            json.endArray();
        }
        json.endArray();
    }

    static void writeThreads(JsonWriter json, HealthSnapshot snapshot, JfrTimeline timeline)
        throws IOException {
        HealthSnapshot.Threads threads = snapshot == null ? null : snapshot.threads();
        ReportJson.writeNullable(json, "threadTotal", threads != null && threads.total() > 0 ? (long) threads.total() : null);
        if (threads == null || threads.byState().isEmpty()) {
            json.name("threadStates").nullValue();
        } else {
            json.name("threadStates").beginObject();
            for (Map.Entry<String, Integer> e : threads.byState().entrySet()) {
                json.name(e.getKey()).value(e.getValue());
            }
            json.endObject();
        }
        if (threads == null || threads.deadlocked().isEmpty()) {
            json.name("deadlocked").nullValue();
        } else {
            json.name("deadlocked").beginArray();
            for (String d : threads.deadlocked()) {
                json.value(d);
            }
            json.endArray();
        }

        json.name("hotThreads").beginArray();
        if (snapshot != null) {
            Map<String, String> states = timeline == null ? Map.of() : timeline.threadStates();
            for (HealthSnapshot.HotThread hot : snapshot.hotThreads()) {
                json.beginArray();
                json.value(hot.samples());
                json.value(states.get(hot.thread()));
                json.value(hot.thread());
                json.value(hot.topMethod());
                json.beginArray();
                for (HealthSnapshot.MethodSample m : hot.methods()) {
                    json.beginArray();
                    json.value(m.method());
                    json.value(m.samples());
                    json.endArray();
                }
                json.endArray();
                json.endArray();
            }
        }
        json.endArray();
    }

    static void writeHotMethods(JsonWriter json, JfrTimeline timeline) throws IOException {
        json.name("hotMethods").beginArray();
        if (timeline != null) {
            for (JfrTimeline.HotMethod hm : timeline.hotMethods()) {
                json.beginArray();
                json.value(hm.samples());
                json.value(hm.method());
                json.beginArray();
                for (String caller : hm.callers()) {
                    json.value(caller);
                }
                json.endArray();
                json.endArray();
            }
        }
        json.endArray();
    }

    static void writeWorlds(JsonWriter json, IncidentMetadata meta, HealthSnapshot snapshot,
                            JfrTimeline timeline) throws IOException {
        Map<String, Long> samples = timeline == null ? Map.of() : timeline.worldSamples();
        json.name("worlds").beginArray();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (snapshot != null) {
            for (HealthSnapshot.World w : snapshot.worlds()) {
                seen.add(worldKey(w.name()));
                json.beginObject();
                json.name("name").value(w.name());
                ReportJson.writeNullable(json, "samples", samplesForWorld(samples, w.name()));
                json.name("stalled").value(w.name().equals(meta.world()));
                ReportJson.writeNullable(json, "players", w.players() >= 0 ? (long) w.players() : null);
                if (w.playerNames().isEmpty()) {
                    json.name("playerNames").nullValue();
                } else {
                    json.name("playerNames").beginArray();
                    for (String name : w.playerNames()) {
                        json.value(name);
                    }
                    json.endArray();
                }
                ReportJson.writeNullable(json, "entities", w.entities() >= 0 ? (long) w.entities() : null);
                ReportJson.writeNullable(json, "chunks", w.chunks() >= 0 ? (long) w.chunks() : null);
                long[] churn = timeline == null ? null : timeline.chunkChurnByWorld().get(w.name());
                ReportJson.writeNullable(json, "chunksGenerated", churn != null && churn[0] >= 0 ? churn[0] : null);
                ReportJson.writeNullable(json, "chunksLoaded", churn != null && churn[1] >= 0 ? churn[1] : null);
                ReportJson.writeNullableDouble(json, "tps", w.tps() >= 0 ? w.tps() : null);
                ReportJson.writeNullableDouble(json, "mspt", w.mspt() >= 0 ? w.mspt() : null);
                ReportJson.writeNullableDouble(json, "msptP50", w.msptP50() >= 0 ? w.msptP50() : null);
                ReportJson.writeNullableDouble(json, "msptP95", w.msptP95() >= 0 ? w.msptP95() : null);
                ReportJson.writeNullableDouble(json, "msptMax", w.msptMax() >= 0 ? w.msptMax() : null);
                json.endObject();
            }
        }
        for (Map.Entry<String, Long> e : samples.entrySet()) {
            if (!seen.add(worldKey(e.getKey()))) {
                continue;
            }
            json.beginObject();
            json.name("name").value(e.getKey());
            json.name("samples").value(e.getValue());
            json.name("stalled").value(e.getKey().equals(meta.world()));
            json.name("players").nullValue();
            json.name("playerNames").nullValue();
            json.name("entities").nullValue();
            json.name("chunks").nullValue();
            json.name("chunksGenerated").nullValue();
            json.name("chunksLoaded").nullValue();
            json.name("tps").nullValue();
            json.name("mspt").nullValue();
            json.name("msptP50").nullValue();
            json.name("msptP95").nullValue();
            json.name("msptMax").nullValue();
            json.endObject();
        }
        json.endArray();
    }

    private static String worldKey(String world) {
        String s = ReportHtml.worldShort(world);
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static Long samplesForWorld(Map<String, Long> samples, String name) {
        Long exact = samples.get(name);
        if (exact != null) {
            return exact;
        }
        String key = worldKey(name);
        long sum = 0;
        boolean found = false;
        for (Map.Entry<String, Long> e : samples.entrySet()) {
            if (worldKey(e.getKey()).equals(key)) {
                sum += e.getValue();
                found = true;
            }
        }
        return found ? sum : null;
    }

    private static long samplesOrZero(JfrTimeline timeline, String world) {
        Long matched = samplesForWorld(timeline.worldSamples(), world);
        return matched == null ? 0L : matched;
    }

    static void writeWorldTps(JsonWriter json, JfrTimeline timeline, TickSelection tick)
        throws IOException {
        if (timeline == null || timeline.ticks().isEmpty()) {
            json.name("worldTps").nullValue();
            return;
        }
        Map<String, JfrTimeline.WorldTicks> ticks = timeline.ticks();
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (tick != null) {
            selected.add(tick.world());
        }
        ticks.keySet().stream()
            .sorted((a, b) -> Long.compare(samplesOrZero(timeline, b), samplesOrZero(timeline, a)))
            .forEach(selected::add);
        json.name("worldTps").beginArray();
        for (String world : selected) {
            JfrTimeline.WorldTicks wt = ticks.get(world);
            if (wt == null || wt.tps() == null) {
                continue;
            }
            json.beginObject();
            json.name("name").value(world);
            ReportJson.writeDoubleSeries(json, "series", wt.tps());
            json.endObject();
        }
        json.endArray();
    }

    static void writeSubsystems(JsonWriter json, JfrTimeline timeline) throws IOException {
        if (timeline == null || timeline.subsystems().isEmpty()) {
            json.name("subsystems").nullValue();
            return;
        }
        json.name("subsystems").beginArray();
        for (Map.Entry<String, Long> e : timeline.subsystems().entrySet()) {
            json.beginArray();
            json.value(e.getKey());
            json.value(e.getValue());
            json.endArray();
        }
        json.endArray();
    }

    static void writeCpuByWorld(JsonWriter json, JfrTimeline timeline) throws IOException {
        List<JfrTimeline.WorldCpu> worlds = timeline == null ? List.of() : timeline.cpuByWorld();
        if (worlds.isEmpty()) {
            json.name("cpuByWorld").nullValue();
            return;
        }
        json.name("cpuByWorld").beginArray();
        for (JfrTimeline.WorldCpu w : worlds) {
            json.beginObject();
            json.name("world").value(w.world());
            json.name("samples").value(w.samples());
            json.name("subsystems").beginArray();
            for (Map.Entry<String, Long> e : w.subsystems().entrySet()) {
                json.beginArray();
                json.value(e.getKey());
                json.value(e.getValue());
                json.endArray();
            }
            json.endArray();
            json.name("mods").beginArray();
            for (Map.Entry<String, Long> e : w.mods().entrySet()) {
                json.beginArray();
                json.value(e.getKey());
                json.value(e.getValue());
                json.endArray();
            }
            json.endArray();
            json.endObject();
        }
        json.endArray();
    }
}
