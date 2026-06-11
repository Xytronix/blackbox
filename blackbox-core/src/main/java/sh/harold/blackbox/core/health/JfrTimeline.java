package sh.harold.blackbox.core.health;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedClassLoader;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

public final class JfrTimeline {
    public static final int BUCKETS = 240;
    private static final int MAX_BUCKETS = 960;
    private static final int HOT_METHOD_LIMIT = 50;
    private static final int CALLER_CHAIN_FRAMES = 5;
    private static final int CHAINS_PER_METHOD_CAP = 64;
    private static final int ALLOC_THREAD_LIMIT = 15;
    private static final int ALLOC_CLASS_LIMIT = 15;
    private static final int ALLOC_MOD_LIMIT = 10;
    private static final int PLUGIN_EVENT_CAP = 2000;
    private static final int PLUGIN_METRIC_LIMIT = 24;
    private static final int PLUGIN_METRIC_MIN_EVENTS = 2;
    private static final int RETAINED_LIMIT = 20;
    private static final int SLOW_IO_LIMIT = 15;
    private static final int SYSTEM_TICK_LIMIT = 24;
    private static final int SYSTEM_TICK_PER_WORLD = 4;
    private static final int SYSTEM_TICK_MIN_EVENTS = 2;
    private static final int SUBSYSTEM_LIMIT = 14;
    private static final int FLAME_DEPTH_LIMIT = 64;
    private static final int FLAME_NODE_CAP = 6000;
    private static final int FLAME_MIN_DIVISOR = 2000;
    private static final int THREAD_LANE_LIMIT = 25;
    private static final int MOD_CPU_LIMIT = 20;
    private static final int NET_OTHERS_LIMIT = 5;
    private static final String WORLD_THREAD_PREFIX = "WorldThread - ";
    private static final String HYTALE_PACKAGE = "com.hypixel.hytale.";

    public record GcStats(long collections, double maxPauseMs, double totalPauseMs) {}

    public record GcConfig(String young, String old, long parallelThreads, long concurrentThreads,
                           long gcTimeRatio, Boolean explicitConcurrent, Boolean explicitDisabled) {}

    public record CpuStats(double jvmAvg, double jvmMax, double machAvg, double machMax) {}

    public record CpuInfo(String brand, long sockets, long cores, long hwThreads) {}

    public record HotMethod(String method, long samples, List<String> callers) {}

    public record Alloc(long totalBytes, Map<String, Long> bySubsystem, Map<String, Long> byThread,
                        Map<String, Long> byClass, Map<String, Long> byMod) {}

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

    public record Flame(String name, long samples, List<Flame> children) {}

    public record ThreadLane(String name, int[] states) {}

    public record ThreadShare(String thread, long samples) {}

    public record ModCpu(String id, long samples, String method, List<ThreadShare> threads) {}

    public record NetIface(String iface, double inAvg, double inPeak, double outAvg, double outPeak) {}

    static final class FlameNode {
        final String name;
        long samples;
        final Map<String, FlameNode> children = new LinkedHashMap<>();

        FlameNode(String name) {
            this.name = name;
        }
    }

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
    private final long[] heapCommittedSeries;
    private final long[] hostMemSeries;
    private final long swapFree;
    private final long swapTotal;
    private final List<NetIface> netOthers;

