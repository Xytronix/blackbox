package sh.harold.blackbox.core.report;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sh.harold.blackbox.core.health.HealthSnapshot;
import sh.harold.blackbox.core.health.HostCpuStats;
import sh.harold.blackbox.core.health.JfrTimeline;
import sh.harold.blackbox.core.json.JsonWriter;

final class ReportSystemWriter {
    private static final Pattern INSTALLED_VERSION = Pattern.compile("installed \\((.+)\\)");
    private static final Pattern PLAYER_UUID = Pattern.compile(
        "(?<=players[/\\\\])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private ReportSystemWriter() {
    }

    static void writeSys(JsonWriter json, HealthSnapshot snapshot, JfrTimeline timeline,
                         String hytaleVersion, String serverName, String containerRuntime,
                         List<String[]> loaders)
        throws IOException {
        HealthSnapshot.Sys sys = snapshot == null ? null : snapshot.system();
        Map<String, String> props = timeline == null ? Map.of() : timeline.sysProps();
        JfrTimeline.CpuInfo cpuInfo = timeline == null ? null : timeline.cpuInfo();
        JfrTimeline.GcConfig gcConfig = timeline == null ? null : timeline.gcConfig();
        String osName = timeline == null ? null : timeline.osName();
        if (sys == null && props.isEmpty() && cpuInfo == null && gcConfig == null && osName == null
            && serverName == null && hytaleVersion == null && containerRuntime == null
            && loaders.isEmpty()) {
            json.name("sys").nullValue();
            return;
        }
        json.name("sys").beginObject();
        String distro = osName != null ? osName : (sys == null ? null : sys.os());
        String kernel = System.getProperty("os.version");
        json.name("os").value(distro != null && kernel != null && !distro.contains(kernel)
            ? distro + " · kernel " + kernel : distro);
        json.name("arch").value(prop(props, "os.arch"));
        writeRuntimeOsMetrics(json);
        JfrTimeline.HostCpu hostCpu = timeline == null ? null : timeline.hostCpu();
        ReportJson.writeNullable(json, "throttledPeriods",
            hostCpu != null && hostCpu.throttledPeriods() >= 0 ? hostCpu.throttledPeriods() : null);
        ReportJson.writeNullable(json, "throttledMs",
            hostCpu != null && hostCpu.throttledMs() >= 0 ? hostCpu.throttledMs() : null);
        ReportJson.writeNullableDouble(json, "cpuStealPct",
            hostCpu != null && hostCpu.stealPct() >= 0 ? hostCpu.stealPct() : null);
        ReportJson.writeNullableDouble(json, "cpuIowaitPct",
            hostCpu != null && hostCpu.iowaitPct() >= 0 ? hostCpu.iowaitPct() : null);
        ReportJson.writeNullableDouble(json, "cpuUsageCores",
            hostCpu != null && hostCpu.cpuUsageCores() >= 0 ? hostCpu.cpuUsageCores() : null);
        JfrTimeline.Safepoint safepoint = timeline == null ? null : timeline.safepoint();
        ReportJson.writeNullable(json, "safepointCount", safepoint != null && safepoint.count() > 0 ? safepoint.count() : null);
        ReportJson.writeNullableDouble(json, "safepointMs", safepoint != null && safepoint.count() > 0 ? safepoint.totalMs() : null);
        json.name("server").value(serverName);
        json.name("hytale").value(hytaleVersion);
        json.name("jvm").value(sys == null ? null : sys.jvm());
        json.name("javaVendor").value(props.getOrDefault("java.vendor", props.get("java.vm.vendor")));
        json.name("javaVersion").value(prop(props, "java.runtime.version"));
        json.name("javaHome").value(props.get("java.home"));
        json.name("cpuBrand").value(cpuInfo == null ? null : cpuInfo.brand());
        Long cpuCores = null;
        if (cpuInfo != null && cpuInfo.cores() > 0) {
            cpuCores = cpuInfo.cores();
        } else if (snapshot != null && snapshot.cpu() != null && snapshot.cpu().cores() > 0) {
            cpuCores = (long) snapshot.cpu().cores();
        }
        ReportJson.writeNullable(json, "cpuCores", cpuCores);
        ReportJson.writeNullable(json, "hwThreads", cpuInfo != null && cpuInfo.hwThreads() > 0 ? cpuInfo.hwThreads() : null);
        ReportJson.writeNullable(json, "sockets", cpuInfo != null && cpuInfo.sockets() > 0 ? cpuInfo.sockets() : null);
        ReportJson.writeNullable(json, "ramTotal", sys != null && sys.totalRamBytes() > 0 ? sys.totalRamBytes() : null);
        ReportJson.writeNullable(json, "uptimeMs", sys != null && sys.uptimeMs() > 0 ? sys.uptimeMs() : null);
        HealthSnapshot.Memory memory = snapshot == null ? null : snapshot.memory();
        if (memory == null || memory.nonHeapUsed() <= 0) {
            json.name("nonHeap").nullValue();
        } else {
            json.name("nonHeap").beginObject();
            json.name("used").value(memory.nonHeapUsed());
            ReportJson.writeNullable(json, "committed", memory.nonHeapCommitted() > 0 ? memory.nonHeapCommitted() : null);
            json.endObject();
        }
        if (timeline == null || timeline.swapTotal() <= 0) {
            json.name("swap").nullValue();
        } else {
            json.name("swap").beginObject();
            json.name("free").value(timeline.swapFree());
            json.name("total").value(timeline.swapTotal());
            json.endObject();
        }
        long[] hostMem = timeline == null ? null : timeline.hostMemSeries();
        ReportJson.writeNullable(json, "hostMemUsed", hostMem != null ? hostMem[hostMem.length - 1] : null);
        json.name("gcYoung").value(gcConfig == null ? null : gcConfig.young());
        json.name("gcOld").value(gcConfig == null ? null : gcConfig.old());
        ReportJson.writeNullable(json, "gcParallel", gcConfig != null && gcConfig.parallelThreads() > 0
            ? gcConfig.parallelThreads() : null);
        ReportJson.writeNullable(json, "gcConc", gcConfig != null && gcConfig.concurrentThreads() > 0
            ? gcConfig.concurrentThreads() : null);
        ReportJson.writeNullable(json, "gcTimeRatio", gcConfig != null && gcConfig.gcTimeRatio() > 0
            ? gcConfig.gcTimeRatio() : null);
        ReportJson.writeNullable(json, "xmxBytes", heapMax(snapshot, timeline));
        json.name("startCommand").value(startCommand(timeline));
        writeDisk(json, snapshot, timeline);
        if (loaders.isEmpty()) {
            json.name("loaders").nullValue();
        } else {
            json.name("loaders").beginArray();
            for (String[] loader : loaders) {
                json.beginArray();
                json.value(loader[0]);
                json.value(loader[1]);
                json.endArray();
            }
            json.endArray();
        }
        JfrTimeline.Container container = timeline == null ? null : timeline.container();
        if (container == null && containerRuntime == null) {
            json.name("container").nullValue();
        } else {
            json.name("container").beginObject();
            json.name("runtime").value(containerRuntime);
            json.name("type").value(container == null ? null : container.type());
            ReportJson.writeNullableDouble(json, "cpuLimit",
                container != null && container.cpuLimit() > 0 ? container.cpuLimit() : null);
            ReportJson.writeNullable(json, "memLimit",
                container != null && container.memLimit() > 0 ? container.memLimit() : null);
            json.endObject();
        }
        json.endObject();
    }

