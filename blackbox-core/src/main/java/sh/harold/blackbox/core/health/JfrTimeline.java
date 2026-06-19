package sh.harold.blackbox.core.health;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class JfrTimeline {
    public static final int BUCKETS = 240;

    public record GcStats(long collections, double maxPauseMs, double totalPauseMs) {}

    public record GcConfig(String young, String old, long parallelThreads, long concurrentThreads,
                           long gcTimeRatio, Boolean explicitConcurrent, Boolean explicitDisabled) {}

    public record CpuStats(double jvmAvg, double jvmMax, double machAvg, double machMax) {}

    public record CpuInfo(String brand, long sockets, long cores, long hwThreads) {}

    public record HotMethod(String method, long samples, List<String> callers) {}

    public record Alloc(long totalBytes, Map<String, Long> bySubsystem, Map<String, Long> byThread,
                        Map<String, Long> byClass, Map<String, Long> byMod, List<WorldAlloc> byWorld) {}

    public record WorldCpu(String world, long samples, Map<String, Long> subsystems, Map<String, Long> mods) {}

    public record WorldAlloc(String world, long bytes, Map<String, Long> bySubsystem, Map<String, Long> byMod) {}

    public record WorldTicks(double[] tps, double[] mspt) {}

    public record ExplicitGc(String caller, long count) {}

    public record Container(String type, double cpuLimit, long memLimit) {}

    public record PluginEvent(long timeMs, String category, String message) {}

    public record ConnEvent(long timeMs, String player, String phase, String detail) {}

    public record PluginMetric(String name, String kind, double[] series) {}

    public record Retained(String className, long count, String allocSite) {}

    public record SystemTick(String name, double[] series) {}

    public record HostCpu(long throttledPeriods, long throttledMs, double stealPct, double iowaitPct,
                          double cpuUsageCores) {}

    public record Safepoint(long count, double totalMs) {}

    public record SlowIo(String path, double durationMs, long bytes, String kind) {}

    public record GcCause(String cause, long count, double totalPauseMs) {}

    public record Flame(String name, long samples, List<Flame> children, int owner) {}

    public record ThreadLane(String name, int[] states) {}

    public record ThreadShare(String thread, long samples) {}

    public record ModCpu(String id, long samples, String method, List<ThreadShare> threads) {}

    public record NetIface(String iface, double inAvg, double inPeak, double outAvg, double outPeak) {}

    private final int buckets;
    private final Instant start;
    private final Instant end;
    private final long totalSamples;
    private final CpuStats cpu;
    private final double[] cpuMachineSeries;
    private final double[] cpuJvmSeries;
    private final long[] heapSeries;
    private final long[] rssSeries;
    private final String netInterface;
    private final double[] netInSeries;
    private final double[] netOutSeries;
    private final long[] threadSeries;
    private final double[] gcPauseSeries;
    private final GcStats gc;
    private final GcConfig gcConfig;
    private final List<ExplicitGc> explicitGcs;
    private final long nmtCommitted;
    private final CpuInfo cpuInfo;
    private final Map<String, String> sysProps;
    private final long heapMax;
    private final List<HotMethod> hotMethods;
    private final Map<String, Long> worldSamples;
    private final Map<String, Long> subsystems;
    private final List<WorldCpu> cpuByWorld;
    private final Map<String, String> threadStates;
    private final Map<String, WorldTicks> ticks;
    private final double[] playersSeries;
    private final String osName;
    private final Alloc alloc;
    private final double[] exceptionsSeries;
    private final long[] explicitGcTimes;
    private final String jvmArgs;
    private final String javaArgs;
    private final Container container;
    private final double[] pingSeries;
    private final List<PluginEvent> pluginEvents;
    private final List<ConnEvent> connEvents;
    private final double[] connectsSeries;
    private final double[] joinsSeries;
    private final double[] leavesSeries;
    private final List<PluginMetric> pluginMetrics;
    private final List<Retained> retained;
    private final List<SystemTick> tickSeries;
    private final HostCpu hostCpu;
    private final Safepoint safepoint;
    private final List<GcCause> gcCauses;
    private final int[] gcOverlapBuckets;
    private final Flame flame;
    private final List<ThreadLane> threadTimeline;
    private final List<ModCpu> cpuByMod;
    private final long[] diskFreeSeries;
    private final long diskTotal;
    private final double[] diskReadSeries;
    private final double[] diskWriteSeries;
    private final List<SlowIo> slowIo;
    private final double[] entitiesSeries;
    private final double[] chunksSeries;
    private final Map<String, long[]> chunkChurnByWorld;
    private final double[] chunksGeneratedSeries;
    private final double[] chunksLoadedSeries;
    private final long[] heapCommittedSeries;
    private final long[] hostMemSeries;
    private final long swapFree;
    private final long swapTotal;
    private final List<NetIface> netOthers;

    JfrTimeline(int buckets, Instant start, Instant end, long totalSamples, CpuStats cpu,
                double[] cpuMachineSeries, double[] cpuJvmSeries, long[] heapSeries, long[] rssSeries,
                String netInterface, double[] netInSeries, double[] netOutSeries, long[] threadSeries,
                double[] gcPauseSeries, GcStats gc, GcConfig gcConfig, List<ExplicitGc> explicitGcs,
                long nmtCommitted, CpuInfo cpuInfo, Map<String, String> sysProps, long heapMax,
                List<HotMethod> hotMethods, Map<String, Long> worldSamples, Map<String, Long> subsystems,
                List<WorldCpu> cpuByWorld,
                Map<String, String> threadStates, Map<String, WorldTicks> ticks, double[] playersSeries,
                String osName, Alloc alloc, double[] exceptionsSeries,
                long[] explicitGcTimes, String jvmArgs, String javaArgs,
                Container container, double[] pingSeries, List<PluginEvent> pluginEvents,
                List<ConnEvent> connEvents,
                double[] connectsSeries, double[] joinsSeries, double[] leavesSeries,
                List<PluginMetric> pluginMetrics, List<Retained> retained,
                List<SystemTick> tickSeries, List<GcCause> gcCauses, int[] gcOverlapBuckets,
                Flame flame, List<ThreadLane> threadTimeline, List<ModCpu> cpuByMod,
                long[] diskFreeSeries, long diskTotal,
                double[] diskReadSeries, double[] diskWriteSeries, List<SlowIo> slowIo,
                double[] entitiesSeries, double[] chunksSeries,
                Map<String, long[]> chunkChurnByWorld,
                double[] chunksGeneratedSeries, double[] chunksLoadedSeries,
                long[] heapCommittedSeries, long[] hostMemSeries,
                long swapFree, long swapTotal, List<NetIface> netOthers,
                HostCpu hostCpu, Safepoint safepoint) {
        this.buckets = buckets;
        this.start = start;
        this.end = end;
        this.totalSamples = totalSamples;
        this.cpu = cpu;
        this.cpuMachineSeries = cpuMachineSeries;
        this.cpuJvmSeries = cpuJvmSeries;
        this.heapSeries = heapSeries;
        this.rssSeries = rssSeries;
        this.netInterface = netInterface;
        this.netInSeries = netInSeries;
        this.netOutSeries = netOutSeries;
        this.threadSeries = threadSeries;
        this.gcPauseSeries = gcPauseSeries;
        this.gc = gc;
        this.gcConfig = gcConfig;
        this.explicitGcs = explicitGcs;
        this.nmtCommitted = nmtCommitted;
        this.cpuInfo = cpuInfo;
        this.sysProps = sysProps;
        this.heapMax = heapMax;
        this.hotMethods = hotMethods;
        this.worldSamples = worldSamples;
        this.subsystems = subsystems;
        this.cpuByWorld = cpuByWorld;
        this.threadStates = threadStates;
        this.ticks = ticks;
        this.playersSeries = playersSeries;
        this.osName = osName;
        this.alloc = alloc;
        this.exceptionsSeries = exceptionsSeries;
        this.explicitGcTimes = explicitGcTimes;
        this.jvmArgs = jvmArgs;
        this.javaArgs = javaArgs;
        this.container = container;
        this.pingSeries = pingSeries;
        this.pluginEvents = pluginEvents;
        this.connEvents = connEvents;
        this.connectsSeries = connectsSeries;
        this.joinsSeries = joinsSeries;
        this.leavesSeries = leavesSeries;
        this.pluginMetrics = pluginMetrics;
        this.retained = retained;
        this.tickSeries = tickSeries;
        this.gcCauses = gcCauses;
        this.gcOverlapBuckets = gcOverlapBuckets;
        this.flame = flame;
        this.threadTimeline = threadTimeline;
        this.cpuByMod = cpuByMod;
        this.diskFreeSeries = diskFreeSeries;
        this.diskReadSeries = diskReadSeries;
        this.diskWriteSeries = diskWriteSeries;
        this.slowIo = slowIo;
        this.diskTotal = diskTotal;
        this.entitiesSeries = entitiesSeries;
        this.chunksSeries = chunksSeries;
        this.chunkChurnByWorld = chunkChurnByWorld;
        this.chunksGeneratedSeries = chunksGeneratedSeries;
        this.chunksLoadedSeries = chunksLoadedSeries;
        this.heapCommittedSeries = heapCommittedSeries;
        this.hostMemSeries = hostMemSeries;
        this.swapFree = swapFree;
        this.swapTotal = swapTotal;
        this.netOthers = netOthers;
        this.hostCpu = hostCpu;
        this.safepoint = safepoint;
    }

    public static JfrTimeline parse(Path recording) {
        return JfrTimelineParser.parse(recording);
    }

    public int buckets() { return buckets; }
    public Instant start() { return start; }
    public Instant end() { return end; }
    public long totalSamples() { return totalSamples; }
    public CpuStats cpu() { return cpu; }
    public double[] cpuMachineSeries() { return cpuMachineSeries; }
    public double[] cpuJvmSeries() { return cpuJvmSeries; }
    public long[] heapSeries() { return heapSeries; }
    public long[] rssSeries() { return rssSeries; }
    public String netInterface() { return netInterface; }
    public double[] netInSeries() { return netInSeries; }
    public double[] netOutSeries() { return netOutSeries; }
    public long[] threadSeries() { return threadSeries; }
    public double[] gcPauseSeries() { return gcPauseSeries; }
    public GcStats gc() { return gc; }
    public GcConfig gcConfig() { return gcConfig; }
    public List<ExplicitGc> explicitGcs() { return explicitGcs; }
    public long nmtCommitted() { return nmtCommitted; }
    public CpuInfo cpuInfo() { return cpuInfo; }
    public Map<String, String> sysProps() { return sysProps; }
    public long heapMax() { return heapMax; }
    public List<HotMethod> hotMethods() { return hotMethods; }
    public Map<String, Long> worldSamples() { return worldSamples; }
    public Map<String, Long> subsystems() { return subsystems; }
    public List<WorldCpu> cpuByWorld() { return cpuByWorld; }
    public Map<String, String> threadStates() { return threadStates; }
    public Map<String, WorldTicks> ticks() { return ticks; }
    public double[] playersSeries() { return playersSeries; }
    public String osName() { return osName; }
    public Alloc alloc() { return alloc; }
    public double[] exceptionsSeries() { return exceptionsSeries; }
    public long[] explicitGcTimes() { return explicitGcTimes; }
    public String jvmArgs() { return jvmArgs; }
    public String javaArgs() { return javaArgs; }
    public Container container() { return container; }
    public double[] pingSeries() { return pingSeries; }
    public long[] diskFreeSeries() { return diskFreeSeries; }
    public long diskTotal() { return diskTotal; }
    public double[] diskReadSeries() { return diskReadSeries; }
    public double[] diskWriteSeries() { return diskWriteSeries; }
    public List<SlowIo> slowIo() { return slowIo; }
    public List<PluginEvent> pluginEvents() { return pluginEvents; }
    public List<ConnEvent> connectionEvents() { return connEvents; }
    public double[] connectsSeries() { return connectsSeries; }
    public double[] joinsSeries() { return joinsSeries; }
    public double[] leavesSeries() { return leavesSeries; }
    public List<PluginMetric> pluginMetrics() { return pluginMetrics; }
    public List<Retained> retained() { return retained; }
    public List<SystemTick> tickSeries() { return tickSeries; }
    public HostCpu hostCpu() { return hostCpu; }
    public Safepoint safepoint() { return safepoint; }
    public List<GcCause> gcCauses() { return gcCauses; }
    public int[] gcOverlapBuckets() { return gcOverlapBuckets; }
    public Flame flame() { return flame; }
    public List<ThreadLane> threadTimeline() { return threadTimeline; }
    public List<ModCpu> cpuByMod() { return cpuByMod; }
    public double[] entitiesSeries() { return entitiesSeries; }
    public double[] chunksSeries() { return chunksSeries; }
    public Map<String, long[]> chunkChurnByWorld() { return chunkChurnByWorld; }
    public double[] chunksGeneratedSeries() { return chunksGeneratedSeries; }
    public double[] chunksLoadedSeries() { return chunksLoadedSeries; }
    public long[] heapCommittedSeries() { return heapCommittedSeries; }
    public long[] hostMemSeries() { return hostMemSeries; }
    public long swapFree() { return swapFree; }
    public long swapTotal() { return swapTotal; }
    public List<NetIface> netOthers() { return netOthers; }
}