    private JfrTimeline(int buckets, Instant start, Instant end, long totalSamples, CpuStats cpu,
                        double[] cpuMachineSeries, double[] cpuJvmSeries, long[] heapSeries, long[] rssSeries,
                        String netInterface, double[] netInSeries, double[] netOutSeries, long[] threadSeries,
                        double[] gcPauseSeries, GcStats gc, GcConfig gcConfig, List<ExplicitGc> explicitGcs,
                        long nmtCommitted, CpuInfo cpuInfo, Map<String, String> sysProps, long heapMax,
                        List<HotMethod> hotMethods, Map<String, Long> worldSamples, Map<String, Long> subsystems,
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
                        double[] entitiesSeries, long[] heapCommittedSeries, long[] hostMemSeries,
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
        this.heapCommittedSeries = heapCommittedSeries;
        this.hostMemSeries = hostMemSeries;
        this.swapFree = swapFree;
        this.swapTotal = swapTotal;
        this.netOthers = netOthers;
        this.hostCpu = hostCpu;
        this.safepoint = safepoint;
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
    public long[] heapCommittedSeries() { return heapCommittedSeries; }
    public long[] hostMemSeries() { return hostMemSeries; }
    public long swapFree() { return swapFree; }
    public long swapTotal() { return swapTotal; }
    public List<NetIface> netOthers() { return netOthers; }

    private record Pt(long t, double v) {}

    private static final String[][] SUBSYSTEM_PATTERNS = {
        {"Collision", ".modules.collision."},
        {"Entity ticking", "EntityTickingSystem", ".component.system.tick.", "ArchetypeTickingSystem"},
        {"World generation", ".hytalegenerator.", ".worldgen."},
        {"Chunk / palette", "SectionPalette", ".world.chunk.", "ChunkStore", "BlockSection"},
        {"NPC pathfinding", ".pathfinding.", "AStar", "MotionController", "Steering"},
        {"NPC / AI", ".builtin.npc", "Behavior", "Behaviour", "Sensor"},
        {"Spawning", ".spawning.", "SpawnMarker", "Spawner"},
        {"Entity stats", ".entitystats.", "EntityStatMap"},
        {"Fluid simulation", ".builtin.fluid.", "FluidSystems", "FluidSection"},
        {"Lighting", ".lighting.", "FloodLight"},
        {"Block ticking", ".builtin.blocktick.", "RandomTickSystem"},
        {"Physics", ".modules.physics.", ".blockphysics.", "Repulsion"},
        {"Projectiles", ".modules.projectile."},
        {"Items / inventory", ".modules.item.", "Inventory"},
        {"Interaction", ".modules.interaction.", "InteractionChain", "InteractionManager"},
        {"Crafting", ".crafting."},
        {"World map", "worldmap", "WorldMap"},
        {"Saving / storage", "ChunkSaving", "SavingSystem"},
        {"Persistence (Mongo)", "org.bson", "com.mongodb"},
        {"Networking", "io.netty.", ".protocol.", "PacketHandler"},
    };
    private static final String SUBSYSTEM_OTHER = "Other";

    private static final Set<String> WANTED_PROPS = Set.of(
        "java.vendor", "java.vm.vendor", "java.home", "java.runtime.version", "os.arch");

    public static JfrTimeline parse(Path recording) {
        List<Pt> cpuJvm = new ArrayList<>();
        List<Pt> cpuMach = new ArrayList<>();
        List<Pt> heap = new ArrayList<>();
        List<Pt> rss = new ArrayList<>();
        Map<String, List<Pt>> netIn = new HashMap<>();
        Map<String, List<Pt>> netOut = new HashMap<>();
        List<Pt> threads = new ArrayList<>();
        List<Pt> gcPause = new ArrayList<>();
        long safepointCount = 0;
        double safepointTotalMs = 0;
        long gcCount = 0;
        double gcMaxPause = 0;
        double gcTotalPause = 0;
        GcConfig gcConfig = null;
        Map<String, long[]> explicitGc = new LinkedHashMap<>();
        List<Long> explicitGcTimes = new ArrayList<>();
        String jvmArgs = null;
        String javaArgs = null;
        long nmtCommitted = -1;
        CpuInfo cpuInfo = null;
        Map<String, String> sysProps = new HashMap<>();
        long heapMax = -1;

        long totalSamples = 0;
        Map<String, long[]> samplesByMethod = new HashMap<>();
        Map<String, Map<String, long[]>> chainsByMethod = new HashMap<>();
        Map<String, long[]> worldSamples = new HashMap<>();
        Map<String, long[]> subsystems = new HashMap<>();
        Map<String, String> threadStates = new HashMap<>();
        Map<String, List<Pt>> tpsByWorld = new HashMap<>();
        Map<String, List<Pt>> msptByWorld = new HashMap<>();
        Map<String, List<Pt>> playersByWorld = new HashMap<>();
        Map<String, List<Pt>> entitiesByWorld = new HashMap<>();
        List<Pt> heapCommitted = new ArrayList<>();
        List<Pt> hostMem = new ArrayList<>();
        long swapFree = -1;
        long swapTotal = -1;
        List<Pt> diskFree = new ArrayList<>();
        long diskTotal = -1;
        List<Pt> diskRead = new ArrayList<>();
        List<Pt> diskWrite = new ArrayList<>();
        List<SlowIo> slowIo = new ArrayList<>();
        List<long[]> hostCpuSamples = new ArrayList<>();
        String osName = null;
        List<Pt> exceptionsCum = new ArrayList<>();
        long allocTotal = 0;
        Map<String, long[]> allocSubsystem = new HashMap<>();
        Map<String, long[]> allocThread = new HashMap<>();
        Map<String, long[]> allocClass = new HashMap<>();
        Map<String, long[]> allocMod = new HashMap<>();
        Container container = null;
        Map<String, List<Pt>> pingWeightedByWorld = new HashMap<>();
        Map<String, List<Pt>> pingWeightsByWorld = new HashMap<>();
        ArrayDeque<PluginEvent> pluginEvents = new ArrayDeque<>();
        ArrayDeque<ConnEvent> connEvents = new ArrayDeque<>();
        Map<String, List<Pt>> metricPts = new LinkedHashMap<>();
        Map<String, Boolean> metricIsCounter = new HashMap<>();
        Map<String, List<Pt>> systemTickPts = new LinkedHashMap<>();
        Map<String, long[]> oldObjectCounts = new HashMap<>();
        Map<String, Map<String, long[]>> oldObjectSites = new HashMap<>();
        Map<String, double[]> gcCauseAgg = new LinkedHashMap<>();
        List<long[]> gcWindows = new ArrayList<>();
        FlameNode flameRoot = new FlameNode("all");
        Map<String, List<Pt>> threadStatePts = new HashMap<>();
        Map<String, long[]> cpuModSamples = new LinkedHashMap<>();
        Map<String, Map<String, long[]>> cpuModMethods = new HashMap<>();
        Map<String, Map<String, long[]>> cpuModThreads = new HashMap<>();

        long minT = Long.MAX_VALUE;
        long maxT = Long.MIN_VALUE;
        boolean any = false;

        try (RecordingFile file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                RecordedEvent e = file.readEvent();
                long t = e.getEndTime() != null ? e.getEndTime().toEpochMilli() : -1;
                if (t > 0) {
                    minT = Math.min(minT, t);
                    maxT = Math.max(maxT, t);
                    any = true;
                }
                switch (e.getEventType().getName()) {
                    case "jdk.ExecutionSample" -> {
                        totalSamples++;
                        RecordedThread thread = e.getThread("sampledThread");
                        String threadName = thread == null ? null : thread.getJavaName();
                        if (threadName != null && threadName.startsWith(WORLD_THREAD_PREFIX)) {
                            worldSamples.computeIfAbsent(
                                threadName.substring(WORLD_THREAD_PREFIX.length()), k -> new long[1])[0]++;
                        }
                        if (threadName != null) {
                            String state = mapState(getS(e, "state"));
                            if (state != null) {
                                threadStates.put(threadName, state);
                                if (t > 0) {
                                    threadStatePts.computeIfAbsent(threadName, k -> new ArrayList<>())
                                        .add(new Pt(t, stateCode(state)));
                                }
                            }
                        }
                        recordStack(e.getStackTrace(), samplesByMethod, chainsByMethod, subsystems,
                            flameRoot, cpuModSamples, cpuModMethods, cpuModThreads, threadName);
                    }
                    case "jdk.CPULoad" -> {
                        cpuJvm.add(new Pt(t, 100.0 * (getD(e, "jvmUser") + getD(e, "jvmSystem"))));
                        cpuMach.add(new Pt(t, 100.0 * getD(e, "machineTotal")));
                    }
                    case "jdk.GCHeapSummary" -> {
                        heap.add(new Pt(t, getL(e, "heapUsed")));
                        long committed = heapSpaceCommitted(e);
                        if (committed > 0) {
                            heapCommitted.add(new Pt(t, committed));
                        }
                    }
                    case "jdk.PhysicalMemory" -> {
                        long used = getL(e, "usedSize");
                        if (used > 0) {
                            hostMem.add(new Pt(t, used));
                        }
                    }
                    case "jdk.SwapSpace" -> {
                        swapTotal = getL(e, "totalSize");
                        swapFree = getL(e, "freeSize");
                    }
                    case "jdk.GCHeapConfiguration" -> {
                        long mx = getL(e, "maxSize");
                        if (mx > 0) {
                            heapMax = mx;
                        }
                    }
                    case "jdk.ResidentSetSize" -> rss.add(new Pt(t, getL(e, "size")));
                    case "jdk.NetworkUtilization" -> {
                        String iface = getS(e, "networkInterface");
                        if (iface != null) {
                            netIn.computeIfAbsent(iface, k -> new ArrayList<>())
                                .add(new Pt(t, getL(e, "readRate") / 1e6));
                            netOut.computeIfAbsent(iface, k -> new ArrayList<>())
                                .add(new Pt(t, getL(e, "writeRate") / 1e6));
                        }
                    }
                    case "jdk.JavaThreadStatistics" -> threads.add(new Pt(t, getL(e, "activeCount")));
                    case "jdk.GarbageCollection" -> {
                        gcCount++;
                        double pauseMs = durationMs(e, "longestPause");
                        double sumMs = durationMs(e, "sumOfPauses");
                        if (sumMs <= 0) {
                            sumMs = pauseMs;
                        }
                        gcMaxPause = Math.max(gcMaxPause, pauseMs);
                        gcTotalPause += sumMs;
                        gcPause.add(new Pt(t, pauseMs));
                        String cause = getS(e, "cause");
                        if (cause != null && !cause.isBlank()) {
                            double[] agg = gcCauseAgg.computeIfAbsent(cause, k -> new double[2]);
                            agg[0]++;
                            agg[1] += sumMs;
                        }
                        if (t > 0 && pauseMs > 0) {
                            gcWindows.add(new long[] {t - (long) Math.ceil(pauseMs), t});
                        }
                    }
                    case "jdk.SystemGC" -> {
                        String caller = systemGcCaller(e.getStackTrace());
                        explicitGc.computeIfAbsent(caller, k -> new long[1])[0]++;
                        if (t > 0) {
                            explicitGcTimes.add(t);
                        }
                    }
                    case "jdk.JVMInformation" -> {
                        String jvm = getS(e, "jvmArguments");
                        String java = getS(e, "javaArguments");
                        if (jvm != null && !jvm.isBlank()) {
                            jvmArgs = jvm.trim();
                        }
                        if (java != null && !java.isBlank()) {
                            javaArgs = java.trim();
                        }
                    }
                    case "jdk.GCConfiguration" -> gcConfig = new GcConfig(
                        getS(e, "youngCollector"), getS(e, "oldCollector"),
                        getL(e, "parallelGCThreads"), getL(e, "concurrentGCThreads"),
                        getL(e, "gcTimeRatio"),
                        getB(e, "isExplicitGCConcurrent"), getB(e, "isExplicitGCDisabled"));
                    case "jdk.NativeMemoryUsageTotal" -> {
                        long committed = getL(e, "committed");
                        if (committed > 0) {
                            nmtCommitted = committed;
                        }
                    }
                    case "jdk.ExceptionStatistics" -> exceptionsCum.add(new Pt(t, getL(e, "throwables")));
                    case "jdk.ObjectAllocationSample" ->
                        allocTotal += recordAlloc(e, getL(e, "weight"), allocSubsystem, allocThread,
                            allocClass, allocMod);
                    case "jdk.ObjectAllocationInNewTLAB" ->
                        allocTotal += recordAlloc(e, getL(e, "tlabSize"), allocSubsystem, allocThread,
                            allocClass, allocMod);
                    case "jdk.ObjectAllocationOutsideTLAB" ->
                        allocTotal += recordAlloc(e, getL(e, "allocationSize"), allocSubsystem, allocThread,
                            allocClass, allocMod);
                    case "jdk.ContainerConfiguration" -> {
                        long quota = getL(e, "cpuQuota");
                        long period = getL(e, "cpuSlicePeriod");
                        long effective = getL(e, "effectiveCpuCount");
                        double cpuLimit = quota > 0 && period > 0 ? round2((double) quota / period)
                            : (effective > 0 ? effective : -1);
                        long memLimit = getL(e, "memoryLimit");
                        container = new Container(getS(e, "containerType"), cpuLimit,
                            memLimit > 0 ? memLimit : -1);
                    }
                    case "sh.harold.blackbox.SystemTick" -> {
                        String world = getS(e, "world");
                        String system = getS(e, "system");
                        if (t > 0 && world != null && !world.isBlank()
                            && system != null && !system.isBlank()) {
                            systemTickPts.computeIfAbsent(system + " @ " + world, k -> new ArrayList<>())
                                .add(new Pt(t, getD(e, "avgMs")));
                        }
                    }
                    case "sh.harold.blackbox.HostCpu" -> {
                        if (t > 0) {
                            hostCpuSamples.add(new long[] {
                                t,
                                getL(e, "throttledPeriods"),
                                getL(e, "throttledMicros"),
                                getL(e, "cpuTotalJiffies"),
                                getL(e, "cpuStealJiffies"),
                                getL(e, "cpuIowaitJiffies"),
                                getL(e, "cpuUsageUsec")
                            });
                        }
                    }
                    case "jdk.SafepointBegin" -> {
                        safepointCount++;
                        safepointTotalMs += durationMs(e, "duration");
                    }
                    case "sh.harold.blackbox.PluginMetric" -> {
                        String name = getS(e, "name");
                        if (t > 0 && name != null && !name.isBlank()) {
                            metricIsCounter.putIfAbsent(name, Boolean.TRUE.equals(getB(e, "counter")));
                            metricPts.computeIfAbsent(name, k -> new ArrayList<>())
                                .add(new Pt(t, getD(e, "value")));
                        }
                    }
                    case "jdk.OldObjectSample" -> {
                        String className = oldObjectClass(e);
                        if (className != null && !className.isBlank()) {
                            oldObjectCounts.computeIfAbsent(className, k -> new long[1])[0]++;
                            String site = topJavaFrame(e.getStackTrace());
                            if (site != null) {
                                oldObjectSites.computeIfAbsent(className, k -> new HashMap<>())
                                    .computeIfAbsent(site, k -> new long[1])[0]++;
                            }
                        }
                    }
                    case "sh.harold.blackbox.PluginEvent" -> {
                        if (t > 0) {
                            String category = getS(e, "category");
                            String message = getS(e, "message");
                            if ((category != null && !category.isBlank())
                                || (message != null && !message.isBlank())) {
                                pluginEvents.addLast(new PluginEvent(t,
                                    category == null ? "" : category, message == null ? "" : message));
                                if (pluginEvents.size() > PLUGIN_EVENT_CAP) {
                                    pluginEvents.removeFirst();
                                }
                            }
                        }
                    }
                    case "sh.harold.blackbox.Connection" -> {
                        String phase = getS(e, "phase");
                        if (t > 0 && phase != null && !phase.isBlank()) {
                            String player = getS(e, "player");
                            connEvents.addLast(new ConnEvent(t,
                                player == null ? "" : player, phase, getS(e, "detail")));
                            if (connEvents.size() > PLUGIN_EVENT_CAP) {
                                connEvents.removeFirst();
                            }
                        }
                    }
                    case "jdk.OSInformation" -> {
                        String parsed = parseOsName(getS(e, "osVersion"));
                        if (parsed != null) {
                            osName = parsed;
                        }
                    }
                    case "jdk.CPUInformation" -> cpuInfo = new CpuInfo(
                        cpuBrand(getS(e, "cpu"), getS(e, "description")),
                        getL(e, "sockets"), getL(e, "cores"), getL(e, "hwThreads"));
                    case "jdk.InitialSystemProperty" -> {
                        String key = getS(e, "key");
                        if (key != null && WANTED_PROPS.contains(key)) {
                            sysProps.putIfAbsent(key, getS(e, "value"));
                        }
                    }
                    case "sh.harold.blackbox.Disk" -> {
                        diskFree.add(new Pt(t, getL(e, "freeBytes")));
                        long total = getL(e, "totalBytes");
                        if (total > 0) {
                            diskTotal = total;
                        }
                        long read = getL(e, "readBytes");
                        if (read >= 0 && e.hasField("readBytes")) {
                            diskRead.add(new Pt(t, read));
                        }
                        long write = getL(e, "writeBytes");
                        if (write >= 0 && e.hasField("writeBytes")) {
                            diskWrite.add(new Pt(t, write));
                        }
                    }
                    case "jdk.FileRead" -> {
                        String path = getS(e, "path");
                        long bytes = getL(e, "bytesRead");
                        if (path != null || bytes > 0) {
                            slowIo.add(new SlowIo(path == null ? "<unknown>" : path,
                                durationMs(e, "duration"), bytes, "read"));
                        }
                    }
                    case "jdk.FileWrite" -> {
                        String path = getS(e, "path");
                        long bytes = getL(e, "bytesWritten");
                        if (path != null || bytes > 0) {
                            slowIo.add(new SlowIo(path == null ? "<unknown>" : path,
                                durationMs(e, "duration"), bytes, "write"));
                        }
                    }
                    case "sh.harold.blackbox.WorldTick" -> {
                        String world = getS(e, "world");
                        if (world != null) {
                            tpsByWorld.computeIfAbsent(world, k -> new ArrayList<>()).add(new Pt(t, getD(e, "tps")));
                            msptByWorld.computeIfAbsent(world, k -> new ArrayList<>()).add(new Pt(t, getD(e, "mspt")));
                            playersByWorld.computeIfAbsent(world, k -> new ArrayList<>())
                                .add(new Pt(t, getL(e, "players")));
                            long entityCount = getL(e, "entities");
                            if (entityCount >= 0 && e.hasField("entities")) {
                                entitiesByWorld.computeIfAbsent(world, k -> new ArrayList<>())
                                    .add(new Pt(t, entityCount));
                            }
                            double ping = getD(e, "avgPingMs");
                            long pingPlayers = getL(e, "players");
                            if (ping > 0 && pingPlayers > 0) {
                                pingWeightedByWorld.computeIfAbsent(world, k -> new ArrayList<>())
                                    .add(new Pt(t, ping * pingPlayers));
                                pingWeightsByWorld.computeIfAbsent(world, k -> new ArrayList<>())
                                    .add(new Pt(t, pingPlayers));
                            }
                        }
                    }
                    default -> { }
                }
            }
        } catch (Exception ex) {
            if (!any) {
                return null;
            }
        }
        if (!any) {
            return null;
        }

        final long lo = minT;
        final long hi = maxT;
        final int buckets = bucketCount(lo, hi);
        Instant start = Instant.ofEpochMilli(lo);
        Instant end = Instant.ofEpochMilli(hi);

        double[] cpuJvmSeries = bucketLast(cpuJvm, minT, maxT, buckets);
        double[] cpuMachSeries = bucketLast(cpuMach, minT, maxT, buckets);
        CpuStats cpuStats = (cpuJvmSeries != null && cpuMachSeries != null)
            ? new CpuStats(avg(cpuJvmSeries), max(cpuJvmSeries), avg(cpuMachSeries), max(cpuMachSeries))
            : null;

        String iface = null;
        double best = -1;
        for (Map.Entry<String, List<Pt>> en : netOut.entrySet()) {
            double sum = en.getValue().stream().mapToDouble(Pt::v).sum();
            if (sum > best) {
                best = sum;
                iface = en.getKey();
            }
        }

        GcStats gcStats = gcCount > 0
            ? new GcStats(gcCount, round2(gcMaxPause), round2(gcTotalPause))
            : null;

        List<ExplicitGc> explicit = new ArrayList<>();
        explicitGc.forEach((caller, count) -> explicit.add(new ExplicitGc(caller, count[0])));

        List<HotMethod> hot = topMethods(samplesByMethod, chainsByMethod);

        Map<String, Long> worlds = new LinkedHashMap<>();
        worldSamples.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
            .forEach(en -> worlds.put(en.getKey(), en.getValue()[0]));

        Map<String, Long> subs = orderSubsystems(subsystems);

        Map<String, WorldTicks> ticks = new LinkedHashMap<>();
        for (Map.Entry<String, List<Pt>> en : tpsByWorld.entrySet()) {
            double[] tps = bucketLast(en.getValue(), minT, maxT, buckets);
            double[] mspt = bucketLast(msptByWorld.getOrDefault(en.getKey(), List.of()), minT, maxT, buckets);
            if (tps != null) {
                ticks.put(en.getKey(), new WorldTicks(tps, mspt));
            }
        }
        double[] players = sumSeries(playersByWorld.values().stream()
            .map(pts -> bucketLast(pts, lo, hi, buckets)).toList());
        double[] entities = sumSeries(entitiesByWorld.values().stream()
            .map(pts -> bucketLast(pts, lo, hi, buckets)).toList());

        List<NetIface> netOthers = new ArrayList<>();
        if (iface != null) {
            final String primary = iface;
            Set<String> allIfaces = new java.util.LinkedHashSet<>(netIn.keySet());
            allIfaces.addAll(netOut.keySet());
            allIfaces.stream()
                .filter(name -> !name.equals(primary) && !name.equals("lo") && !name.equals("lo0"))
                .sorted((a, b) -> Double.compare(totalTraffic(netIn, netOut, b), totalTraffic(netIn, netOut, a)))
                .limit(NET_OTHERS_LIMIT)
                .forEach(name -> {
                    double[] in = bucketLast(netIn.getOrDefault(name, List.of()), lo, hi, buckets);
                    double[] outRate = bucketLast(netOut.getOrDefault(name, List.of()), lo, hi, buckets);
                    if (in != null && outRate != null) {
                        netOthers.add(new NetIface(name, avg(in), max(in), avg(outRate), max(outRate)));
                    }
                });
        }

        Alloc alloc = null;
        if (allocTotal > 0) {
            Map<String, Long> bySubsystem = orderSubsystems(allocSubsystem);
            Map<String, Long> byThread = new LinkedHashMap<>();
            allocThread.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(ALLOC_THREAD_LIMIT)
                .forEach(en -> byThread.put(en.getKey(), en.getValue()[0]));
            Map<String, Long> byClass = new LinkedHashMap<>();
            allocClass.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(ALLOC_CLASS_LIMIT)
                .forEach(en -> byClass.put(en.getKey(), en.getValue()[0]));
            Map<String, Long> byMod = new LinkedHashMap<>();
            allocMod.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(ALLOC_MOD_LIMIT)
                .forEach(en -> byMod.put(en.getKey(), en.getValue()[0]));
            alloc = new Alloc(allocTotal, bySubsystem, byThread, byClass, byMod);
        }

        List<PluginMetric> pluginMetrics = new ArrayList<>();
        metricPts.entrySet().stream()
            .filter(en -> en.getValue().size() >= PLUGIN_METRIC_MIN_EVENTS)
            .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
            .limit(PLUGIN_METRIC_LIMIT)
            .forEach(en -> {
                boolean isCounter = Boolean.TRUE.equals(metricIsCounter.get(en.getKey()));
                double[] series = isCounter
                    ? bucketSumZeroFilled(en.getValue(), lo, hi, buckets)
                    : bucketLast(en.getValue(), lo, hi, buckets);
                if (series != null) {
                    pluginMetrics.add(new PluginMetric(en.getKey(), isCounter ? "count" : "gauge", series));
                }
            });

        List<Retained> retained = new ArrayList<>();
        oldObjectCounts.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
            .limit(RETAINED_LIMIT)
            .forEach(en -> {
                Map<String, long[]> sites = oldObjectSites.get(en.getKey());
                String site = sites == null || sites.isEmpty() ? null : sites.entrySet().stream()
                    .max((a, b) -> Long.compare(a.getValue()[0], b.getValue()[0]))
                    .map(Map.Entry::getKey).orElse(null);
                retained.add(new Retained(en.getKey(), en.getValue()[0], site));
            });

        List<SystemTick> tickSeries = new ArrayList<>();
        Map<String, List<Map.Entry<String, List<Pt>>>> tickByWorld = new LinkedHashMap<>();
        systemTickPts.entrySet().stream()
            .filter(en -> en.getValue().size() >= SYSTEM_TICK_MIN_EVENTS)
            .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
            .forEach(en -> {
                int at = en.getKey().lastIndexOf(" @ ");
                String world = at >= 0 ? en.getKey().substring(at + 3) : "";
                tickByWorld.computeIfAbsent(world, k -> new ArrayList<>()).add(en);
            });
        List<Map.Entry<String, List<Pt>>> tickPicked = new ArrayList<>();
        tickByWorld.values().forEach(list ->
            tickPicked.addAll(list.subList(0, Math.min(SYSTEM_TICK_PER_WORLD, list.size()))));
        tickPicked.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        tickPicked.subList(0, Math.min(SYSTEM_TICK_LIMIT, tickPicked.size()))
            .forEach(en -> {
                double[] series = bucketLast(en.getValue(), lo, hi, buckets);
                if (series != null) {
                    tickSeries.add(new SystemTick(en.getKey(), series));
                }
            });

        List<GcCause> gcCauses = new ArrayList<>();
        gcCauseAgg.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
            .forEach(en -> gcCauses.add(
                new GcCause(en.getKey(), (long) en.getValue()[0], round2(en.getValue()[1]))));

        int[] gcOverlap = overlapBuckets(gcWindows, lo, hi, buckets);

        Flame flame = flamePrune(flameRoot, totalSamples);

        List<ThreadLane> threadTimeline = new ArrayList<>();
        threadStatePts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
            .limit(THREAD_LANE_LIMIT)
            .forEach(en -> threadTimeline.add(
                new ThreadLane(en.getKey(), modalStates(en.getValue(), lo, hi, buckets))));

        List<ModCpu> cpuByMod = new ArrayList<>();
        cpuModSamples.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
            .limit(MOD_CPU_LIMIT)
            .forEach(en -> {
                Map<String, long[]> threadMap = cpuModThreads.get(en.getKey());
                List<ThreadShare> modThreads = List.of();
                if (threadMap != null && !threadMap.isEmpty()) {
                    List<Map.Entry<String, long[]>> threadEntries = new ArrayList<>(threadMap.entrySet());
                    threadEntries.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
                    List<ThreadShare> ts = new ArrayList<>(Math.min(3, threadEntries.size()));
                    for (int i = 0; i < Math.min(3, threadEntries.size()); i++) {
                        ts.add(new ThreadShare(threadEntries.get(i).getKey(), threadEntries.get(i).getValue()[0]));
                    }
                    modThreads = List.copyOf(ts);
                }
                cpuByMod.add(new ModCpu(en.getKey(), en.getValue()[0],
                    dominantOf(cpuModMethods.get(en.getKey())), modThreads));
            });

        double[] ping = null;
        double[] pingWeighted = sumSeries(pingWeightedByWorld.values().stream()
            .map(pts -> bucketLast(pts, lo, hi, buckets)).toList());
        double[] pingWeights = sumSeries(pingWeightsByWorld.values().stream()
            .map(pts -> bucketLast(pts, lo, hi, buckets)).toList());
        if (pingWeighted != null && pingWeights != null) {
            ping = new double[pingWeighted.length];
            for (int i = 0; i < ping.length; i++) {
                ping[i] = pingWeights[i] > 0 ? pingWeighted[i] / pingWeights[i] : 0;
            }
        }

        return new JfrTimeline(
            buckets, start, end, totalSamples, cpuStats,
            cpuMachSeries, cpuJvmSeries,
            toLongs(bucketLast(heap, minT, maxT, buckets)),
            toLongs(bucketLast(rss, minT, maxT, buckets)),
            iface,
            iface == null ? null : bucketLast(netIn.getOrDefault(iface, List.of()), minT, maxT, buckets),
            iface == null ? null : bucketLast(netOut.getOrDefault(iface, List.of()), minT, maxT, buckets),
            toLongs(bucketLast(threads, minT, maxT, buckets)),
            bucketMaxZeroFilled(gcPause, minT, maxT, buckets),
            gcStats, gcConfig, explicit, nmtCommitted, cpuInfo,
            Map.copyOf(sysProps), heapMax, hot, worlds, subs,
            Map.copyOf(threadStates), ticks, players, osName, alloc,
            toDeltas(bucketLast(exceptionsCum, lo, hi, buckets)),
            explicitGcTimes.stream().mapToLong(Long::longValue).toArray(), jvmArgs, javaArgs,
            container, ping, List.copyOf(pluginEvents), List.copyOf(connEvents),
            countPerBucket(connEvents, "connect", lo, hi, buckets),
            countPerBucket(connEvents, "join", lo, hi, buckets),
            countPerBucket(connEvents, "leave", lo, hi, buckets),
            List.copyOf(pluginMetrics), List.copyOf(retained),
            List.copyOf(tickSeries), List.copyOf(gcCauses), gcOverlap, flame,
            List.copyOf(threadTimeline), List.copyOf(cpuByMod),
            toLongs(bucketLast(diskFree, lo, hi, buckets)), diskTotal,
            toRateMBps(bucketLast(diskRead, lo, hi, buckets), lo, hi, buckets),
            toRateMBps(bucketLast(diskWrite, lo, hi, buckets), lo, hi, buckets),
            topSlowIo(slowIo),
            entities,
            toLongs(bucketLast(heapCommitted, lo, hi, buckets)),
            toLongs(bucketLast(hostMem, lo, hi, buckets)),
            swapFree, swapTotal, List.copyOf(netOthers),
            computeHostCpu(hostCpuSamples),
            new Safepoint(safepointCount, round2(safepointTotalMs)));
    }