    private static void writeRuntimeOsMetrics(JsonWriter json) throws IOException {
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        double load = os == null ? -1 : os.getSystemLoadAverage();
        ReportJson.writeNullableDouble(json, "loadAvg", load >= 0 ? ReportJson.round2(load) : null);
        Long openFds = null;
        Long maxFds = null;
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
            long open = unix.getOpenFileDescriptorCount();
            long max = unix.getMaxFileDescriptorCount();
            openFds = open >= 0 ? open : null;
            maxFds = max > 0 ? max : null;
        }
        ReportJson.writeNullable(json, "openFds", openFds);
        ReportJson.writeNullable(json, "maxFds", maxFds);
        long[] mem = HostCpuStats.cgroupMemory();
        ReportJson.writeNullable(json, "containerMemUsed", mem != null && mem[0] >= 0 ? mem[0] : null);
        ReportJson.writeNullable(json, "oomKills", mem != null && mem[1] >= 0 ? mem[1] : null);
        double[] psi = HostCpuStats.psi();
        ReportJson.writeNullableDouble(json, "psiCpu", psi != null && psi[0] >= 0 ? ReportJson.round2(psi[0]) : null);
        ReportJson.writeNullableDouble(json, "psiIo", psi != null && psi[1] >= 0 ? ReportJson.round2(psi[1]) : null);
        ReportJson.writeNullableDouble(json, "psiMem", psi != null && psi[2] >= 0 ? ReportJson.round2(psi[2]) : null);
        long[] swap = HostCpuStats.cgroupSwap();
        ReportJson.writeNullable(json, "containerSwapUsed", swap != null && swap[0] >= 0 ? swap[0] : null);
        ReportJson.writeNullable(json, "containerSwapMax", swap != null && swap[1] >= 0 ? swap[1] : null);
        long[] ioMax = HostCpuStats.cgroupIoMax();
        ReportJson.writeNullable(json, "ioReadLimit", ioMax != null && ioMax[0] >= 0 ? ioMax[0] : null);
        ReportJson.writeNullable(json, "ioWriteLimit", ioMax != null && ioMax[1] >= 0 ? ioMax[1] : null);
        json.name("cpuset").value(HostCpuStats.cpusetCpus());
    }

    private static String prop(Map<String, String> props, String key) {
        String value = props.get(key);
        return value != null ? value : System.getProperty(key);
    }

    static boolean isLoaderEntry(String value) {
        return value != null
            && (value.equals("installed") || INSTALLED_VERSION.matcher(value).matches());
    }

    static List<String[]> loaders(Map<String, String> environment) {
        if (environment == null) {
            return List.of();
        }
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String value = entry.getValue();
            if (!isLoaderEntry(value)) {
                continue;
            }
            Matcher m = INSTALLED_VERSION.matcher(value);
            out.add(new String[] {entry.getKey(), m.matches() ? m.group(1) : "installed"});
        }
        return out;
    }

    private static String startCommand(JfrTimeline timeline) {
        if (timeline == null || (timeline.jvmArgs() == null && timeline.javaArgs() == null)) {
            return null;
        }
        return ("java "
            + (timeline.jvmArgs() == null ? "" : timeline.jvmArgs()) + " "
            + (timeline.javaArgs() == null ? "" : timeline.javaArgs()))
            .replaceAll("\\s+", " ").trim();
    }

    private static void writeDisk(JsonWriter json, HealthSnapshot snapshot, JfrTimeline timeline)
        throws IOException {
        long free = -1;
        long total = -1;
        if (snapshot != null && snapshot.disk() != null && snapshot.disk().totalBytes() > 0) {
            total = snapshot.disk().totalBytes();
            free = total - Math.max(0, snapshot.disk().usedBytes());
        } else if (timeline != null && timeline.diskFreeSeries() != null) {
            long[] series = timeline.diskFreeSeries();
            free = series[series.length - 1];
            total = timeline.diskTotal();
        }
        if (free < 0 || total <= 0) {
            json.name("disk").nullValue();
            return;
        }
        json.name("disk").beginObject();
        json.name("free").value(free);
        json.name("total").value(total);
        json.endObject();
    }

    private static Long heapMax(HealthSnapshot snapshot, JfrTimeline timeline) {
        if (timeline != null && timeline.heapMax() > 0) {
            return timeline.heapMax();
        }
        if (snapshot != null && snapshot.memory() != null && snapshot.memory().heapMax() > 0) {
            return snapshot.memory().heapMax();
        }
        return null;
    }

    static void writeCpu(JsonWriter json, JfrTimeline timeline) throws IOException {
        JfrTimeline.CpuStats cpu = timeline == null ? null : timeline.cpu();
        if (cpu == null) {
            json.name("cpu").nullValue();
            return;
        }
        json.name("cpu").beginObject();
        json.name("jvmAvg").value(cpu.jvmAvg());
        json.name("jvmMax").value(cpu.jvmMax());
        json.name("machAvg").value(cpu.machAvg());
        json.name("machMax").value(cpu.machMax());
        json.endObject();
    }

    static void writeHeap(JsonWriter json, HealthSnapshot snapshot, JfrTimeline timeline) throws IOException {
        long[] series = timeline == null ? null : timeline.heapSeries();
        Long max = heapMax(snapshot, timeline);
        Long committed = snapshot != null && snapshot.memory() != null && snapshot.memory().heapCommitted() > 0
            ? snapshot.memory().heapCommitted() : null;
        if (series != null) {
            long peak = Long.MIN_VALUE;
            long floor = Long.MAX_VALUE;
            for (long v : series) {
                peak = Math.max(peak, v);
                floor = Math.min(floor, v);
            }
            json.name("heap").beginObject();
            json.name("peak").value(peak);
            json.name("floor").value(floor);
            ReportJson.writeNullable(json, "max", max);
            ReportJson.writeNullable(json, "committed", committed);
            json.endObject();
            return;
        }
        if (snapshot != null && snapshot.memory() != null && snapshot.memory().heapUsed() > 0) {
            json.name("heap").beginObject();
            json.name("peak").value(snapshot.memory().heapUsed());
            json.name("floor").value(snapshot.memory().heapUsed());
            ReportJson.writeNullable(json, "max", max);
            ReportJson.writeNullable(json, "committed", committed);
            json.endObject();
            return;
        }
        json.name("heap").nullValue();
    }

    static void writeRam(JsonWriter json, JfrTimeline timeline) throws IOException {
        long[] rss = timeline == null ? null : timeline.rssSeries();
        if (rss == null) {
            json.name("ram").nullValue();
            return;
        }
        long peak = Long.MIN_VALUE;
        for (long v : rss) {
            peak = Math.max(peak, v);
        }
        json.name("ram").beginObject();
        json.name("rssLast").value(rss[rss.length - 1]);
        json.name("rssPeak").value(peak);
        json.name("nmtOn").value(timeline.nmtCommitted() >= 0);
        ReportJson.writeNullable(json, "nativeCommitted", timeline.nmtCommitted() >= 0 ? timeline.nmtCommitted() : null);
        json.endObject();
    }

    static void writeAlloc(JsonWriter json, JfrTimeline timeline) throws IOException {
        JfrTimeline.Alloc alloc = timeline == null ? null : timeline.alloc();
        if (alloc == null) {
            json.name("alloc").nullValue();
            return;
        }
        json.name("alloc").beginObject();
        json.name("total").value(alloc.totalBytes());
        json.name("bySubsystem").beginArray();
        for (Map.Entry<String, Long> e : alloc.bySubsystem().entrySet()) {
            json.beginArray();
            json.value(e.getKey());
            json.value(e.getValue());
            json.endArray();
        }
        json.endArray();
        json.name("byThread").beginArray();
        for (Map.Entry<String, Long> e : alloc.byThread().entrySet()) {
            json.beginArray();
            json.value(e.getKey());
            json.value(e.getValue());
            json.endArray();
        }
        json.endArray();
        json.name("byClass").beginArray();
        for (Map.Entry<String, Long> e : alloc.byClass().entrySet()) {
            json.beginArray();
            json.value(e.getKey());
            json.value(e.getValue());
            json.endArray();
        }
        json.endArray();
        json.name("byMod").beginArray();
        for (Map.Entry<String, Long> e : alloc.byMod().entrySet()) {
            json.beginArray();
            json.value(e.getKey());
            json.value(e.getValue());
            json.endArray();
        }
        json.endArray();
        json.name("byWorld").beginArray();
        for (JfrTimeline.WorldAlloc w : alloc.byWorld()) {
            json.beginObject();
            json.name("world").value(w.world());
            json.name("bytes").value(w.bytes());
            json.name("bySubsystem").beginArray();
            for (Map.Entry<String, Long> e : w.bySubsystem().entrySet()) {
                json.beginArray();
                json.value(e.getKey());
                json.value(e.getValue());
                json.endArray();
            }
            json.endArray();
            json.name("byMod").beginArray();
            for (Map.Entry<String, Long> e : w.byMod().entrySet()) {
                json.beginArray();
                json.value(e.getKey());
                json.value(e.getValue());
                json.endArray();
            }
            json.endArray();
            json.endObject();
        }
        json.endArray();
        json.endObject();
    }

    static void writeGc(JsonWriter json, HealthSnapshot snapshot, JfrTimeline timeline) throws IOException {
        JfrTimeline.GcStats gc = timeline == null ? null : timeline.gc();
        JfrTimeline.GcConfig cfg = timeline == null ? null : timeline.gcConfig();
        if (gc == null) {
            json.name("gc").nullValue();
        } else {
            json.name("gc").beginObject();
            json.name("collections").value(gc.collections());
            json.name("maxPauseMs").value(gc.maxPauseMs());
            json.name("totalPauseMs").value(gc.totalPauseMs());
            if (cfg != null && cfg.explicitDisabled() != null) {
                json.name("explicitDisabled").value(cfg.explicitDisabled());
            } else {
                json.name("explicitDisabled").nullValue();
            }
            if (cfg != null && cfg.explicitConcurrent() != null) {
                json.name("explicitConcurrent").value(cfg.explicitConcurrent());
            } else {
                json.name("explicitConcurrent").nullValue();
            }
            json.name("explicitCalls").beginArray();
            for (JfrTimeline.ExplicitGc call : timeline.explicitGcs()) {
                json.beginArray();
                json.value(friendlyGcCaller(call.caller()));
                json.value(call.count());
                json.endArray();
            }
            json.endArray();
            json.endObject();
        }

        List<HealthSnapshot.Gc> byCollector = snapshot == null ? List.of() : snapshot.gc();
        if (byCollector.isEmpty()) {
            json.name("gcByCollector").nullValue();
        } else {
            json.name("gcByCollector").beginArray();
            for (HealthSnapshot.Gc g : byCollector) {
                json.beginArray();
                json.value(g.name());
                json.value(g.count());
                json.value(g.totalTimeMs());
                json.endArray();
            }
            json.endArray();
        }
    }

    private static String friendlyGcCaller(String caller) {
        if (caller != null && caller.startsWith("sun.rmi.")) {
            return caller + " (JVM RMI DGC, Java standard, not the game)";
        }
        return caller;
    }

    static void writeGcCauses(JsonWriter json, JfrTimeline timeline) throws IOException {
        if (timeline == null || timeline.gcCauses().isEmpty()) {
            json.name("gcCauses").nullValue();
            return;
        }
        json.name("gcCauses").beginArray();
        for (JfrTimeline.GcCause cause : timeline.gcCauses()) {
            json.beginArray();
            json.value(cause.cause());
            json.value(cause.count());
            json.value(cause.totalPauseMs());
            json.endArray();
        }
        json.endArray();
    }

    static void writeGcOverlap(JsonWriter json, JfrTimeline timeline) throws IOException {
        int[] buckets = timeline == null ? null : timeline.gcOverlapBuckets();
        if (buckets == null || buckets.length == 0) {
            json.name("gcOverlap").nullValue();
            return;
        }
        json.name("gcOverlap").beginArray();
        for (int bucket : buckets) {
            json.value(bucket);
        }
        json.endArray();
    }

    static void writeNet(JsonWriter json, JfrTimeline timeline) throws IOException {
        double[] outSeries = timeline == null ? null : timeline.netOutSeries();
        if (timeline == null || timeline.netInterface() == null || outSeries == null) {
            json.name("net").nullValue();
            return;
        }
        double[] inSeries = timeline.netInSeries();
        json.name("net").beginObject();
        json.name("iface").value(timeline.netInterface());
        if (inSeries == null) {
            json.name("inAvg").nullValue();
            json.name("inPeak").nullValue();
        } else {
            json.name("inAvg").value(ReportJson.round2(ReportJson.avg(inSeries)));
            json.name("inPeak").value(ReportJson.round2(ReportJson.max(inSeries)));
        }
        json.name("outAvg").value(ReportJson.round2(ReportJson.avg(outSeries)));
        json.name("outPeak").value(ReportJson.round2(ReportJson.max(outSeries)));
        if (timeline.netOthers().isEmpty()) {
            json.name("others").nullValue();
        } else {
            json.name("others").beginArray();
            for (JfrTimeline.NetIface other : timeline.netOthers()) {
                json.beginArray();
                json.value(other.iface());
                json.value(other.inAvg());
                json.value(other.inPeak());
                json.value(other.outAvg());
                json.value(other.outPeak());
                json.endArray();
            }
            json.endArray();
        }
        json.endObject();
    }

    static void writeRetained(JsonWriter json, JfrTimeline timeline) throws IOException {
        List<JfrTimeline.Retained> retained = timeline == null ? List.of() : timeline.retained();
        if (retained.isEmpty()) {
            json.name("retained").nullValue();
            return;
        }
        json.name("retained").beginArray();
        for (JfrTimeline.Retained entry : retained) {
            json.beginArray();
            json.value(entry.className());
            json.value(entry.count());
            json.value(entry.allocSite());
            json.endArray();
        }
        json.endArray();
    }

    static void writeSlowIo(JsonWriter json, JfrTimeline timeline) throws IOException {
        List<JfrTimeline.SlowIo> slowIo = timeline == null ? List.of() : timeline.slowIo();
        if (slowIo.isEmpty()) {
            json.name("slowIo").nullValue();
            return;
        }
        Map<String, String> players = new LinkedHashMap<>();
        json.name("slowIo").beginArray();
        for (JfrTimeline.SlowIo entry : slowIo) {
            json.beginArray();
            json.value(maskPlayers(entry.path(), players));
            json.value(ReportJson.round2(entry.durationMs()));
            json.value(entry.bytes());
            json.value(entry.kind());
            json.endArray();
        }
        json.endArray();
    }

    private static String maskPlayers(String path, Map<String, String> players) {
        if (path == null) {
            return null;
        }
        Matcher m = PLAYER_UUID.matcher(path);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String label = players.computeIfAbsent(m.group(), k -> "player" + (players.size() + 1));
            m.appendReplacement(sb, Matcher.quoteReplacement(label));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static void writeHeapHistogram(JsonWriter json, Map<String, String> heapHistogram)
        throws IOException {
        if (heapHistogram == null || heapHistogram.isEmpty()) {
            json.name("heapHistogram").nullValue();
            return;
        }
        json.name("heapHistogram").beginArray();
        for (Map.Entry<String, String> e : heapHistogram.entrySet()) {
            String[] parts = e.getValue().trim().split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            try {
                long instances = Long.parseLong(parts[0]);
                long bytes = Long.parseLong(parts[1]);
                json.beginArray();
                json.value(e.getKey());
                json.value(instances);
                json.value(bytes);
                json.endArray();
            } catch (NumberFormatException ignored) {
            }
        }
        json.endArray();
    }

    static void writeMemPools(JsonWriter json, Map<String, String> memPools) throws IOException {
        if (memPools == null || memPools.isEmpty()) {
            json.name("memPools").nullValue();
            return;
        }
        json.name("memPools").beginArray();
        for (Map.Entry<String, String> e : memPools.entrySet()) {
            String[] parts = e.getValue().trim().split("\\s+");
            if (parts.length < 4) {
                continue;
            }
            try {
                long used = Long.parseLong(parts[0]);
                long committed = Long.parseLong(parts[1]);
                long max = Long.parseLong(parts[2]);
                long collectionUsed = Long.parseLong(parts[3]);
                json.beginArray();
                json.value(e.getKey());
                json.value(used);
                json.value(committed);
                json.value(max);
                json.value(collectionUsed);
                json.endArray();
            } catch (NumberFormatException ignored) {
            }
        }
        json.endArray();
    }
}
