package sh.harold.blackbox.hytale;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.Locale;

final class JvmProbes {
    private JvmProbes() {
    }

    static long[] deadlockedThreadIds() {
        try {
            return ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        } catch (Exception e) {
            return null;
        }
    }

    static int deadlockedThreadCount() {
        try {
            long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
            return ids == null ? 0 : ids.length;
        } catch (Exception e) {
            return -1;
        }
    }

    static double processCpuLoad() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                return sun.getProcessCpuLoad();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    static long allocatedBytes() {
        try {
            java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean sun) {
                long total = 0;
                boolean any = false;
                for (long bytes : sun.getThreadAllocatedBytes(bean.getAllThreadIds())) {
                    if (bytes > 0) {
                        total += bytes;
                        any = true;
                    }
                }
                return any ? total : -1;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    static long stwGcPauseMillis() {
        try {
            long total = 0;
            boolean any = false;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (!isStopTheWorld(gc.getName())) {
                    continue;
                }
                long time = gc.getCollectionTime();
                if (time >= 0) {
                    total += time;
                    any = true;
                }
            }
            return any ? total : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    static boolean isStopTheWorld(String beanName) {
        if (beanName == null) {
            return false;
        }
        String name = beanName.toLowerCase(Locale.ROOT);
        return !name.contains("cycles") && !name.contains("concurrent");
    }

    static double tenuredAfterGcFraction() {
        try {
            MemoryPoolMXBean chosen = null;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() != MemoryType.HEAP) {
                    continue;
                }
                String name = pool.getName().toLowerCase(Locale.ROOT);
                if (name.contains("old") || name.contains("tenured")) {
                    chosen = pool;
                    break;
                }
                if (chosen == null) {
                    chosen = pool;
                }
            }
            if (chosen == null) {
                return Double.NaN;
            }
            MemoryUsage afterGc = chosen.getCollectionUsage();
            if (afterGc == null || (afterGc.getUsed() == 0 && afterGc.getCommitted() == 0)) {
                return Double.NaN;
            }
            long max = afterGc.getMax();
            if (max <= 0 && chosen.getUsage() != null) {
                max = chosen.getUsage().getMax();
            }
            if (max <= 0) {
                return Double.NaN;
            }
            return afterGc.getUsed() / (double) max;
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