    private static double[] countPerBucket(Collection<ConnEvent> events, String phase,
                                           long lo, long hi, int buckets) {
        if (events.isEmpty() || hi <= lo) {
            return null;
        }
        double[] out = null;
        for (ConnEvent e : events) {
            if (!phase.equals(e.phase()) || e.timeMs() < lo || e.timeMs() > hi) {
                continue;
            }
            if (out == null) {
                out = new double[buckets];
            }
            int b = (int) ((e.timeMs() - lo) * buckets / (hi - lo + 1));
            out[Math.min(buckets - 1, Math.max(0, b))]++;
        }
        return out;
    }

    private static HostCpu computeHostCpu(List<long[]> samples) {
        if (samples.size() < 2) {
            return new HostCpu(-1, -1, -1, -1, -1);
        }
        long[] first = samples.get(0);
        long[] last = samples.get(0);
        for (long[] s : samples) {
            if (s[0] < first[0]) {
                first = s;
            }
            if (s[0] > last[0]) {
                last = s;
            }
        }
        long periods = first[1] >= 0 && last[1] >= first[1] ? last[1] - first[1] : -1;
        long throttledMs = first[2] >= 0 && last[2] >= first[2] ? (last[2] - first[2]) / 1000L : -1;
        double stealPct = -1;
        double iowaitPct = -1;
        if (first[3] > 0 && last[3] > first[3]) {
            long totalDelta = last[3] - first[3];
            if (totalDelta > 0) {
                if (first[4] >= 0 && last[4] >= first[4]) {
                    stealPct = round2(100.0 * (last[4] - first[4]) / totalDelta);
                }
                if (first[5] >= 0 && last[5] >= first[5]) {
                    iowaitPct = round2(100.0 * (last[5] - first[5]) / totalDelta);
                }
            }
        }
        double cpuUsageCores = -1;
        if (first[6] >= 0 && last[6] >= first[6]) {
            long windowUs = (last[0] - first[0]) * 1000L;
            if (windowUs > 0) {
                cpuUsageCores = round2((double) (last[6] - first[6]) / windowUs);
            }
        }
        return new HostCpu(periods, throttledMs, stealPct, iowaitPct, cpuUsageCores);
    }

