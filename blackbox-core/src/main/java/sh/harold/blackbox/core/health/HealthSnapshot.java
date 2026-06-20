package sh.harold.blackbox.core.health;

import java.util.List;
import java.util.Map;

public record HealthSnapshot(
    int targetTps,
    Cpu cpu,
    Memory memory,
    List<Gc> gc,
    Sys system,
    Disk disk,
    Threads threads,
    List<World> worlds,
    List<HotThread> hotThreads
) {
    public HealthSnapshot {
        gc = gc == null ? List.of() : List.copyOf(gc);
        worlds = worlds == null ? List.of() : List.copyOf(worlds);
        hotThreads = hotThreads == null ? List.of() : List.copyOf(hotThreads);
    }

    public record Cpu(double processLoad, double systemLoad, int cores) {}

    public record Memory(long heapUsed, long heapCommitted, long heapMax, long nonHeapUsed,
                         long nonHeapCommitted) {
        public Memory(long heapUsed, long heapCommitted, long heapMax, long nonHeapUsed) {
            this(heapUsed, heapCommitted, heapMax, nonHeapUsed, -1);
        }
    }

    public record Gc(String name, long count, long totalTimeMs) {}

    public record Sys(String os, String jvm, long totalRamBytes, long uptimeMs) {}

    public record Disk(long usedBytes, long totalBytes) {}

    public record Threads(int total, Map<String, Integer> byState, List<RunnableThread> runnable,
                          List<String> deadlocked) {
        public Threads {
            byState = byState == null ? Map.of() : Map.copyOf(byState);
            runnable = runnable == null ? List.of() : List.copyOf(runnable);
            deadlocked = deadlocked == null ? List.of() : List.copyOf(deadlocked);
        }

        public Threads(int total, Map<String, Integer> byState, List<RunnableThread> runnable) {
            this(total, byState, runnable, List.of());
        }
    }

    public record RunnableThread(String name, String topFrame) {}

    public record World(String name, int players, int entities, int chunks, double tps, double mspt,
                        List<String> playerNames, double avgPingMs,
                        double msptP50, double msptP95, double msptMax,
                        long chunksGeneratedTotal, long chunksLoadedTotal) {
        public World {
            playerNames = playerNames == null ? List.of() : List.copyOf(playerNames);
        }

        public World(String name, int players, int entities, int chunks, double tps, double mspt) {
            this(name, players, entities, chunks, tps, mspt, List.of(), -1, -1, -1, -1, -1, -1);
        }

        public World(String name, int players, int entities, int chunks, double tps, double mspt,
                     List<String> playerNames) {
            this(name, players, entities, chunks, tps, mspt, playerNames, -1, -1, -1, -1, -1, -1);
        }

        public World(String name, int players, int entities, int chunks, double tps, double mspt,
                     List<String> playerNames, double avgPingMs) {
            this(name, players, entities, chunks, tps, mspt, playerNames, avgPingMs, -1, -1, -1, -1, -1);
        }
    }

    public record HotThread(String thread, long samples, String topMethod, List<MethodSample> methods) {
        public HotThread {
            methods = methods == null ? List.of() : List.copyOf(methods);
        }

        public HotThread(String thread, long samples, String topMethod) {
            this(thread, samples, topMethod, List.of());
        }
    }

    public record MethodSample(String method, long samples) {}
}
