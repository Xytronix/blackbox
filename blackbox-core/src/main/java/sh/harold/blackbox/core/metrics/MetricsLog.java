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
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class MetricsLog {

    public static final String HEADER = "timestamp,tickAvgMs,tps,players,heapUsedBytes,rssBytes,gcPct";

    private static final DateTimeFormatter DAY =
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final String PREFIX = "health-";
    private static final String SUFFIX = ".csv";

    private final Path dir;

    public MetricsLog(Path dir) {
        this.dir = dir;
    }

    public static String formatRow(Instant when, double tickAvgMs, double tps, int players,
                                   long heapUsedBytes, long rssBytes, double gcPct) {
        return when.toString()
            + "," + number(tickAvgMs)
            + "," + number(tps)
            + "," + (players < 0 ? "" : Integer.toString(players))
            + "," + (heapUsedBytes < 0 ? "" : Long.toString(heapUsedBytes))
            + "," + (rssBytes < 0 ? "" : Long.toString(rssBytes))
            + "," + number(gcPct);
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