    private static double totalTraffic(Map<String, List<Pt>> in, Map<String, List<Pt>> out, String iface) {
        double sum = 0;
        for (Pt p : in.getOrDefault(iface, List.of())) {
            sum += p.v();
        }
        for (Pt p : out.getOrDefault(iface, List.of())) {
            sum += p.v();
        }
        return sum;
    }

    private static long heapSpaceCommitted(RecordedEvent e) {
        try {
            Object space = e.hasField("heapSpace") ? e.getValue("heapSpace") : null;
            return space instanceof RecordedObject ro ? getL(ro, "committedSize") : -1;
        } catch (Exception ex) {
            return -1;
        }
    }

    private static double[] toRateMBps(double[] cumulative, long lo, long hi, int buckets) {
        double[] deltas = toDeltas(cumulative);
        if (deltas == null) {
            return null;
        }
        double bucketSeconds = (hi - lo) / 1000.0 / buckets;
        if (bucketSeconds <= 0) {
            return null;
        }
        for (int i = 0; i < deltas.length; i++) {
            deltas[i] = Math.max(0, deltas[i]) / bucketSeconds / 1e6;
        }
        return deltas;
    }

    private static List<SlowIo> topSlowIo(List<SlowIo> all) {
        all.sort((a, b) -> Double.compare(b.durationMs(), a.durationMs()));
        return List.copyOf(all.size() > SLOW_IO_LIMIT ? all.subList(0, SLOW_IO_LIMIT) : all);
    }

