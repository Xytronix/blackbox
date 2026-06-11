package sh.harold.blackbox.core.health;

import java.nio.file.Files;
import java.nio.file.Path;

public final class HostCpuStats {

    private HostCpuStats() {
    }

    public static long[] cgroupThrottle() {
        long[] v2 = readCpuStat(Path.of("/sys/fs/cgroup/cpu.stat"), "throttled_usec", 1L);
        return v2 != null ? v2 : readCpuStat(Path.of("/sys/fs/cgroup/cpu/cpu.stat"), "throttled_time", 1_000L);
    }

    private static long[] readCpuStat(Path path, String timeKey, long nsToUsDivisor) {
        try {
            if (!Files.isReadable(path)) {
                return null;
            }
            long periods = -1;
            long micros = -1;
            long usage = -1;
            for (String line : Files.readAllLines(path)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                try {
                    if (parts[0].equals("nr_throttled")) {
                        periods = Long.parseLong(parts[1]);
                    } else if (parts[0].equals(timeKey)) {
                        micros = Long.parseLong(parts[1]) / nsToUsDivisor;
                    } else if (parts[0].equals("usage_usec")) {
                        usage = Long.parseLong(parts[1]);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            return periods >= 0 || micros >= 0 || usage >= 0 ? new long[] {periods, micros, usage} : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static double[] psi() {
        double cpu = psiSomeAvg10(Path.of("/sys/fs/cgroup/cpu.pressure"));
        double io = psiSomeAvg10(Path.of("/sys/fs/cgroup/io.pressure"));
        double mem = psiSomeAvg10(Path.of("/sys/fs/cgroup/memory.pressure"));
        return cpu < 0 && io < 0 && mem < 0 ? null : new double[] {cpu, io, mem};
    }

    private static double psiSomeAvg10(Path path) {
        try {
            if (!Files.isReadable(path)) {
                return -1;
            }
            for (String line : Files.readAllLines(path)) {
                if (!line.startsWith("some ")) {
                    continue;
                }
                for (String token : line.split("\\s+")) {
                    if (token.startsWith("avg10=")) {
                        return Double.parseDouble(token.substring("avg10=".length()));
                    }
                }
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public static long[] procCpu() {
        try {
            Path path = Path.of("/proc/stat");
            if (!Files.isReadable(path)) {
                return null;
            }
            for (String line : Files.readAllLines(path)) {
                if (!line.startsWith("cpu ")) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                long total = 0;
                long steal = 0;
                long iowait = 0;
                int last = Math.min(parts.length - 1, 8);
                for (int i = 1; i <= last; i++) {
                    long value;
                    try {
                        value = Long.parseLong(parts[i]);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    total += value;
                    if (i == 5) {
                        iowait = value;
                    } else if (i == 8) {
                        steal = value;
                    }
                }
                return new long[] {total, steal, iowait};
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static long[] cgroupMemory() {
        long current = readLong(Path.of("/sys/fs/cgroup/memory.current"));
        long oomKills = readKeyedLong(Path.of("/sys/fs/cgroup/memory.events"), "oom_kill");
        if (current < 0) {
            current = readLong(Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes"));
        }
        if (oomKills < 0) {
            oomKills = readKeyedLong(Path.of("/sys/fs/cgroup/memory/memory.oom_control"), "oom_kill");
        }
        return current < 0 && oomKills < 0 ? null : new long[] {current, oomKills};
    }

    public static long[] cgroupIoMax() {
        Path path = Path.of("/sys/fs/cgroup/io.max");
        try {
            if (!Files.isReadable(path)) {
                return null;
            }
            long rbps = -1;
            long wbps = -1;
            for (String line : Files.readAllLines(path)) {
                for (String token : line.trim().split("\\s+")) {
                    long r = parseIoLimit(token, "rbps");
                    if (r >= 0) {
                        rbps = rbps < 0 ? r : Math.min(rbps, r);
                    }
                    long w = parseIoLimit(token, "wbps");
                    if (w >= 0) {
                        wbps = wbps < 0 ? w : Math.min(wbps, w);
                    }
                }
            }
            return rbps < 0 && wbps < 0 ? null : new long[] {rbps, wbps};
        } catch (Exception e) {
            return null;
        }
    }

    private static long parseIoLimit(String token, String key) {
        if (!token.startsWith(key + "=")) {
            return -1;
        }
        String value = token.substring(key.length() + 1);
        if (value.equals("max")) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static long[] cgroupSwap() {
        long current = readLong(Path.of("/sys/fs/cgroup/memory.swap.current"));
        long max = readLong(Path.of("/sys/fs/cgroup/memory.swap.max"));
        if (current < 0) {
            return null;
        }
        return new long[] {current, max};
    }

    private static long readLong(Path path) {
        try {
            if (!Files.isReadable(path)) {
                return -1;
            }
            return Long.parseLong(Files.readString(path).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static long readKeyedLong(Path path, String key) {
        try {
            if (!Files.isReadable(path)) {
                return -1;
            }
            for (String line : Files.readAllLines(path)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2 && parts[0].equals(key)) {
                    return Long.parseLong(parts[1]);
                }
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
