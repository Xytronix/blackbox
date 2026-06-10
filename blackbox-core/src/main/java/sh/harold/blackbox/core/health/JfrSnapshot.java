package sh.harold.blackbox.core.health;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;

/**
 * Reconstructs a {@link HealthSnapshot} from a JFR recording by reading the last values of the metric
 * events JFR records continuously: {@code jdk.CPULoad}, {@code jdk.GCHeapSummary},
 * {@code jdk.GCHeapConfiguration}, {@code jdk.MetaspaceSummary}, {@code jdk.GarbageCollection},
 * {@code jdk.CPUInformation}, {@code jdk.OSInformation}, {@code jdk.JVMInformation},
 * {@code jdk.PhysicalMemory} and {@code jdk.JavaThreadStatistics}.
 *
 * <p>Used when no live MXBean snapshot is available (for example when rebuilding an incident bundle
 * after an unclean shutdown). Best-effort and unit-compatible with {@link HealthCollector}: CPU is a
 * 0..1 fraction, memory/RAM are bytes, GC time is milliseconds. Fields the recording does not carry
 * (disk, per-state thread counts, worlds, TPS) are left at zero/empty.
 */
public final class JfrSnapshot {

    private JfrSnapshot() {
    }

    public static HealthSnapshot parse(Path recording, int hotThreadLimit) {
        double procLoad = 0;
        double sysLoad = 0;
        int cores = 0;
        long heapUsed = 0;
        long heapCommitted = 0;
        long heapMax = 0;
        long nonHeapUsed = 0;
        long totalRam = 0;
        long jvmStart = 0;
        long lastEventMs = 0;
        String os = null;
        String jvm = null;
        long threadsActive = 0;
        Map<String, long[]> gcByName = new LinkedHashMap<>();

        try (RecordingFile file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                RecordedEvent e = file.readEvent();
                if (e.getEndTime() != null) {
                    lastEventMs = Math.max(lastEventMs, e.getEndTime().toEpochMilli());
                }
                switch (e.getEventType().getName()) {
                    case "jdk.CPULoad" -> {
                        procLoad = getD(e, "jvmUser") + getD(e, "jvmSystem");
                        sysLoad = getD(e, "machineTotal");
                    }
                    case "jdk.CPUInformation" -> {
                        int hw = (int) getL(e, "hwThreads");
                        if (hw > 0) {
                            cores = hw;
                        }
                    }
                    case "jdk.GCHeapSummary" -> {
                        heapUsed = getL(e, "heapUsed");
                        RecordedObject space = getObj(e, "heapSpace");
                        if (space != null) {
                            heapCommitted = getL(space, "committedSize");
                        }
                    }
                    case "jdk.GCHeapConfiguration" -> {
                        long mx = getL(e, "maxSize");
                        if (mx > 0) {
                            heapMax = mx;
                        }
                    }
                    case "jdk.MetaspaceSummary" -> {
                        RecordedObject ms = getObj(e, "metaspace");
                        if (ms != null) {
                            nonHeapUsed = getL(ms, "used");
                        }
                    }
                    case "jdk.GarbageCollection" -> {
                        String gn = getS(e, "name");
                        long durMs = e.getDuration() != null ? e.getDuration().toMillis() : 0;
                        long[] agg = gcByName.computeIfAbsent(gn == null ? "GC" : gn, k -> new long[2]);
                        agg[0]++;
                        agg[1] += durMs;
                    }
                    case "jdk.PhysicalMemory" -> {
                        long ts = getL(e, "totalSize");
                        if (ts > 0) {
                            totalRam = ts;
                        }
                    }
                    case "jdk.OSInformation" -> {
                        String v = getS(e, "osVersion");
                        if (v != null) {
                            os = firstLine(v);
                        }
                    }
                    case "jdk.JVMInformation" -> {
                        String jn = getS(e, "jvmName");
                        String jv = getS(e, "jvmVersion");
                        if (jn != null) {
                            jvm = jv != null ? jn + " " + firstLine(jv) : jn;
                        }
                        long st = getL(e, "jvmStartTime");
                        if (st > 0) {
                            jvmStart = st;
                        }
                    }
                    case "jdk.JavaThreadStatistics" -> {
                        long ac = getL(e, "activeCount");
                        if (ac > 0) {
                            threadsActive = ac;
                        }
                    }
                    default -> { }
                }
            }
        } catch (Exception ex) {
        }

        List<HealthSnapshot.Gc> gcs = new ArrayList<>();
        for (Map.Entry<String, long[]> en : gcByName.entrySet()) {
            gcs.add(new HealthSnapshot.Gc(en.getKey(), en.getValue()[0], en.getValue()[1]));
        }
        long uptime = jvmStart > 0 && lastEventMs > jvmStart ? lastEventMs - jvmStart : 0;
        HealthSnapshot.Cpu cpu = new HealthSnapshot.Cpu(procLoad, sysLoad, cores);
        HealthSnapshot.Memory mem = new HealthSnapshot.Memory(heapUsed, heapCommitted, heapMax, nonHeapUsed);
        HealthSnapshot.Sys sys = new HealthSnapshot.Sys(
            os == null ? "unknown" : os, jvm == null ? "unknown" : jvm, totalRam, uptime);
        HealthSnapshot.Threads threads = new HealthSnapshot.Threads((int) threadsActive, Map.of(), List.of());
        List<HealthSnapshot.HotThread> hot = JfrHotThreads.parse(recording, hotThreadLimit);
        return new HealthSnapshot(0, cpu, mem, gcs, sys, new HealthSnapshot.Disk(0, 0), threads, List.of(), hot);
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

    private static RecordedObject getObj(RecordedObject o, String f) {
        try {
            return o.hasField(f) && o.getValue(f) instanceof RecordedObject ro ? ro : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }
}
