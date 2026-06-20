package sh.harold.blackbox.core.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import sh.harold.blackbox.core.json.JsonWriter;
import sh.harold.blackbox.core.metrics.MetricsLog;

public final class TrendReport {
    private static final String TEMPLATE_RESOURCE = "trend-template.html";
    private static final String TITLE_TOKEN = "__TITLE__";
    private static final String DATA_TOKEN = "/*__DATA__*/null";
    private static final int MAX_POINTS = 600;
    private static final double MB = 1024 * 1024;

    private static final DateTimeFormatter GENERATED_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private TrendReport() {
    }

    public static void write(MetricsLog.Trend trend, int days, Instant generatedAt, long[] incidents,
                             OutputStream out) throws IOException {
        String template = loadTemplate();
        String html = replaceOnce(template, TITLE_TOKEN, "Blackbox Health Trends · " + days + "d");
        String json = buildData(trend, days, generatedAt, incidents).replace("</", "<\\/");
        html = replaceOnce(html, DATA_TOKEN, json);
        OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write(html);
        writer.flush();
    }

    private static String buildData(MetricsLog.Trend trend, int days, Instant generatedAt, long[] incidents)
        throws IOException {
        int n = trend.size();
        int buckets = Math.min(n, MAX_POINTS);
        StringBuilder out = new StringBuilder(8192);
        JsonWriter json = new JsonWriter(out);
        json.beginObject();
        json.name("days").value((Number) days);
        json.name("generated").value(GENERATED_FORMAT.format(generatedAt));
        json.name("count").value((Number) n);

        json.name("t").beginArray();
        for (int b = 0; b < buckets; b++) {
            int lo = (int) ((long) b * n / buckets);
            int hi = (int) ((long) (b + 1) * n / buckets);
            json.value((Number) meanTime(trend.timestamps(), lo, hi));
        }
        json.endArray();

        writeSeries(json, "tickAvgMs", trend.tickAvgMs(), n, buckets, 1, 2);
        writeSeries(json, "tps", trend.tps(), n, buckets, 1, 2);
        writeSeries(json, "players", trend.players(), n, buckets, 1, 0);
        writeSeries(json, "heapMb", trend.heapBytes(), n, buckets, MB, 1);
        writeSeries(json, "rssMb", trend.rssBytes(), n, buckets, MB, 1);
        writeSeries(json, "gcPct", trend.gcPct(), n, buckets, 1, 2);
        writeSeries(json, "cpuPct", trend.cpuPct(), n, buckets, 1, 1);
        writeSeries(json, "allocMbPerSec", trend.allocMbPerSec(), n, buckets, 1, 1);
        writeSeries(json, "queuedPackets", trend.queuedPackets(), n, buckets, 1, 0);

        json.name("incidents").beginArray();
        if (incidents != null) {
            for (long ms : incidents) {
                json.value((Number) ms);
            }
        }
        json.endArray();

        json.endObject();
        return out.toString();
    }

    private static void writeSeries(JsonWriter json, String name, double[] values, int n, int buckets,
                                    double divisor, int decimals) throws IOException {
        json.name(name).beginArray();
        for (int b = 0; b < buckets; b++) {
            int lo = (int) ((long) b * n / buckets);
            int hi = (int) ((long) (b + 1) * n / buckets);
            double mean = mean(values, lo, hi);
            if (Double.isNaN(mean)) {
                json.nullValue();
            } else {
                json.value((Number) round(mean / divisor, decimals));
            }
        }
        json.endArray();
    }

    private static double mean(double[] values, int lo, int hi) {
        double sum = 0;
        int count = 0;
        for (int i = lo; i < hi; i++) {
            if (!Double.isNaN(values[i])) {
                sum += values[i];
                count++;
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private static long meanTime(long[] values, int lo, int hi) {
        long sum = 0;
        for (int i = lo; i < hi; i++) {
            sum += values[i];
        }
        return sum / Math.max(1, hi - lo);
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    private static String loadTemplate() throws IOException {
        try (InputStream in = TrendReport.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing resource " + TEMPLATE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String replaceOnce(String haystack, String token, String replacement) throws IOException {
        int i = haystack.indexOf(token);
        if (i < 0) {
            throw new IOException("Trend template is missing token " + token);
        }
        return haystack.substring(0, i) + replacement + haystack.substring(i + token.length());
    }
}