    private static Map<String, Long> orderSubsystems(Map<String, long[]> raw) {
        List<Map.Entry<String, Long>> named = new ArrayList<>();
        long otherTotal = 0;
        for (Map.Entry<String, long[]> en : raw.entrySet()) {
            if (SUBSYSTEM_OTHER.equals(en.getKey())) {
                otherTotal += en.getValue()[0];
            } else {
                named.add(Map.entry(en.getKey(), en.getValue()[0]));
            }
        }
        named.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        while (named.size() + (otherTotal > 0 ? 1 : 0) > SUBSYSTEM_LIMIT) {
            int smallestEngine = -1;
            for (int i = named.size() - 1; i >= 0; i--) {
                if (named.get(i).getKey().startsWith("Engine: ")) {
                    smallestEngine = i;
                    break;
                }
            }
            if (smallestEngine < 0) {
                break;
            }
            otherTotal += named.remove(smallestEngine).getValue();
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> en : named) {
            out.put(en.getKey(), en.getValue());
        }
        if (otherTotal > 0) {
            out.put(SUBSYSTEM_OTHER, otherTotal);
        }
        return out;
    }

    private static double[] toDeltas(double[] cumulative) {
        if (cumulative == null) {
            return null;
        }
        double[] out = new double[cumulative.length];
        for (int i = 1; i < cumulative.length; i++) {
            out[i] = Math.max(0, cumulative[i] - cumulative[i - 1]);
        }
        return out;
    }

