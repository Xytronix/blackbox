package sh.harold.blackbox.core.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class MetricsLog {

    public static final String HEADER =
        "timestamp,tickAvgMs,tps,players,heapUsedBytes,rssBytes,gcPct,cpuPct,allocMbPerSec";

    private static final DateTimeFormatter DAY =
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final String PREFIX = "health-";
    private static final String SUFFIX = ".csv";

    private final Path dir;

    public MetricsLog(Path dir) {
        this.dir = dir;
    }

    public static String formatRow(Instant when, double tickAvgMs, double tps, int players,
                                   long heapUsedBytes, long rssBytes, double gcPct,
                                   double cpuPct, double allocMbPerSec) {
        return when.toString()
            + "," + number(tickAvgMs)
            + "," + number(tps)
            + "," + (players < 0 ? "" : Integer.toString(players))
            + "," + (heapUsedBytes < 0 ? "" : Long.toString(heapUsedBytes))
            + "," + (rssBytes < 0 ? "" : Long.toString(rssBytes))
            + "," + number(gcPct)
            + "," + number(cpuPct)
            + "," + number(allocMbPerSec);
    }

    public void append(Instant when, String row, int retentionDays) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(PREFIX + DAY.format(when) + SUFFIX);
        StringBuilder out = new StringBuilder();
        if (!Files.exists(file)) {
            out.append(HEADER).append('\n');
        }
        out.append(row).append('\n');
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        prune(retentionDays, when);
    }

    public Trend read(Instant now, int days) {
        if (days <= 0 || dir == null || !Files.isDirectory(dir)) {
            return Trend.EMPTY;
        }
        long cutoff = now.minus(Duration.ofDays(days)).toEpochMilli();
        List<double[]> rows = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> files = entries
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith(PREFIX) && name.endsWith(SUFFIX);
                })
                .sorted()
                .toList();
            for (Path file : files) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank() || line.startsWith("timestamp")) {
                        continue;
                    }
                    double[] row = parseRow(line);
                    if (row != null && row[0] >= cutoff) {
                        rows.add(row);
                    }
                }
            }
        } catch (IOException e) {
            return Trend.EMPTY;
        }
        rows.sort(Comparator.comparingDouble(r -> r[0]));
        int n = rows.size();
        long[] timestamps = new long[n];
        double[] tickAvgMs = new double[n];
        double[] tps = new double[n];
        double[] players = new double[n];
        double[] heapBytes = new double[n];
        double[] rssBytes = new double[n];
        double[] gcPct = new double[n];
        double[] cpuPct = new double[n];
        double[] allocMbPerSec = new double[n];
        for (int i = 0; i < n; i++) {
            double[] r = rows.get(i);
            timestamps[i] = (long) r[0];
            tickAvgMs[i] = r[1];
            tps[i] = r[2];
            players[i] = r[3];
            heapBytes[i] = r[4];
            rssBytes[i] = r[5];
            gcPct[i] = r[6];
            cpuPct[i] = r[7];
            allocMbPerSec[i] = r[8];
        }
        return new Trend(timestamps, tickAvgMs, tps, players, heapBytes, rssBytes, gcPct, cpuPct, allocMbPerSec);
    }

    private static double[] parseRow(String line) {
        String[] c = line.split(",", -1);
        if (c.length < 7) {
            return null;
        }
        long ts;
        try {
            ts = Instant.parse(c[0].trim()).toEpochMilli();
        } catch (RuntimeException e) {
            return null;
        }
        return new double[]{ts, num(c, 1), num(c, 2), num(c, 3), num(c, 4), num(c, 5), num(c, 6),
            num(c, 7), num(c, 8)};
    }

    private static double num(String[] cols, int idx) {
        if (idx >= cols.length) {
            return Double.NaN;
        }
        String s = cols[idx].trim();
        if (s.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    public record Trend(long[] timestamps, double[] tickAvgMs, double[] tps, double[] players,
                        double[] heapBytes, double[] rssBytes, double[] gcPct,
                        double[] cpuPct, double[] allocMbPerSec) {
        public static final Trend EMPTY = new Trend(new long[0], new double[0], new double[0],
            new double[0], new double[0], new double[0], new double[0], new double[0], new double[0]);

        public boolean isEmpty() {
            return timestamps.length == 0;
        }

        public int size() {
            return timestamps.length;
        }
    }

    private void prune(int retentionDays, Instant when) throws IOException {
        if (retentionDays <= 0 || !Files.isDirectory(dir)) {
            return;
        }
        String cutoff = PREFIX + DAY.format(when.minus(Duration.ofDays(retentionDays))) + SUFFIX;
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> stale = entries
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith(PREFIX) && name.endsWith(SUFFIX) && name.compareTo(cutoff) < 0;
                })
                .toList();
            for (Path file : stale) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static String number(double value) {
        if (value < 0 || Double.isNaN(value)) {
            return "";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
