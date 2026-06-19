package sh.harold.blackbox.core.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sh.harold.blackbox.core.health.HealthSnapshot;
import sh.harold.blackbox.core.health.JfrTimeline;
import sh.harold.blackbox.core.incident.DiagnosticSection;
import sh.harold.blackbox.core.incident.IncidentMetadata;
import sh.harold.blackbox.core.incident.IncidentReport;
import sh.harold.blackbox.core.json.JsonWriter;

/**
 * Renders a self-contained incident report HTML page.
 */
public final class ReportHtml {
    private static final String TEMPLATE_RESOURCE = "report-template.html";
    private static final String TITLE_TOKEN = "__TITLE__";
    private static final String HEADER_TOKEN = "<!--__HEADER__-->";
    private static final String DATA_TOKEN = "/*__DATA__*/null";

    private static final DateTimeFormatter CREATED_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CLOCK_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private static final Pattern LOG_LINE = Pattern.compile(
        "^\\[(\\d{4})/(\\d{2})/(\\d{2}) (?:(\\d{2}):(\\d{2}):(\\d{2})|\\[REDACTED\\])\\s+([A-Z]+)\\]\\s+\\[([^\\]]*)\\]\\s?(.*)$");
    private static final Pattern WORLD_UUID_SUFFIX = Pattern.compile(
        "-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final int MAX_LOG_MESSAGE_CHARS = 8000;

    private ReportHtml() {
    }

    public static void write(IncidentReport report, OutputStream out) throws IOException {
        write(report, null, out);
    }

    public static void write(IncidentReport report, JfrTimeline timeline, OutputStream out) throws IOException {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(out, "out");

        OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write(render(report, timeline));
        writer.flush();
    }

    private static String render(IncidentReport report, JfrTimeline timeline) throws IOException {
        String template = loadTemplate();
        ReportSections sections = ReportSections.route(report.diagnostics());
        Long stallMs = parseStallMs(report.context());
        Integer players = totalPlayers(report.snapshot());

        String html = replaceOnce(template, TITLE_TOKEN,
            "Blackbox Incident Report · " + escapeHtml(report.meta().id().value()));
        html = replaceOnce(html, HEADER_TOKEN,
            buildHeader(report.meta(), stallMs, players, report.context(), blockedInFrame(report)));
        String json = buildData(report, timeline, sections, stallMs, players)
            .replace("</", "<\\/");
        return replaceOnce(html, DATA_TOKEN, json);
    }

    private static String loadTemplate() throws IOException {
        try (InputStream in = ReportHtml.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing resource " + TEMPLATE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String replaceOnce(String haystack, String token, String replacement) throws IOException {
        int i = haystack.indexOf(token);
        if (i < 0) {
            throw new IOException("Report template is missing token " + token);
        }
        return haystack.substring(0, i) + replacement + haystack.substring(i + token.length());
    }


    private static String blockedInFrame(IncidentReport report) {
        if (report.diagnostics() == null) {
            return null;
        }
        for (DiagnosticSection section : report.diagnostics()) {
            if ("Stalled thread".equals(section.title()) || "Deadlocked thread".equals(section.title())) {
                String frame = section.entries().get("Blocked in");
                if (frame != null && !frame.isBlank()) {
                    return frame;
                }
            }
        }
        return null;
    }

    private static String buildHeader(IncidentMetadata meta, Long stallMs, Integer players,
                                      Map<String, String> context, String blockedIn) {
        String sevClass = switch (meta.severity()) {
            case CRITICAL -> "bad";
            case DEGRADED -> "warn";
            default -> "good";
        };
        StringBuilder h = new StringBuilder(1024);
        h.append("<div class=\"hl-top\">")
            .append("<span class=\"badge ").append(sevClass).append("\"><span class=\"dot\"></span>")
            .append(escapeHtml(meta.severity().name())).append("</span>")
            .append("<span class=\"pill\">Trigger <b>").append(escapeHtml(meta.trigger())).append("</b></span>");
        if (players != null) {
            h.append("<span class=\"pill\"><b>").append(players).append("</b> online</span>");
        }
        if (context != null) {
            for (Map.Entry<String, String> entry : context.entrySet()) {
                if ("stallMs".equals(entry.getKey()) || "profileStartMs".equals(entry.getKey())) {
                    continue;
                }
                h.append("<span class=\"pill\">").append(escapeHtml(entry.getKey()))
                    .append(" <b>").append(escapeHtml(entry.getValue())).append("</b></span>");
            }
        }
        h.append("<span class=\"incid\">Incident <b>").append(escapeHtml(meta.id().value())).append("</b></span>")
            .append("<span id=\"copySlot\" style=\"margin-left:14px\"></span>")
            .append("</div>");

        String worldShort = worldShort(meta.world());
        if ("HEARTBEAT_STALL".equals(meta.trigger()) && stallMs != null && worldShort != null) {
            h.append("<h1>Heartbeat stalled <span class=\"ms\">").append(stallMs)
                .append(" ms</span> on world <i>").append(escapeHtml(worldShort)).append("</i></h1>");
        } else {
            h.append("<h1>").append(escapeHtmlWithBreaks(meta.headline())).append("</h1>");
        }

        h.append("<div class=\"subhead\">");
        if (meta.world() != null) {
            h.append("World <code>").append(escapeHtml(meta.world())).append("</code> · ");
        }
        h.append("Captured <span class=\"mono\">").append(escapeHtml(meta.createdAt().toString()))
            .append("</span>");
        if (blockedIn != null) {
            h.append(" · Blocked in <code>").append(escapeHtml(blockedIn)).append("</code>");
        }
        h.append("</div>");
        return h.toString();
    }

    static String worldShort(String world) {
        if (world == null) {
            return null;
        }
        String s = world.startsWith("instance-") ? world.substring("instance-".length()) : world;
        s = WORLD_UUID_SUFFIX.matcher(s).replaceFirst("");
        return s.replace('_', ' ');
    }

    private static String blackboxVersion() {
        try (InputStream in = ReportHtml.class.getResourceAsStream("/sh/harold/blackbox/core/version.txt")) {
            if (in != null) {
                String version = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!version.isEmpty() && !version.contains("${")) {
                    return version;
                }
            }
        } catch (IOException ignored) {
        }
        return ReportHtml.class.getPackage() == null ? null : ReportHtml.class.getPackage().getImplementationVersion();
    }

    private static Long parseStallMs(Map<String, String> context) {
        try {
            String raw = context == null ? null : context.get("stallMs");
            return raw == null ? null : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long profileStartMs(Map<String, String> context, long fallback) {
        try {
            String raw = context == null ? null : context.get("profileStartMs");
            return raw == null ? fallback : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Integer totalPlayers(HealthSnapshot snapshot) {
        if (snapshot == null || snapshot.worlds().isEmpty()) {
            return null;
        }
        int sum = 0;
        boolean anyKnown = false;
        for (HealthSnapshot.World w : snapshot.worlds()) {
            if (w.players() >= 0) {
                sum += w.players();
                anyKnown = true;
            }
        }
        return anyKnown ? sum : null;
    }


    private static String buildData(IncidentReport report, JfrTimeline timeline, ReportSections sections,
                                    Long stallMs, Integer players) throws IOException {
        IncidentMetadata meta = report.meta();
        HealthSnapshot snapshot = report.snapshot();
        Map<String, String> env = sections.environment();
        String hytaleVersion = env == null ? null : env.get("Hytale version");
        String serverName = env == null ? null : env.get("Server name");
        String containerRuntime = env == null ? null : env.get("Container runtime");
        List<String[]> loaders = ReportSystemWriter.loaders(env);

        StringBuilder out = new StringBuilder(16384);
        JsonWriter json = new JsonWriter(out);
        json.beginObject();

        json.name("blackboxVersion").value(blackboxVersion());

        json.name("incident").beginObject();
        json.name("id").value(meta.id().value());
        json.name("severity").value(meta.severity().name());
        json.name("trigger").value(meta.trigger());
        json.name("world").value(meta.world());
        json.name("worldShort").value(worldShort(meta.world()));
        json.name("created").value(CREATED_FORMAT.format(meta.createdAt()));
        json.name("note");
        if ("UNCLEAN_SHUTDOWN".equals(meta.trigger())) {
            json.value(String.join(" ", report.summary().whatHappened()));
        } else {
            json.nullValue();
        }
        ReportJson.writeNullable(json, "stallMs", stallMs);
        long markerMs = profileStartMs(report.context(), meta.createdAt().toEpochMilli());
        if (timeline != null && timeline.start() != null && timeline.end() != null) {
            json.name("recStart").value(CLOCK_FORMAT.format(timeline.start()));
            long spanMs = timeline.end().toEpochMilli() - timeline.start().toEpochMilli();
            json.name("windowSec").value(Math.max(0, spanMs / 1000));
            boolean manual = "MANUAL".equals(meta.trigger());
            if (spanMs > 0 && !manual) {
                long incidentMs = "UNCLEAN_SHUTDOWN".equals(meta.trigger())
                    ? timeline.end().toEpochMilli() : markerMs;
                double frac = (incidentMs - timeline.start().toEpochMilli()) / (double) spanMs;
                json.name("markerFrac").value(Math.round(Math.max(0, Math.min(1, frac)) * 10000) / 10000.0);
                double recoverySec = (timeline.end().toEpochMilli() - incidentMs) / 1000.0;
                ReportJson.writeNullableDouble(json, "recoverySec", recoverySec >= 2 ? recoverySec : null);
            } else {
                json.name("markerFrac").nullValue();
                json.name("recoverySec").nullValue();
            }
            String profileStart = report.context() == null ? null : report.context().get("profileStartMs");
            if (manual && profileStart != null && spanMs > 0) {
                double frac = (markerMs - timeline.start().toEpochMilli()) / (double) spanMs;
                json.name("profileFrac").value(Math.round(Math.max(0, Math.min(1, frac)) * 10000) / 10000.0);
            } else {
                json.name("profileFrac").nullValue();
            }
        } else {
            json.name("recStart").nullValue();
            json.name("windowSec").nullValue();
            json.name("markerFrac").nullValue();
            json.name("recoverySec").nullValue();
            json.name("profileFrac").nullValue();
        }
        ReportJson.writeNullable(json, "samples", timeline != null && timeline.totalSamples() > 0
            ? timeline.totalSamples() : null);
        ReportJson.writeNullable(json, "players", players == null ? null : players.longValue());
        json.endObject();

        ReportSystemWriter.writeSys(json, snapshot, timeline, hytaleVersion, serverName, containerRuntime, loaders);
        ReportSystemWriter.writeCpu(json, timeline);
        ReportSystemWriter.writeHeap(json, snapshot, timeline);
        ReportSystemWriter.writeRam(json, timeline);
        ReportSystemWriter.writeAlloc(json, timeline);
        ReportSystemWriter.writeGc(json, snapshot, timeline);
        ReportSystemWriter.writeGcCauses(json, timeline);
        ReportSystemWriter.writeNet(json, timeline);
        ReportActivityWriter.TickSelection tick = ReportActivityWriter.selectTicks(meta, snapshot, timeline);
        ReportActivityWriter.writeTps(json, tick, snapshot);
        ReportActivityWriter.writeSeries(json, timeline, tick);
        ReportSystemWriter.writeGcOverlap(json, timeline);
        ReportActivityWriter.writePluginMetrics(json, timeline);
        ReportSystemWriter.writeRetained(json, timeline);
        ReportSystemWriter.writeSlowIo(json, timeline);
        ReportSystemWriter.writeHeapHistogram(json, sections.heapHistogram());
        ReportSystemWriter.writeMemPools(json, sections.memPools());
        ReportActivityWriter.writeEntities(json, sections.entities());
        ReportActivityWriter.writeTickSystems(json, sections.tickSystems());
        ReportActivityWriter.writeTickSeries(json, timeline);
        ReportActivityWriter.writeModCpu(json, timeline, sections.modCpu());
        ReportActivityWriter.writeFlame(json, timeline);
        ReportActivityWriter.writeThreadTimeline(json, timeline);
        List<LogLine> logLines = parseLog(sections.serverLog());
        ReportEvents.write(json, logLines, timeline);
        ReportActivityWriter.writeThreads(json, snapshot, timeline);
        ReportActivityWriter.writeHotMethods(json, timeline);
        ReportActivityWriter.writeWorlds(json, meta, snapshot, timeline);
        ReportActivityWriter.writeWorldTps(json, timeline, tick);
        ReportActivityWriter.writeSubsystems(json, timeline);
        ReportActivityWriter.writeCpuByWorld(json, timeline);
        ReportDiagnosticsWriter.writePlugins(json, sections.plugins());
        ReportDiagnosticsWriter.writeAssetPacks(json, sections.assetPacks());
        ReportDiagnosticsWriter.writeMixins(json, sections.mixins());
        ReportDiagnosticsWriter.writeLog(json, logLines);
        ReportDiagnosticsWriter.writeConfigs(json, sections.configs());
        ReportDiagnosticsWriter.writeGeneric(json, sections.generic(), sections.plugins() != null);

        json.endObject();
        return out.toString();
    }

    /** Connection lifecycle lines from the Hytale log become "conn" timeline events. */
    static String connectionEventType(LogLine line) {
        if (!"Hytale".equals(line.source())) {
            return null;
        }
        String msg = line.message();
        return msg.startsWith("Starting authenticated flow") || msg.startsWith("Disconnecting ")
            ? "conn" : null;
    }

    record LogLine(String level, String source, String message, long epochMs) {
        LogLine appendMessage(String line) {
            return new LogLine(level, source, message + "\n" + line, epochMs);
        }
    }

    static List<LogLine> parseLog(String serverLog) {
        if (serverLog == null || serverLog.isBlank()) {
            return List.of();
        }
        List<LogLine> out = new ArrayList<>();
        for (String line : serverLog.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = LOG_LINE.matcher(line);
            if (m.matches()) {
                String level = switch (m.group(7)) {
                    case "WARNING" -> "WARN";
                    case "SEVERE" -> "ERROR";
                    default -> m.group(7);
                };
                long epochMs = -1;
                try {
                    if (m.group(4) != null) {
                        epochMs = java.time.LocalDateTime.of(
                            Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                            Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                            Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)))
                            .toInstant(ZoneOffset.UTC).toEpochMilli();
                    }
                } catch (Exception ignored) {
                }
                out.add(new LogLine(level, m.group(8), m.group(9), epochMs));
            } else if (!out.isEmpty()) {
                LogLine prev = out.get(out.size() - 1);
                if (prev.message().length() < MAX_LOG_MESSAGE_CHARS) {
                    out.set(out.size() - 1, prev.appendMessage(line));
                }
            } else {
                out.add(new LogLine("", "", line, -1));
            }
        }
        return out;
    }


    private static String escapeHtml(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static String escapeHtmlWithBreaks(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r') {
                if (i + 1 < value.length() && value.charAt(i + 1) == '\n') {
                    i++;
                }
                escaped.append("<br>");
                continue;
            }
            if (c == '\n') {
                escaped.append("<br>");
                continue;
            }
            if (c == '\t') {
                escaped.append("&#9;");
                continue;
            }
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