    private static long recordAlloc(RecordedEvent e, long weight, Map<String, long[]> bySubsystem,
                                    Map<String, long[]> byThread, Map<String, long[]> byClass,
                                    Map<String, long[]> byMod) {
        if (weight <= 0) {
            return 0;
        }
        RecordedStackTrace stack = e.getStackTrace();
        if (stack != null) {
            bySubsystem.computeIfAbsent(classify(stack.getFrames()), k -> new long[1])[0] += weight;
            String mod = allocMod(stack);
            if (mod != null) {
                byMod.computeIfAbsent(mod, k -> new long[1])[0] += weight;
            }
        }
        RecordedThread thread = e.getThread();
        String threadName = thread == null ? null : thread.getJavaName();
        if (threadName != null && !threadName.isBlank()) {
            byThread.computeIfAbsent(threadName, k -> new long[1])[0] += weight;
        }
        String className = getClassName(e, "objectClass");
        if (className != null && !className.isBlank()) {
            byClass.computeIfAbsent(className, k -> new long[1])[0] += weight;
        }
        return weight;
    }

    private static String allocMod(RecordedStackTrace stack) {
        for (RecordedFrame frame : stack.getFrames()) {
            if (!frame.isJavaFrame() || frame.getMethod() == null) {
                continue;
            }
            String type = frame.getMethod().getType().getName();
            if (!JfrHotThreads.isEngine(type)) {
                return JfrHotThreads.modId(type);
            }
        }
        return null;
    }

    private static String parseOsName(String osVersion) {
        if (osVersion == null || osVersion.isBlank()) {
            return null;
        }
        for (String line : osVersion.split("\n")) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            String key = eq < 0 ? null : trimmed.substring(0, eq).trim();
            if ("PRETTY_NAME".equals(key) || "DISTRIB_DESCRIPTION".equals(key)) {
                String value = trimmed.substring(eq + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        String first = osVersion.split("\n", 2)[0].trim();
        return first.isBlank() ? null : first;
    }

    static String normalizeThreadName(String name) {
        if (name == null) {
            return null;
        }
        int i = name.length() - 1;
        while (i >= 0 && Character.isDigit(name.charAt(i))) {
            i--;
        }
        if (i < name.length() - 1 && i >= 0 && (name.charAt(i) == '-' || name.charAt(i) == '#')) {
            return name.substring(0, i);
        }
        return name;
    }

    private static void recordStack(RecordedStackTrace stack, Map<String, long[]> samplesByMethod,
                                    Map<String, Map<String, long[]>> chainsByMethod,
                                    Map<String, long[]> subsystems, FlameNode flameRoot,
                                    Map<String, long[]> cpuModSamples,
                                    Map<String, Map<String, long[]>> cpuModMethods,
                                    Map<String, Map<String, long[]>> cpuModThreads,
                                    String threadName) {
        if (stack == null) {
            return;
        }
        List<RecordedFrame> frames = stack.getFrames();
        String leaf = null;
        int leafIndex = -1;
        for (int i = 0; i < frames.size(); i++) {
            RecordedFrame frame = frames.get(i);
            if (frame.isJavaFrame() && frame.getMethod() != null) {
                leaf = frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
                leafIndex = i;
                break;
            }
        }
        if (leaf == null) {
            return;
        }
        samplesByMethod.computeIfAbsent(leaf, k -> new long[1])[0]++;

        List<String> path = new ArrayList<>(Math.min(frames.size(), FLAME_DEPTH_LIMIT));
        for (int i = frames.size() - 1; i >= 0 && path.size() < FLAME_DEPTH_LIMIT; i--) {
            RecordedFrame frame = frames.get(i);
            if (frame.isJavaFrame() && frame.getMethod() != null) {
                path.add(shortName(frame.getMethod().getType().getName())
                    + "." + frame.getMethod().getName());
            }
        }
        flameInsert(flameRoot, path);

        for (RecordedFrame frame : frames) {
            if (!frame.isJavaFrame() || frame.getMethod() == null) {
                continue;
            }
            String type = frame.getMethod().getType().getName();
            if (JfrHotThreads.isEngine(type)) {
                continue;
            }
            String id = pluginLoaderId(frame);
            if (id == null) {
                id = JfrHotThreads.modId(type);
            }
            cpuModSamples.computeIfAbsent(id, k -> new long[1])[0]++;
            cpuModMethods.computeIfAbsent(id, k -> new HashMap<>())
                .computeIfAbsent(type + "." + frame.getMethod().getName(), k -> new long[1])[0]++;
            if (threadName != null) {
                String normalized = normalizeThreadName(threadName);
                cpuModThreads.computeIfAbsent(id, k -> new HashMap<>())
                    .computeIfAbsent(normalized, k -> new long[1])[0]++;
            }
            break;
        }

        StringBuilder chain = new StringBuilder(96);
        int added = 0;
        for (int i = leafIndex + 1; i < frames.size() && added < CALLER_CHAIN_FRAMES; i++) {
            RecordedFrame frame = frames.get(i);
            if (!frame.isJavaFrame() || frame.getMethod() == null) {
                continue;
            }
            if (added > 0) {
                chain.append(' ');
            }
            chain.append(shortName(frame.getMethod().getType().getName()))
                .append('.').append(frame.getMethod().getName());
            added++;
        }
        if (added > 0) {
            Map<String, long[]> chains = chainsByMethod.computeIfAbsent(leaf, k -> new HashMap<>());
            long[] counter = chains.get(chain.toString());
            if (counter != null) {
                counter[0]++;
            } else if (chains.size() < CHAINS_PER_METHOD_CAP) {
                chains.put(chain.toString(), new long[] {1});
            }
        }

        subsystems.computeIfAbsent(classify(frames), k -> new long[1])[0]++;
    }

    private static String classify(List<RecordedFrame> frames) {
        String deepestEngine = null;
        for (RecordedFrame frame : frames) {
            if (!frame.isJavaFrame() || frame.getMethod() == null) {
                continue;
            }
            String type = frame.getMethod().getType().getName();
            for (String[] pattern : SUBSYSTEM_PATTERNS) {
                for (int i = 1; i < pattern.length; i++) {
                    if (type.contains(pattern[i])) {
                        return pattern[0];
                    }
                }
            }
            if (deepestEngine == null && type.startsWith(HYTALE_PACKAGE)) {
                deepestEngine = type;
            }
        }
        return deepestEngine == null ? SUBSYSTEM_OTHER : engineArea(deepestEngine);
    }

    private static String engineArea(String type) {
        String rest = type.substring(HYTALE_PACKAGE.length());
        if (rest.startsWith("component.")) {
            return "Engine: ECS core";
        }
        if (rest.startsWith("protocol")) {
            return "Engine: protocol";
        }
        if (rest.startsWith("builtin.")) {
            return engineAreaSegment(rest.substring("builtin.".length()));
        }
        if (rest.startsWith("server.core.modules.")) {
            return engineAreaSegment(rest.substring("server.core.modules.".length()));
        }
        if (rest.startsWith("server.core.universe.")) {
            return "Engine: universe";
        }
        if (rest.startsWith("server.core.")) {
            return engineAreaSegment(rest.substring("server.core.".length()));
        }
        return engineAreaSegment(rest);
    }

    private static String engineAreaSegment(String rest) {
        int dot = rest.indexOf('.');
        String segment = dot < 0 ? "" : rest.substring(0, dot);
        return segment.isBlank() ? SUBSYSTEM_OTHER : "Engine: " + segment;
    }

    private static List<HotMethod> topMethods(Map<String, long[]> samplesByMethod,
                                              Map<String, Map<String, long[]>> chainsByMethod) {
        List<Map.Entry<String, long[]>> ordered = new ArrayList<>(samplesByMethod.entrySet());
        ordered.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        List<HotMethod> out = new ArrayList<>(Math.min(HOT_METHOD_LIMIT, ordered.size()));
        for (Map.Entry<String, long[]> en : ordered) {
            if (out.size() >= HOT_METHOD_LIMIT) {
                break;
            }
            List<String> callers = List.of();
            Map<String, long[]> chains = chainsByMethod.get(en.getKey());
            if (chains != null && !chains.isEmpty()) {
                String dominant = chains.entrySet().stream()
                    .max((a, b) -> Long.compare(a.getValue()[0], b.getValue()[0]))
                    .map(Map.Entry::getKey).orElse(null);
                if (dominant != null) {
                    callers = List.of(dominant.split(" "));
                }
            }
            out.add(new HotMethod(en.getKey(), en.getValue()[0], callers));
        }
        return out;
    }

    private static String shortName(String type) {
        int i = type.lastIndexOf('.');
        return i < 0 ? type : type.substring(i + 1);
    }

    static void flameInsert(FlameNode root, List<String> path) {
        root.samples++;
        FlameNode node = root;
        int depth = 0;
        for (String name : path) {
            if (depth++ >= FLAME_DEPTH_LIMIT) {
                break;
            }
            node = node.children.computeIfAbsent(name, FlameNode::new);
            node.samples++;
        }
    }

    static Flame flamePrune(FlameNode root, long totalSamples) {
        if (root == null || root.samples <= 0) {
            return null;
        }
        long threshold = Math.max(3, totalSamples / FLAME_MIN_DIVISOR);
        while (countAbove(root, threshold) > FLAME_NODE_CAP) {
            threshold *= 2;
        }
        return toFlame(root, threshold);
    }

    private static int countAbove(FlameNode node, long threshold) {
        int n = 1;
        for (FlameNode child : node.children.values()) {
            if (child.samples >= threshold) {
                n += countAbove(child, threshold);
            }
        }
        return n;
    }

    private static Flame toFlame(FlameNode node, long threshold) {
        List<Flame> children = new ArrayList<>();
        for (FlameNode child : node.children.values()) {
            if (child.samples >= threshold) {
                children.add(toFlame(child, threshold));
            }
        }
        return new Flame(node.name, node.samples, children);
    }

    private static int stateCode(String mapped) {
        return switch (mapped) {
            case "RUNNABLE" -> 1;
            case "WAITING" -> 2;
            case "TIMED_WAITING" -> 3;
            case "BLOCKED" -> 4;
            default -> 0;
        };
    }

    private static int[] modalStates(List<Pt> pts, long lo, long hi, int buckets) {
        int[][] counts = new int[buckets][5];
        for (Pt p : pts) {
            int code = (int) p.v();
            if (code >= 1 && code <= 4) {
                counts[bucketOf(p.t(), lo, hi, buckets)][code]++;
            }
        }
        int[] preference = {1, 4, 2, 3};
        int[] out = new int[buckets];
        for (int b = 0; b < buckets; b++) {
            int bestCode = 0;
            int bestCount = 0;
            for (int code : preference) {
                if (counts[b][code] > bestCount) {
                    bestCount = counts[b][code];
                    bestCode = code;
                }
            }
            out[b] = bestCode;
        }
        return out;
    }

    private static int[] overlapBuckets(List<long[]> windows, long lo, long hi, int buckets) {
        java.util.TreeSet<Integer> touched = new java.util.TreeSet<>();
        for (long[] window : windows) {
            if (window[1] < lo || window[0] > hi) {
                continue;
            }
            int from = bucketOf(Math.max(window[0], lo), lo, hi, buckets);
            int to = bucketOf(Math.min(window[1], hi), lo, hi, buckets);
            for (int b = from; b <= to; b++) {
                touched.add(b);
            }
        }
        int[] out = new int[touched.size()];
        int i = 0;
        for (int b : touched) {
            out[i++] = b;
        }
        return out;
    }

    private static String pluginLoaderId(RecordedFrame frame) {
        try {
            RecordedClassLoader loader = frame.getMethod().getType().getClassLoader();
            String name = loader == null ? null : loader.getName();
            if (name == null || !name.endsWith(")")) {
                return null;
            }
            int open = name.indexOf('(');
            if (open <= 0
                || !(name.startsWith("ThirdParty(") || name.startsWith("BuiltinPlugin("))) {
                return null;
            }
            String id = name.substring(open + 1, name.length() - 1).trim();
            return id.isBlank() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    private static String dominantOf(Map<String, long[]> methods) {
        if (methods == null || methods.isEmpty()) {
            return "<unknown>";
        }
        return methods.entrySet().stream()
            .max((a, b) -> Long.compare(a.getValue()[0], b.getValue()[0]))
            .map(Map.Entry::getKey).orElse("<unknown>");
    }

    private static String mapState(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.startsWith("STATE_") ? raw.substring("STATE_".length()) : raw;
        return switch (s) {
            case "RUNNABLE" -> "RUNNABLE";
            case "SLEEPING", "PARKED_TIMED", "TIMED_WAITING", "IN_OBJECT_WAIT_TIMED" -> "TIMED_WAITING";
            case "PARKED", "WAITING", "IN_OBJECT_WAIT" -> "WAITING";
            case "BLOCKED", "BLOCKED_ON_MONITOR_ENTER" -> "BLOCKED";
            default -> null;
        };
    }

    private static String systemGcCaller(RecordedStackTrace stack) {
        if (stack == null) {
            return "<unknown>";
        }
        for (RecordedFrame frame : stack.getFrames()) {
            if (!frame.isJavaFrame() || frame.getMethod() == null) {
                continue;
            }
            String type = frame.getMethod().getType().getName();
            if (type.equals("java.lang.System") || type.equals("java.lang.Runtime")) {
                continue;
            }
            return type + "." + frame.getMethod().getName();
        }
        return "<unknown>";
    }

    private static String cpuBrand(String cpu, String description) {
        if (description != null) {
            int i = description.indexOf("Brand: ");
            if (i >= 0) {
                int from = i + "Brand: ".length();
                int to = description.indexOf(',', from);
                int nl = description.indexOf('\n', from);
                if (nl >= 0 && (to < 0 || nl < to)) {
                    to = nl;
                }
                String brand = (to < 0 ? description.substring(from) : description.substring(from, to)).trim();
                if (!brand.isEmpty()) {
                    return brand;
                }
            }
        }
        return cpu;
    }

    private static double[] bucketLast(List<Pt> pts, long minT, long maxT, int buckets) {
        if (pts == null || pts.size() < 2 || maxT <= minT) {
            return null;
        }
        double[] out = new double[buckets];
        boolean[] seen = new boolean[buckets];
        for (Pt p : pts) {
            int b = bucketOf(p.t(), minT, maxT, buckets);
            out[b] = p.v();
            seen[b] = true;
        }
        return fillGaps(out, seen);
    }

    private static double[] bucketSumZeroFilled(List<Pt> pts, long minT, long maxT, int buckets) {
        if (pts == null || pts.size() < 2 || maxT <= minT) {
            return null;
        }
        double[] out = new double[buckets];
        for (Pt p : pts) {
            out[bucketOf(p.t(), minT, maxT, buckets)] += p.v();
        }
        return out;
    }

    private static double[] bucketMaxZeroFilled(List<Pt> pts, long minT, long maxT, int buckets) {
        if (pts == null || pts.size() < 2 || maxT <= minT) {
            return null;
        }
        double[] out = new double[buckets];
        for (Pt p : pts) {
            int b = bucketOf(p.t(), minT, maxT, buckets);
            out[b] = Math.max(out[b], p.v());
        }
        return out;
    }

    private static int bucketOf(long t, long minT, long maxT, int buckets) {
        int b = (int) ((t - minT) * buckets / (maxT - minT + 1));
        return Math.max(0, Math.min(buckets - 1, b));
    }

    static int bucketCount(long lo, long hi) {
        if (hi <= lo) {
            return BUCKETS;
        }
        long target = Math.round((hi - lo) / 1000.0 / 10.0);
        return (int) Math.max(BUCKETS, Math.min(MAX_BUCKETS, target));
    }

    private static double[] fillGaps(double[] values, boolean[] seen) {
        double first = Double.NaN;
        for (int i = 0; i < values.length; i++) {
            if (seen[i]) {
                first = values[i];
                break;
            }
        }
        if (Double.isNaN(first)) {
            return null;
        }
        double last = first;
        for (int i = 0; i < values.length; i++) {
            if (seen[i]) {
                last = values[i];
            } else {
                values[i] = last;
            }
        }
        return values;
    }

    private static double[] sumSeries(List<double[]> series) {
        double[] out = null;
        for (double[] s : series) {
            if (s == null) {
                continue;
            }
            if (out == null) {
                out = s.clone();
            } else {
                for (int i = 0; i < out.length; i++) {
                    out[i] += s[i];
                }
            }
        }
        return out;
    }

    private static long[] toLongs(double[] values) {
        if (values == null) {
            return null;
        }
        long[] out = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = Math.round(values[i]);
        }
        return out;
    }

    private static double avg(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return round2(sum / values.length);
    }

    private static double max(double[] values) {
        double m = values[0];
        for (double v : values) {
            m = Math.max(m, v);
        }
        return round2(m);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double durationMs(RecordedEvent e, String field) {
        try {
            if (e.hasField(field)) {
                java.time.Duration d = e.getDuration(field);
                return d == null ? 0 : d.toNanos() / 1_000_000.0;
            }
        } catch (Exception ignored) {
        }
        try {
            return e.getDuration() == null ? 0 : e.getDuration().toNanos() / 1_000_000.0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static double getD(RecordedObject o, String f) {
        try {
            return o.hasField(f) ? o.getDouble(f) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long getL(RecordedObject o, String f) {
        try {
            return o.hasField(f) ? o.getLong(f) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String getS(RecordedObject o, String f) {
        try {
            return o.hasField(f) ? o.getString(f) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean getB(RecordedObject o, String f) {
        try {
            return o.hasField(f) ? o.getBoolean(f) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String getClassName(RecordedObject o, String f) {
        try {
            Object value = o.hasField(f) ? o.getValue(f) : null;
            return value instanceof RecordedClass rc ? decodeTypeName(rc.getName()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Turns JVM array descriptors ("[J", "[Ljava.lang.Object;") into source form ("long[]", "java.lang.Object[]"). */
    static String decodeTypeName(String name) {
        if (name == null || !name.startsWith("[")) {
            return name;
        }
        int dims = 0;
        while (dims < name.length() && name.charAt(dims) == '[') {
            dims++;
        }
        String rest = name.substring(dims);
        if (rest.isEmpty()) {
            return name;
        }
        String base = switch (rest.charAt(0)) {
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'D' -> "double";
            case 'F' -> "float";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'S' -> "short";
            case 'L' -> rest.endsWith(";") ? rest.substring(1, rest.length() - 1) : rest;
            default -> rest;
        };
        return base + "[]".repeat(dims);
    }

    private static String oldObjectClass(RecordedEvent e) {
        try {
            Object object = e.hasField("object") ? e.getValue("object") : null;
            if (object instanceof RecordedObject ro) {
                Object type = ro.hasField("type") ? ro.getValue("type") : null;
                if (type instanceof RecordedClass rc) {
                    return decodeTypeName(rc.getName());
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static String topJavaFrame(RecordedStackTrace stack) {
        if (stack == null) {
            return null;
        }
        for (RecordedFrame frame : stack.getFrames()) {
            if (frame.isJavaFrame() && frame.getMethod() != null) {
                return shortName(frame.getMethod().getType().getName())
                    + "." + frame.getMethod().getName();
            }
        }
        return null;
    }
}
