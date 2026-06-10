package sh.harold.blackbox.core.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Renders the self-contained incident report page: a static template (CSS/JS renderer) plus a
 * generated data object. The template hides every section whose data is missing, so the same
 * page works for live captures, recovered recordings, and minimal test reports.
 *
 * <p>Diagnostic sections are routed by title: "Mixins", "Plugins" and "Server log" become
 * structured report sections; other preformatted sections render as config file blocks; other
 * key/value sections render as generic diagnostic cards. "Server name" / "Hytale version"
 * entries of an "Environment" section enrich the system facts.
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

    /** "[yyyy/MM/dd HH:mm:ss   LEVEL] [Logger] message" as written by HytaleLogFormatter (UTC). */
    private static final Pattern LOG_LINE = Pattern.compile(
        "^\\[(\\d{4})/(\\d{2})/(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})\\s+([A-Z]+)\\]\\s+\\[([^\\]]*)\\]\\s?(.*)$");
    private static final int MAX_EVENTS = 120;
    private static final int MAX_WORLD_TPS = 8;
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
        Sections sections = Sections.route(report.diagnostics());
        Long stallMs = parseStallMs(report.context());
        Integer players = totalPlayers(report.snapshot());

        String html = replaceOnce(template, TITLE_TOKEN,
            "Blackbox Incident Report · " + escapeHtml(report.meta().id().value()));
        html = replaceOnce(html, HEADER_TOKEN, buildHeader(report.meta(), stallMs, players, report.context()));
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

    // ---- server-rendered header --------------------------------------------------------

    private static String buildHeader(IncidentMetadata meta, Long stallMs, Integer players,
                                      Map<String, String> context) {
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
                if ("stallMs".equals(entry.getKey())) {
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
            .append("</span></div>");
        return h.toString();
    }

    private static String worldShort(String world) {
        if (world == null) {
            return null;
        }
        String s = world.startsWith("instance-") ? world.substring("instance-".length()) : world;
        s = WORLD_UUID_SUFFIX.matcher(s).replaceFirst("");
        return s.replace('_', ' ');
    }

    private static Long parseStallMs(Map<String, String> context) {
        try {
            String raw = context == null ? null : context.get("stallMs");
            return raw == null ? null : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
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

    // ---- diagnostics routing --------------------------------------------------------------

    private record Sections(Map<String, String> mixins, Map<String, String> plugins,
                            Map<String, String> assetPacks, String serverLog,
                            Map<String, String> environment, Map<String, String> tickSystems,
                            Map<String, String> modCpu, Map<String, String> heapHistogram,
                            Map<String, String> memPools, Map<String, String> entities,
                            List<DiagnosticSection> configs, List<DiagnosticSection> generic) {

        static Sections route(List<DiagnosticSection> diagnostics) {
            Map<String, String> mixins = null;
            Map<String, String> plugins = null;
            Map<String, String> assetPacks = null;
            String serverLog = null;
            Map<String, String> environment = null;
            Map<String, String> tickSystems = null;
            Map<String, String> modCpu = null;
            Map<String, String> heapHistogram = null;
            Map<String, String> memPools = null;
            Map<String, String> entities = null;
            List<DiagnosticSection> configs = new ArrayList<>();
            List<DiagnosticSection> generic = new ArrayList<>();
            for (DiagnosticSection section : diagnostics == null ? List.<DiagnosticSection>of() : diagnostics) {
                if (section.preformatted() != null) {
                    if ("Server log".equals(section.title())) {
                        serverLog = section.preformatted();
                    } else {
                        configs.add(section);
                    }
                } else if ("Mixins".equals(section.title())) {
                    mixins = section.entries();
                } else if ("Plugins".equals(section.title())) {
                    plugins = section.entries();
                } else if ("Asset packs".equals(section.title())) {
                    assetPacks = section.entries();
                } else if ("Tick systems".equals(section.title())) {
                    tickSystems = section.entries();
                } else if ("Mod hot-path contribution (JFR)".equals(section.title())) {
                    modCpu = section.entries();
                } else if ("Heap histogram".equals(section.title())) {
                    heapHistogram = section.entries();
                } else if ("Memory pools".equals(section.title())) {
                    memPools = section.entries();
                } else if ("Entities".equals(section.title())) {
                    entities = section.entries();
                } else if ("Environment".equals(section.title())) {
                    environment = section.entries();
                    generic.add(section);
                } else {
                    generic.add(section);
                }
            }
            return new Sections(mixins, plugins, assetPacks, serverLog, environment, tickSystems, modCpu,
                heapHistogram, memPools, entities, configs, generic);
        }
    }

    // ---- data object -----------------------------------------------------------------------

    private static String buildData(IncidentReport report, JfrTimeline timeline, Sections sections,
                                    Long stallMs, Integer players) throws IOException {
        IncidentMetadata meta = report.meta();
        HealthSnapshot snapshot = report.snapshot();
        Map<String, String> env = sections.environment();
        String hytaleVersion = env == null ? null : env.get("Hytale version");
        String serverName = env == null ? null : env.get("Server name");
        String containerRuntime = env == null ? null : env.get("Container runtime");
        List<String[]> loaders = loaders(env);

        StringBuilder out = new StringBuilder(16384);
        JsonWriter json = new JsonWriter(out);
        json.beginObject();

        json.name("blackboxVersion").value(ReportHtml.class.getPackage() == null
            ? null : ReportHtml.class.getPackage().getImplementationVersion());

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
        writeNullable(json, "stallMs", stallMs);
        if (timeline != null && timeline.start() != null && timeline.end() != null) {
            json.name("recStart").value(CLOCK_FORMAT.format(timeline.start()));
            long spanMs = timeline.end().toEpochMilli() - timeline.start().toEpochMilli();
            json.name("windowSec").value(Math.max(0, spanMs / 1000));
            if (spanMs > 0) {
                double frac = (meta.createdAt().toEpochMilli() - timeline.start().toEpochMilli())
                    / (double) spanMs;
                json.name("markerFrac").value(Math.round(Math.max(0, Math.min(1, frac)) * 10000) / 10000.0);
                double recoverySec = (timeline.end().toEpochMilli() - meta.createdAt().toEpochMilli()) / 1000.0;
                writeNullableDouble(json, "recoverySec", recoverySec >= 2 ? recoverySec : null);
            } else {
                json.name("markerFrac").nullValue();
                json.name("recoverySec").nullValue();
            }
        } else {
            json.name("recStart").nullValue();
            json.name("windowSec").nullValue();
            json.name("markerFrac").nullValue();
            json.name("recoverySec").nullValue();
        }
        writeNullable(json, "samples", timeline != null && timeline.totalSamples() > 0
            ? timeline.totalSamples() : null);
        writeNullable(json, "players", players == null ? null : players.longValue());
        json.endObject();

        writeSys(json, snapshot, timeline, hytaleVersion, serverName, containerRuntime, loaders);
        writeCpu(json, timeline);
        writeHeap(json, snapshot, timeline);
        writeRam(json, timeline);
        writeAlloc(json, timeline);
        writeGc(json, snapshot, timeline);
        writeGcCauses(json, timeline);
        writeNet(json, timeline);
        TickSelection tick = selectTicks(meta, snapshot, timeline);
        writeTps(json, tick, snapshot);
        writeSeries(json, timeline, tick);
        writeGcOverlap(json, timeline);
        writePluginMetrics(json, timeline);
        writeRetained(json, timeline);
        writeSlowIo(json, timeline);
        writeHeapHistogram(json, sections.heapHistogram());
        writeMemPools(json, sections.memPools());
        writeEntities(json, sections.entities());
        writeTickSystems(json, sections.tickSystems());
        writeTickSeries(json, timeline);
        writeModCpu(json, timeline, sections.modCpu());
        writeFlame(json, timeline);
        writeThreadTimeline(json, timeline);
        List<LogLine> logLines = parseLog(sections.serverLog());
        writeEvents(json, buildEvents(logLines, timeline));
        writeThreads(json, snapshot, timeline);
        writeHotMethods(json, timeline);
        writeWorlds(json, meta, snapshot, timeline);
        writeWorldTps(json, timeline, tick);
        writeSubsystems(json, timeline);
        writePlugins(json, sections.plugins());
        writeAssetPacks(json, sections.assetPacks());
        writeMixins(json, sections.mixins());
        writeLog(json, logLines);
        writeConfigs(json, sections.configs());
        writeGeneric(json, sections.generic(), sections.plugins() != null);

        json.endObject();
        return out.toString();
    }

    private static void writeSys(JsonWriter json, HealthSnapshot snapshot, JfrTimeline timeline,
                                 String hytaleVersion, String serverName, String containerRuntime,
                                 List<String[]> loaders)
        throws IOException {
        HealthSnapshot.Sys sys = snapshot == null ? null : snapshot.system();
        Map<String, String> props = timeline == null ? Map.of() : timeline.sysProps();
        JfrTimeline.CpuInfo cpuInfo = timeline == null ? null : timeline.cpuInfo();
        JfrTimeline.GcConfig gcConfig = timeline == null ? null : timeline.gcConfig();
        String osName = timeline == null ? null : timeline.osName();
        if (sys == null && props.isEmpty() && cpuInfo == null && gcConfig == null && osName == null
            && serverName == null && hytaleVersion == null && containerRuntime == null
            && loaders.isEmpty()) {
            json.name("sys").nullValue();
            return;
        }
        json.name("sys").beginObject();
        json.name("os").value(osName != null ? osName : (sys == null ? null : sys.os()));
        json.name("arch").value(props.get("os.arch"));
        json.name("server").value(serverName);
        json.name("hytale").value(hytaleVersion);
        json.name("jvm").value(sys == null ? null : sys.jvm());
        json.name("javaVendor").value(props.getOrDefault("java.vendor", props.get("java.vm.vendor")));
        json.name("javaVersion").value(props.get("java.runtime.version"));
        json.name("javaHome").value(props.get("java.home"));
        json.name("cpuBrand").value(cpuInfo == null ? null : cpuInfo.brand());
        Long cpuCores = null;
        if (cpuInfo != null && cpuInfo.cores() > 0) {
            cpuCores = cpuInfo.cores();
        } else if (snapshot != null && snapshot.cpu() != null && snapshot.cpu().cores() > 0) {
            cpuCores = (long) snapshot.cpu().cores();
        }
        writeNullable(json, "cpuCores", cpuCores);
        writeNullable(json, "hwThreads", cpuInfo != null && cpuInfo.hwThreads() > 0 ? cpuInfo.hwThreads() : null);
        writeNullable(json, "sockets", cpuInfo != null && cpuInfo.sockets() > 0 ? cpuInfo.sockets() : null);
        writeNullable(json, "ramTotal", sys != null && sys.totalRamBytes() > 0 ? sys.totalRamBytes() : null);
        writeNullable(json, "uptimeMs", sys != null && sys.uptimeMs() > 0 ? sys.uptimeMs() : null);
        HealthSnapshot.Memory memory = snapshot == null ? null : snapshot.memory();
        if (memory == null || memory.nonHeapUsed() <= 0) {
            json.name("nonHeap").nullValue();
        } else {
            json.name("nonHeap").beginObject();
            json.name("used").value(memory.nonHeapUsed());
            writeNullable(json, "committed", memory.nonHeapCommitted() > 0 ? memory.nonHeapCommitted() : null);
            json.endObject();
        }
        if (timeline == null || timeline.swapTotal() <= 0) {
            json.name("swap").nullValue();
        } else {
            json.name("swap").beginObject();
            json.name("free").value(timeline.swapFree());
            json.name("total").value(timeline.swapTotal());
            json.endObject();
        }
        long[] hostMem = timeline == null ? null : timeline.hostMemSeries();
        writeNullable(json, "hostMemUsed", hostMem != null ? hostMem[hostMem.length - 1] : null);
        json.name("gcYoung").value(gcConfig == null ? null : gcConfig.young());
        json.name("gcOld").value(gcConfig == null ? null : gcConfig.old());
        writeNullable(json, "gcParallel", gcConfig != null && gcConfig.parallelThreads() > 0
            ? gcConfig.parallelThreads() : null);
        writeNullable(json, "gcConc", gcConfig != null && gcConfig.concurrentThreads() > 0
            ? gcConfig.concurrentThreads() : null);
        writeNullable(json, "gcTimeRatio", gcConfig != null && gcConfig.gcTimeRatio() > 0
            ? gcConfig.gcTimeRatio() : null);
        writeNullable(json, "xmxBytes", heapMax(snapshot, timeline));
        json.name("startCommand").value(startCommand(timeline));
        writeDisk(json, snapshot, timeline);
        if (loaders.isEmpty()) {
            json.name("loaders").nullValue();
        } else {
            json.name("loaders").beginArray();
            for (String[] loader : loaders) {
                json.beginArray();
                json.value(loader[0]);
                json.value(loader[1]);
                json.endArray();
            }
            json.endArray();
        }
        JfrTimeline.Container container = timeline == null ? null : timeline.container();
        if (container == null && containerRuntime == null) {
            json.name("container").nullValue();
        } else {
            json.name("container").beginObject();
            json.name("runtime").value(containerRuntime);
            json.name("type").value(container == null ? null : container.type());
            writeNullableDouble(json, "cpuLimit",
                container != null && container.cpuLimit() > 0 ? container.cpuLimit() : null);
            writeNullable(json, "memLimit",
                container != null && container.memLimit() > 0 ? container.memLimit() : null);
            json.endObject();
        }
        json.endObject();
    }

    private static final Pattern INSTALLED_VERSION = Pattern.compile("installed \\((.+)\\)");
    private static final String[] LOADER_KEYS = {"Hyinit", "Hyxin"};

    /** [name, version-or-"installed"] for each loader the Environment section reports. */
    private static List<String[]> loaders(Map<String, String> environment) {
        if (environment == null) {
            return List.of();
        }
        List<String[]> out = new ArrayList<>();
        for (String key : LOADER_KEYS) {
            String value = environment.get(key);
            if (value == null) {
                continue;
            }
            Matcher m = INSTALLED_VERSION.matcher(value);
            out.add(new String[] {key, m.matches() ? m.group(1) : "installed"});
        }
        return out;
    }

    /** Reconstructed start command from the recording's JVM information. */
    private static String startCommand(JfrTimeline timeline) {
        if (timeline == null || (timeline.jvmArgs() == null && timeline.javaArgs() == null)) {
            return null;
        }
        return ("java "
            + (timeline.jvmArgs() == null ? "" : timeline.jvmArgs()) + " "
            + (timeline.javaArgs() == null ? "" : timeline.javaArgs()))
            .replaceAll("\\s+", " ").trim();
    }

    /**
     * Disk free/total for the server filesystem: the live snapshot when available, else the
     * sampler's last recorded value (covers crash-recovered bundles).
     */
    private static void writeDisk(JsonWriter json, HealthSnapshot snapshot, JfrTimeline timeline)
        throws IOException {
        long free = -1;
        long total = -1;
        if (snapshot != null && snapshot.disk() != null && snapshot.disk().totalBytes() > 0) {
            total = snapshot.disk().totalBytes();
            free = total - Math.max(0, snapshot.disk().usedBytes());
        } else if (timeline != null && timeline.diskFreeSeries() != null) {
            long[] series = timeline.diskFreeSeries();
            free = series[series.length - 1];
            total = timeline.diskTotal();
        }
        if (free < 0 || total <= 0) {
            json.name("disk").nullValue();
            return;
        }
        json.name("disk").beginObject();
        json.name("free").value(free);
        json.name("total").value(total);
        json.endObject();
    }

    private static Long heapMax(HealthSnapshot snapshot, JfrTimeline timeline) {
        if (timeline != null && timeline.heapMax() > 0) {
            return timeline.heapMax();
        }
        if (snapshot != null && snapshot.memory() != null && snapshot.memory().heapMax() > 0) {
            return snapshot.memory().heapMax();
        }
        return null;
    }

    private static void writeCpu(JsonWriter json, JfrTimeline timeline) throws IOException {
        JfrTimeline.CpuStats cpu = timeline == null ? null : timeline.cpu();
        if (cpu == null) {
            json.name("cpu").nullValue();
            return;
        }
        json.name("cpu").beginObject();
        json.name("jvmAvg").value(cpu.jvmAvg());
        json.name("jvmMax").value(cpu.jvmMax());
        json.name("machAvg").value(cpu.machAvg());
        json.name("machMax").value(cpu.machMax());
        json.endObject();
    }

    private static void writeHeap(JsonWriter json, HealthSnapshot snapshot, JfrTimeline timeline) throws IOException {
        long[] series = timeline == null ? null : timeline.heapSeries();
        Long max = heapMax(snapshot, timeline);
        Long committed = snapshot != null && snapshot.memory() != null && snapshot.memory().heapCommitted() > 0
            ? snapshot.memory().heapCommitted() : null;
        if (series != null) {
            long peak = Long.MIN_VALUE;
            long floor = Long.MAX_VALUE;
            for (long v : series) {
                peak = Math.max(peak, v);
                floor = Math.min(floor, v);
            }
            json.name("heap").beginObject();
            json.name("peak").value(peak);
            json.name("floor").value(floor);
            writeNullable(json, "max", max);
            writeNullable(json, "committed", committed);
            json.endObject();
            return;
        }
        if (snapshot != null && snapshot.memory() != null && snapshot.memory().heapUsed() > 0) {
            json.name("heap").beginObject();
            json.name("peak").value(snapshot.memory().heapUsed());
            json.name("floor").value(snapshot.memory().heapUsed());
            writeNullable(json, "max", max);
            writeNullable(json, "committed", committed);
            json.endObject();
            return;
        }
        json.name("heap").nullValue();
    }

    private static void writeRam(JsonWriter json, JfrTimeline timeline) throws IOException {
        long[] rss = timeline == null ? null : timeline.rssSeries();
        if (rss == null) {
            json.name("ram").nullValue();
            return;
        }
        long peak = Long.MIN_VALUE;
        for (long v : rss) {
            peak = Math.max(peak, v);
        }
        json.name("ram").beginObject();
        json.name("rssLast").value(rss[rss.length - 1]);
        json.name("rssPeak").value(peak);
        json.name("nmtOn").value(timeline.nmtCommitted() >= 0);
        writeNullable(json, "nativeCommitted", timeline.nmtCommitted() >= 0 ? timeline.nmtCommitted() : null);
        json.endObject();
    }

    private static void writeAlloc(JsonWriter json, JfrTimeline timeline) throws IOException {
        JfrTimeline.Alloc alloc = timeline == null ? null : timeline.alloc();
        if (alloc == null) {
            json.name("alloc").nullValue();
            return;
        }
        json.name("alloc").beginObject();
        json.name("total").value(alloc.totalBytes());
        json.name("bySubsystem").beginArray();
        for (Map.Entry<String, Long> e : alloc.bySubsystem().entrySet()) {
            json.beginArray();
            json.value(e.getKey());
            json.value(e.getValue());
            json.endArray();
        }
        json.endArray();
        json.name("byThread").beginArray();
        for (Map.Entry<String, Long> e : alloc.byThread().entrySet()) {
            json.beginArray();
            json.value(e.getKey());
            json.value(e.getValue());
            json.endArray();
        }
        json.endArray();
        json.name("byClass").beginArray();
        for (Map.Entry<String, Long> e : alloc.byClass().entrySet()) {
            json.beginArray();
            json.value(e.getKey());
            json.value(e.getValue());
            json.endArray();
        }
        json.endArray();
        json.name("byMod").beginArray();
        for (Map.Entry<String, Long> e : alloc.byMod().entrySet()) {
            json.beginArray();
            json.value(e.getKey());
            json.value(e.getValue());
            json.endArray();
        }
        json.endArray();
        json.endObject();
    }

    private static void writeGc(JsonWriter json, HealthSnapshot snapshot, JfrTimeline timeline) throws IOException {
        JfrTimeline.GcStats gc = timeline == null ? null : timeline.gc();
        JfrTimeline.GcConfig cfg = timeline == null ? null : timeline.gcConfig();
        if (gc == null) {
            json.name("gc").nullValue();
        } else {
            json.name("gc").beginObject();
            json.name("collections").value(gc.collections());
            json.name("maxPauseMs").value(gc.maxPauseMs());
            json.name("totalPauseMs").value(gc.totalPauseMs());
            if (cfg != null && cfg.explicitDisabled() != null) {
                json.name("explicitDisabled").value(cfg.explicitDisabled());
            } else {
                json.name("explicitDisabled").nullValue();
            }
            if (cfg != null && cfg.explicitConcurrent() != null) {
                json.name("explicitConcurrent").value(cfg.explicitConcurrent());
            } else {
                json.name("explicitConcurrent").nullValue();
            }
            json.name("explicitCalls").beginArray();
            for (JfrTimeline.ExplicitGc call : timeline.explicitGcs()) {
                json.beginArray();
                json.value(friendlyGcCaller(call.caller()));
                json.value(call.count());
                json.endArray();
            }
            json.endArray();
            json.endObject();
        }

        List<HealthSnapshot.Gc> byCollector = snapshot == null ? List.of() : snapshot.gc();
        if (byCollector.isEmpty()) {
            json.name("gcByCollector").nullValue();
        } else {
            json.name("gcByCollector").beginArray();
            for (HealthSnapshot.Gc g : byCollector) {
                json.beginArray();
                json.value(g.name());
                json.value(g.count());
                json.value(g.totalTimeMs());
                json.endArray();
            }
            json.endArray();
        }
    }

    private static String friendlyGcCaller(String caller) {
        if (caller != null && caller.startsWith("sun.rmi.")) {
            return caller + " (JVM RMI DGC, Java standard, not the game)";
        }
        return caller;
    }

    /** A discrete event placed on the chart time axis. */
    private record ReportEvent(double frac, String type, String label, int count) {
        ReportEvent bump() {
            return new ReportEvent(frac, type, label, count + 1);
        }
    }

    /**
     * Discrete events within the recording window: server-log ERROR/WARN lines, explicit GC
     * calls, and plugin-reported moments. Same-type bursts within 0.5% of the window collapse
     * into one event with a count; when more than {@value #MAX_EVENTS} remain, the most recent
     * ones win (they sit closest to the incident).
     */
    private static List<ReportEvent> buildEvents(List<LogLine> log, JfrTimeline timeline) {
        if (timeline == null || timeline.start() == null || timeline.end() == null) {
            return List.of();
        }
        long start = timeline.start().toEpochMilli();
        long end = timeline.end().toEpochMilli();
        if (end <= start) {
            return List.of();
        }
        List<ReportEvent> raw = new ArrayList<>();
        for (LogLine line : log) {
            String type = switch (line.level()) {
                case "ERROR" -> "error";
                case "WARN" -> "warn";
                default -> null;
            };
            if (type == null || line.epochMs() < start || line.epochMs() > end) {
                continue;
            }
            String msg = line.message();
            int nl = msg.indexOf('\n');
            if (nl >= 0) {
                msg = msg.substring(0, nl);
            }
            if (msg.length() > 80) {
                msg = msg.substring(0, 80) + "…";
            }
            String label = line.source().isBlank() ? msg : line.source() + ": " + msg;
            raw.add(new ReportEvent((line.epochMs() - start) / (double) (end - start), type, label, 1));
        }
        for (long t : timeline.explicitGcTimes()) {
            if (t >= start && t <= end) {
                raw.add(new ReportEvent((t - start) / (double) (end - start), "gc", "System.gc()", 1));
            }
        }
        for (JfrTimeline.PluginEvent pe : timeline.pluginEvents()) {
            if (pe.timeMs() < start || pe.timeMs() > end) {
                continue;
            }
            String label = pe.category().isBlank() ? pe.message() : "[" + pe.category() + "] " + pe.message();
            if (label.length() > 80) {
                label = label.substring(0, 80) + "…";
            }
            raw.add(new ReportEvent((pe.timeMs() - start) / (double) (end - start), "plugin", label, 1));
        }
        raw.sort((a, b) -> Double.compare(a.frac(), b.frac()));

        List<ReportEvent> collapsed = new ArrayList<>();
        for (ReportEvent event : raw) {
            ReportEvent last = collapsed.isEmpty() ? null : collapsed.get(collapsed.size() - 1);
            if (last != null && last.type().equals(event.type()) && event.frac() - last.frac() < 0.005) {
                collapsed.set(collapsed.size() - 1, last.bump());
            } else {
                collapsed.add(event);
            }
        }
        return collapsed.size() > MAX_EVENTS
            ? collapsed.subList(collapsed.size() - MAX_EVENTS, collapsed.size())
            : collapsed;
    }

    private static void writeEvents(JsonWriter json, List<ReportEvent> events) throws IOException {
        if (events.isEmpty()) {
            json.name("events").nullValue();
            return;
        }
        json.name("events").beginArray();
        for (ReportEvent event : events) {
            json.beginArray();
            json.value(Math.round(event.frac() * 10000) / 10000.0);
            json.value(event.type());
            json.value(event.count() > 1 ? event.label() + " ×" + event.count() : event.label());
            json.endArray();
        }
        json.endArray();
    }

    private static void writeNet(JsonWriter json, JfrTimeline timeline) throws IOException {
        double[] outSeries = timeline == null ? null : timeline.netOutSeries();
        if (timeline == null || timeline.netInterface() == null || outSeries == null) {
            json.name("net").nullValue();
            return;
        }
        double[] inSeries = timeline.netInSeries();
        json.name("net").beginObject();
        json.name("iface").value(timeline.netInterface());
        json.name("inAvg").value(round2(avg(inSeries)));
        json.name("inPeak").value(round2(max(inSeries)));
        json.name("outAvg").value(round2(avg(outSeries)));
        json.name("outPeak").value(round2(max(outSeries)));
        if (timeline.netOthers().isEmpty()) {
            json.name("others").nullValue();
        } else {
            json.name("others").beginArray();
            for (JfrTimeline.NetIface other : timeline.netOthers()) {
                json.beginArray();
                json.value(other.iface());
                json.value(other.inAvg());
                json.value(other.inPeak());
                json.value(other.outAvg());
                json.value(other.outPeak());
                json.endArray();
            }
            json.endArray();
        }
        json.endObject();
    }

    private record TickSelection(String world, double[] tps, double[] mspt) {}

    private static TickSelection selectTicks(IncidentMetadata meta, HealthSnapshot snapshot, JfrTimeline timeline) {
        if (timeline == null || timeline.ticks().isEmpty()) {
            return null;
        }
        Map<String, JfrTimeline.WorldTicks> ticks = timeline.ticks();
        String world = null;
        if (meta.world() != null && ticks.containsKey(meta.world())) {
            world = meta.world();
        } else {
            long best = -1;
            for (String candidate : ticks.keySet()) {
                long samples = timeline.worldSamples().getOrDefault(candidate, 0L);
                if (samples > best) {
                    best = samples;
                    world = candidate;
                }
            }
        }
        JfrTimeline.WorldTicks wt = ticks.get(world);
        return wt == null ? null : new TickSelection(world, wt.tps(), wt.mspt());
    }

    private static void writeTps(JsonWriter json, TickSelection tick, HealthSnapshot snapshot) throws IOException {
        if (tick == null || tick.tps() == null) {
            json.name("tps").nullValue();
            return;
        }
        json.name("tps").beginObject();
        json.name("world").value(tick.world());
        writeNullable(json, "target", snapshot != null && snapshot.targetTps() > 0
            ? (long) snapshot.targetTps() : null);
        json.name("avg").value(round2(avg(tick.tps())));
        json.name("min").value(round2(min(tick.tps())));
        json.endObject();
    }

    private static void writeSeries(JsonWriter json, JfrTimeline timeline, TickSelection tick) throws IOException {
        json.name("series").beginObject();
        if (timeline != null) {
            writeDoubleSeries(json, "cpu", timeline.cpuMachineSeries());
            writeDoubleSeries(json, "cpuJvm", timeline.cpuJvmSeries());
            writeLongSeries(json, "heap", timeline.heapSeries());
            writeLongSeries(json, "heapCommitted", timeline.heapCommittedSeries());
            writeLongSeries(json, "rss", timeline.rssSeries());
            writeLongSeries(json, "hostMem", timeline.hostMemSeries());
            writeDoubleSeries(json, "entities", timeline.entitiesSeries());
            writeDoubleSeries(json, "netIn", timeline.netInSeries());
            writeDoubleSeries(json, "netOut", timeline.netOutSeries());
            writeLongSeries(json, "threads", timeline.threadSeries());
            writeDoubleSeries(json, "gcPause", timeline.gcPauseSeries());
            writeDoubleSeries(json, "players", timeline.playersSeries());
            writeDoubleSeries(json, "exceptions", timeline.exceptionsSeries());
            writeDoubleSeries(json, "ping", timeline.pingSeries());
            writeLongSeries(json, "disk", timeline.diskFreeSeries());
            writeDoubleSeries(json, "diskRead", timeline.diskReadSeries());
            writeDoubleSeries(json, "diskWrite", timeline.diskWriteSeries());
        }
        if (tick != null) {
            writeDoubleSeries(json, "tps", tick.tps());
            writeDoubleSeries(json, "mspt", tick.mspt());
        }
        json.endObject();
    }

    private static void writePluginMetrics(JsonWriter json, JfrTimeline timeline) throws IOException {
        List<JfrTimeline.PluginMetric> metrics = timeline == null ? List.of() : timeline.pluginMetrics();
        if (metrics.isEmpty()) {
            json.name("pluginMetrics").nullValue();
            return;
        }
        json.name("pluginMetrics").beginArray();
        for (JfrTimeline.PluginMetric metric : metrics) {
            json.beginObject();
            json.name("name").value(metric.name());
            json.name("kind").value(metric.kind());
            writeDoubleSeries(json, "series", metric.series());
            json.endObject();
        }
        json.endArray();
    }

    private static void writeRetained(JsonWriter json, JfrTimeline timeline) throws IOException {
        List<JfrTimeline.Retained> retained = timeline == null ? List.of() : timeline.retained();
        if (retained.isEmpty()) {
            json.name("retained").nullValue();
            return;
        }
        json.name("retained").beginArray();
        for (JfrTimeline.Retained entry : retained) {
            json.beginArray();
            json.value(entry.className());
            json.value(entry.count());
            json.value(entry.allocSite());
            json.endArray();
        }
        json.endArray();
    }

    private static void writeSlowIo(JsonWriter json, JfrTimeline timeline) throws IOException {
        List<JfrTimeline.SlowIo> slowIo = timeline == null ? List.of() : timeline.slowIo();
        if (slowIo.isEmpty()) {
            json.name("slowIo").nullValue();
            return;
        }
        json.name("slowIo").beginArray();
        for (JfrTimeline.SlowIo entry : slowIo) {
            json.beginArray();
            json.value(entry.path());
            json.value(round2(entry.durationMs()));
            json.value(entry.bytes());
            json.value(entry.kind());
            json.endArray();
        }
        json.endArray();
    }

    /** One parsed "Tick systems" row: "<system> @ <world>" with 1m/5m avg+max millis. */
    private record TickSystemRow(String system, String world, double avg1m, double max1m,
                                 double avg5m, double max5m) {}

    private static void writeTickSystems(JsonWriter json, Map<String, String> tickSystems) throws IOException {
        String note = null;
        List<TickSystemRow> rows = new ArrayList<>();
        if (tickSystems != null) {
            for (Map.Entry<String, String> e : tickSystems.entrySet()) {
                if ("Note".equals(e.getKey())) {
                    note = e.getValue();
                    continue;
                }
                String key = e.getKey().replaceFirst(" #\\d+$", "");
                int at = key.lastIndexOf(" @ ");
                if (at < 0) {
                    continue;
                }
                String[] parts = e.getValue().trim().split("\\s+");
                if (parts.length < 4) {
                    continue;
                }
                try {
                    rows.add(new TickSystemRow(
                        key.substring(0, at), key.substring(at + " @ ".length()),
                        Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]), Double.parseDouble(parts[3])));
                } catch (NumberFormatException ignored) {
                    // skip a malformed row
                }
            }
        }
        rows.sort((a, b) -> Double.compare(b.avg1m(), a.avg1m()));
        if (rows.isEmpty()) {
            json.name("tickSystems").nullValue();
        } else {
            json.name("tickSystems").beginArray();
            for (int i = 0; i < rows.size() && i < 20; i++) {
                TickSystemRow row = rows.get(i);
                json.beginArray();
                json.value(row.system());
                json.value(row.world());
                json.value(round2(row.avg1m()));
                json.value(round2(row.max1m()));
                json.value(round2(row.avg5m()));
                json.value(round2(row.max5m()));
                json.endArray();
            }
            json.endArray();
        }
        json.name("tickSystemsNote").value(note);
    }

    private static void writeTickSeries(JsonWriter json, JfrTimeline timeline) throws IOException {
        List<JfrTimeline.SystemTick> series = timeline == null ? List.of() : timeline.tickSeries();
        if (series.isEmpty()) {
            json.name("tickSeries").nullValue();
            return;
        }
        json.name("tickSeries").beginArray();
        for (JfrTimeline.SystemTick entry : series) {
            json.beginObject();
            json.name("name").value(entry.name());
            writeDoubleSeries(json, "series", entry.series());
            json.endObject();
        }
        json.endArray();
    }

    /** Parses "N samples · method" values from the JFR mod-attribution section. */
    /**
     * Prefers the timeline's classloader-based attribution (exact plugin identity, works for
     * recovered bundles); falls back to the capture-time "Mod hot-path contribution" section.
     */
    private static void writeModCpu(JsonWriter json, JfrTimeline timeline, Map<String, String> modCpu)
        throws IOException {
        if (timeline != null && !timeline.cpuByMod().isEmpty()) {
            json.name("modCpu").beginArray();
            for (JfrTimeline.ModCpu mod : timeline.cpuByMod()) {
                json.beginArray();
                json.value(mod.id());
                json.value(mod.samples());
                json.value(mod.method());
                json.beginArray();
                for (JfrTimeline.ThreadShare ts : mod.threads()) {
                    json.beginArray();
                    json.value(ts.thread());
                    json.value(ts.samples());
                    json.endArray();
                }
                json.endArray();
                json.endArray();
            }
            json.endArray();
            return;
        }
        if (modCpu == null || modCpu.isEmpty()) {
            json.name("modCpu").nullValue();
            return;
        }
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : modCpu.entrySet()) {
            String value = e.getValue();
            long samples = 0;
            String method = value;
            int idx = value.indexOf(" samples · ");
            if (idx > 0) {
                try {
                    samples = Long.parseLong(value.substring(0, idx).trim());
                    method = value.substring(idx + " samples · ".length()).trim();
                } catch (NumberFormatException ignored) {
                    // keep the raw value as the method text
                }
            }
            rows.add(new Object[] {e.getKey(), samples, method});
        }
        rows.sort((a, b) -> Long.compare((long) b[1], (long) a[1]));
        json.name("modCpu").beginArray();
        for (Object[] row : rows) {
            json.beginArray();
            json.value((String) row[0]);
            json.value((long) row[1]);
            json.value((String) row[2]);
            json.endArray();
        }
        json.endArray();
    }

    private static void writeGcCauses(JsonWriter json, JfrTimeline timeline) throws IOException {
        if (timeline == null || timeline.gcCauses().isEmpty()) {
            json.name("gcCauses").nullValue();
            return;
        }
        json.name("gcCauses").beginArray();
        for (JfrTimeline.GcCause cause : timeline.gcCauses()) {
            json.beginArray();
            json.value(cause.cause());
            json.value(cause.count());
            json.value(cause.totalPauseMs());
            json.endArray();
        }
        json.endArray();
    }

    private static void writeGcOverlap(JsonWriter json, JfrTimeline timeline) throws IOException {
        int[] buckets = timeline == null ? null : timeline.gcOverlapBuckets();
        if (buckets == null || buckets.length == 0) {
            json.name("gcOverlap").nullValue();
            return;
        }
        json.name("gcOverlap").beginArray();
        for (int bucket : buckets) {
            json.value(bucket);
        }
        json.endArray();
    }

    private static void writeFlame(JsonWriter json, JfrTimeline timeline) throws IOException {
        JfrTimeline.Flame flame = timeline == null ? null : timeline.flame();
        json.name("flame");
        if (flame == null) {
            json.nullValue();
            return;
        }
        writeFlameNode(json, flame);
    }

    private static void writeFlameNode(JsonWriter json, JfrTimeline.Flame node) throws IOException {
        json.beginArray();
        json.value(node.name());
        json.value(node.samples());
        json.beginArray();
        for (JfrTimeline.Flame child : node.children()) {
            writeFlameNode(json, child);
        }
        json.endArray();
        json.endArray();
    }

    private static void writeThreadTimeline(JsonWriter json, JfrTimeline timeline) throws IOException {
        if (timeline == null || timeline.threadTimeline().isEmpty()) {
            json.name("threadTimeline").nullValue();
            return;
        }
        json.name("threadTimeline").beginArray();
        for (JfrTimeline.ThreadLane lane : timeline.threadTimeline()) {
            json.beginObject();
            json.name("name").value(lane.name());
            json.name("states").beginArray();
            for (int state : lane.states()) {
                json.value(state);
            }
            json.endArray();
            json.endObject();
        }
        json.endArray();
    }

    /** "Heap histogram" section entries are {@code className -> "instances bytes"}. */
    private static void writeHeapHistogram(JsonWriter json, Map<String, String> heapHistogram)
        throws IOException {
        if (heapHistogram == null || heapHistogram.isEmpty()) {
            json.name("heapHistogram").nullValue();
            return;
        }
        json.name("heapHistogram").beginArray();
        for (Map.Entry<String, String> e : heapHistogram.entrySet()) {
            String[] parts = e.getValue().trim().split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            try {
                long instances = Long.parseLong(parts[0]);
                long bytes = Long.parseLong(parts[1]);
                json.beginArray();
                json.value(e.getKey());
                json.value(instances);
                json.value(bytes);
                json.endArray();
            } catch (NumberFormatException ignored) {
                // skip a malformed histogram row
            }
        }
        json.endArray();
    }

    /** "Memory pools" section entries are {@code poolName -> "used committed max collectionUsed"}. */
    private static void writeMemPools(JsonWriter json, Map<String, String> memPools) throws IOException {
        if (memPools == null || memPools.isEmpty()) {
            json.name("memPools").nullValue();
            return;
        }
        json.name("memPools").beginArray();
        for (Map.Entry<String, String> e : memPools.entrySet()) {
            String[] parts = e.getValue().trim().split("\\s+");
            if (parts.length < 4) {
                continue;
            }
            try {
                long used = Long.parseLong(parts[0]);
                long committed = Long.parseLong(parts[1]);
                long max = Long.parseLong(parts[2]);
                long collectionUsed = Long.parseLong(parts[3]);
                json.beginArray();
                json.value(e.getKey());
                json.value(used);
                json.value(committed);
                json.value(max);
                json.value(collectionUsed);
                json.endArray();
            } catch (NumberFormatException ignored) {
                // skip a malformed pool row
            }
        }
        json.endArray();
    }

    /** "Entities" section entries are {@code world -> "Type xN, Type xN, ..."}. */
    private static void writeEntities(JsonWriter json, Map<String, String> entities) throws IOException {
        if (entities == null || entities.isEmpty()) {
            json.name("entities").nullValue();
            return;
        }
        json.name("entities").beginArray();
        for (Map.Entry<String, String> e : entities.entrySet()) {
            json.beginArray();
            json.value(e.getKey());
            json.beginArray();
            for (String part : e.getValue().split(", ")) {
                int idx = part.lastIndexOf(" x");
                if (idx <= 0) {
                    continue;
                }
                try {
                    long count = Long.parseLong(part.substring(idx + 2).trim());
                    json.beginArray();
                    json.value(part.substring(0, idx).trim());
                    json.value(count);
                    json.endArray();
                } catch (NumberFormatException ignored) {
                    // skip a malformed entity entry
                }
            }
            json.endArray();
            json.endArray();
        }
        json.endArray();
    }

    private static void writeThreads(JsonWriter json, HealthSnapshot snapshot, JfrTimeline timeline)
        throws IOException {
        HealthSnapshot.Threads threads = snapshot == null ? null : snapshot.threads();
        writeNullable(json, "threadTotal", threads != null && threads.total() > 0 ? (long) threads.total() : null);
        if (threads == null || threads.byState().isEmpty()) {
            json.name("threadStates").nullValue();
        } else {
            json.name("threadStates").beginObject();
            for (Map.Entry<String, Integer> e : threads.byState().entrySet()) {
                json.name(e.getKey()).value(e.getValue());
            }
            json.endObject();
        }
        if (threads == null || threads.deadlocked().isEmpty()) {
            json.name("deadlocked").nullValue();
        } else {
            json.name("deadlocked").beginArray();
            for (String d : threads.deadlocked()) {
                json.value(d);
            }
            json.endArray();
        }

        json.name("hotThreads").beginArray();
        if (snapshot != null) {
            Map<String, String> states = timeline == null ? Map.of() : timeline.threadStates();
            for (HealthSnapshot.HotThread hot : snapshot.hotThreads()) {
                json.beginArray();
                json.value(hot.samples());
                json.value(states.get(hot.thread()));
                json.value(hot.thread());
                json.value(hot.topMethod());
                json.endArray();
            }
        }
        json.endArray();
    }

    private static void writeHotMethods(JsonWriter json, JfrTimeline timeline) throws IOException {
        json.name("hotMethods").beginArray();
        if (timeline != null) {
            for (JfrTimeline.HotMethod hm : timeline.hotMethods()) {
                json.beginArray();
                json.value(hm.samples());
                json.value(hm.method());
                json.beginArray();
                for (String caller : hm.callers()) {
                    json.value(caller);
                }
                json.endArray();
                json.endArray();
            }
        }
        json.endArray();
    }

    private static void writeWorlds(JsonWriter json, IncidentMetadata meta, HealthSnapshot snapshot,
                                    JfrTimeline timeline) throws IOException {
        Map<String, Long> samples = timeline == null ? Map.of() : timeline.worldSamples();
        json.name("worlds").beginArray();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (snapshot != null) {
            for (HealthSnapshot.World w : snapshot.worlds()) {
                seen.add(worldShort(w.name()));
                json.beginObject();
                json.name("name").value(w.name());
                writeNullable(json, "samples", samplesForWorld(samples, w.name()));
                json.name("stalled").value(w.name().equals(meta.world()));
                writeNullable(json, "players", w.players() >= 0 ? (long) w.players() : null);
                if (w.playerNames().isEmpty()) {
                    json.name("playerNames").nullValue();
                } else {
                    json.name("playerNames").beginArray();
                    for (String name : w.playerNames()) {
                        json.value(name);
                    }
                    json.endArray();
                }
                writeNullable(json, "entities", w.entities() >= 0 ? (long) w.entities() : null);
                writeNullable(json, "chunks", w.chunks() >= 0 ? (long) w.chunks() : null);
                writeNullableDouble(json, "tps", w.tps() >= 0 ? w.tps() : null);
                writeNullableDouble(json, "mspt", w.mspt() >= 0 ? w.mspt() : null);
                writeNullableDouble(json, "msptP50", w.msptP50() >= 0 ? w.msptP50() : null);
                writeNullableDouble(json, "msptP95", w.msptP95() >= 0 ? w.msptP95() : null);
                writeNullableDouble(json, "msptMax", w.msptMax() >= 0 ? w.msptMax() : null);
                json.endObject();
            }
        }
        for (Map.Entry<String, Long> e : samples.entrySet()) {
            if (!seen.add(worldShort(e.getKey()))) {
                continue;
            }
            json.beginObject();
            json.name("name").value(e.getKey());
            json.name("samples").value(e.getValue());
            json.name("stalled").value(e.getKey().equals(meta.world()));
            json.name("players").nullValue();
            json.name("playerNames").nullValue();
            json.name("entities").nullValue();
            json.name("chunks").nullValue();
            json.name("tps").nullValue();
            json.name("mspt").nullValue();
            json.name("msptP50").nullValue();
            json.name("msptP95").nullValue();
            json.name("msptMax").nullValue();
            json.endObject();
        }
        json.endArray();
    }

    private static Long samplesForWorld(Map<String, Long> samples, String name) {
        Long exact = samples.get(name);
        if (exact != null) {
            return exact;
        }
        String shortName = worldShort(name);
        long sum = 0;
        boolean found = false;
        for (Map.Entry<String, Long> e : samples.entrySet()) {
            if (worldShort(e.getKey()).equals(shortName)) {
                sum += e.getValue();
                found = true;
            }
        }
        return found ? sum : null;
    }

    /** Per-world TPS series: the top worlds by CPU samples, always including the selected tick world. */
    private static void writeWorldTps(JsonWriter json, JfrTimeline timeline, TickSelection tick)
        throws IOException {
        if (timeline == null || timeline.ticks().isEmpty()) {
            json.name("worldTps").nullValue();
            return;
        }
        Map<String, JfrTimeline.WorldTicks> ticks = timeline.ticks();
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (tick != null) {
            selected.add(tick.world());
        }
        ticks.keySet().stream()
            .sorted((a, b) -> Long.compare(
                timeline.worldSamples().getOrDefault(b, 0L), timeline.worldSamples().getOrDefault(a, 0L)))
            .forEach(world -> {
                if (selected.size() < MAX_WORLD_TPS) {
                    selected.add(world);
                }
            });
        json.name("worldTps").beginArray();
        for (String world : selected) {
            JfrTimeline.WorldTicks wt = ticks.get(world);
            if (wt == null || wt.tps() == null) {
                continue;
            }
            json.beginObject();
            json.name("name").value(world);
            writeDoubleSeries(json, "series", wt.tps());
            json.endObject();
        }
        json.endArray();
    }

    private static void writeSubsystems(JsonWriter json, JfrTimeline timeline) throws IOException {
        if (timeline == null || timeline.subsystems().isEmpty()) {
            json.name("subsystems").nullValue();
            return;
        }
        json.name("subsystems").beginArray();
        for (Map.Entry<String, Long> e : timeline.subsystems().entrySet()) {
            json.beginArray();
            json.value(e.getKey());
            json.value(e.getValue());
            json.endArray();
        }
        json.endArray();
    }

    private static void writePlugins(JsonWriter json, Map<String, String> plugins) throws IOException {
        writeModRows(json, "plugins", plugins);
    }

    private static void writeAssetPacks(JsonWriter json, Map<String, String> assetPacks) throws IOException {
        writeModRows(json, "assetPacks", assetPacks);
    }

    private static void writeModRows(JsonWriter json, String key, Map<String, String> entries) throws IOException {
        if (entries == null || entries.isEmpty()) {
            json.name(key).nullValue();
            return;
        }
        json.name(key).beginArray();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String id = e.getKey().replaceFirst(" #\\d+$", "");
            int colon = id.indexOf(':');
            String author = colon > 0 ? id.substring(0, colon) : "";
            String name = colon > 0 ? id.substring(colon + 1) : id;
            String[] parts = e.getValue().split(" @ ", 3);
            json.beginArray();
            json.value(author);
            json.value(name);
            json.value(parts[0]);
            json.value(author.equalsIgnoreCase("hytale"));
            json.value(parts.length > 1 ? parts[1] : null);
            json.value(parts.length > 2 ? parts[2] : null);
            json.endArray();
        }
        json.endArray();
    }

    private static void writeMixins(JsonWriter json, Map<String, String> mixins) throws IOException {
        boolean hasContent = mixins != null && mixins.keySet().stream().anyMatch(k ->
            k.startsWith("Conflict: ")
                || (!"Bootstrapper".equals(k) && !"Conflicts".equals(k)));
        if (!hasContent) {
            json.name("mixins").nullValue();
            return;
        }
        json.name("mixins").beginObject();
        json.name("bootstrapper").value(mixins.get("Bootstrapper"));
        json.name("configs").beginArray();
        for (Map.Entry<String, String> e : mixins.entrySet()) {
            if ("Bootstrapper".equals(e.getKey()) || "Conflicts".equals(e.getKey())
                || e.getKey().startsWith("Conflict: ")) {
                continue;
            }
            json.beginArray();
            json.value(e.getKey());
            json.beginArray();
            for (String cls : e.getValue().split(", ")) {
                if (!cls.isBlank()) {
                    json.value(cls);
                }
            }
            json.endArray();
            json.endArray();
        }
        json.endArray();
        // "Conflict: <target>" entries → conflicts list; a bare "Conflicts"="none" entry means
        // targets were resolvable and nothing overlaps (empty list); neither → unknown (null).
        boolean resolvable = "none".equals(mixins.get("Conflicts"));
        json.name("conflicts");
        boolean any = mixins.keySet().stream().anyMatch(k -> k.startsWith("Conflict: "));
        if (!any && !resolvable) {
            json.nullValue();
        } else {
            json.beginArray();
            for (Map.Entry<String, String> e : mixins.entrySet()) {
                if (!e.getKey().startsWith("Conflict: ")) {
                    continue;
                }
                json.beginArray();
                json.value(e.getKey().substring("Conflict: ".length()));
                json.beginArray();
                // Each participant is "config → MixinName" (or just "config" when the
                // mixin names were not resolvable), separated by "; ".
                for (String participant : e.getValue().split("; ")) {
                    if (participant.isBlank()) {
                        continue;
                    }
                    int arrow = participant.indexOf(" → ");
                    json.beginArray();
                    if (arrow < 0) {
                        json.value(participant.trim());
                        json.nullValue();
                    } else {
                        json.value(participant.substring(0, arrow).trim());
                        json.value(participant.substring(arrow + " → ".length()).trim());
                    }
                    json.endArray();
                }
                json.endArray();
                json.endArray();
            }
            json.endArray();
        }
        json.endObject();
    }

    private static void writeLog(JsonWriter json, List<LogLine> entries) throws IOException {
        if (entries.isEmpty()) {
            json.name("log").nullValue();
            return;
        }
        json.name("log").beginArray();
        for (LogLine entry : entries) {
            json.beginArray();
            json.value(entry.level());
            json.value(entry.source());
            json.value(entry.message());
            json.endArray();
        }
        json.endArray();
    }

    /** One parsed server-log entry; {@code epochMs} is -1 when the line carried no timestamp. */
    record LogLine(String level, String source, String message, long epochMs) {
        LogLine appendMessage(String line) {
            return new LogLine(level, source, message + "\n" + line, epochMs);
        }
    }

    /**
     * Parses Hytale's log format into entries. Lines that do not match (stack traces, wrapped
     * output) are appended to the previous entry's message; leading unmatched lines become
     * entries of their own with an empty level/source.
     */
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
                    epochMs = java.time.LocalDateTime.of(
                            Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                            Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                            Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)))
                        .toInstant(ZoneOffset.UTC).toEpochMilli();
                } catch (Exception ignored) {
                    // keep -1: the entry still renders, it just carries no event position
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

    private static void writeConfigs(JsonWriter json, List<DiagnosticSection> configs) throws IOException {
        json.name("configs").beginArray();
        for (DiagnosticSection section : configs) {
            json.beginObject();
            json.name("title").value(section.title());
            json.name("content").value(section.preformatted());
            json.endObject();
        }
        json.endArray();
    }

    private static void writeGeneric(JsonWriter json, List<DiagnosticSection> generic, boolean hasPlugins)
        throws IOException {
        json.name("diag").beginArray();
        for (DiagnosticSection section : generic) {
            Map<String, String> entries = section.entries();
            if ("Environment".equals(section.title())) {
                Map<String, String> trimmed = new LinkedHashMap<>(entries);
                trimmed.remove("Server name");
                trimmed.remove("Hytale version");
                trimmed.remove("Container runtime");
                for (String key : LOADER_KEYS) {
                    trimmed.remove(key);
                }
                if (hasPlugins) {
                    trimmed.remove("Mods");
                    trimmed.remove("Mods (count)");
                }
                entries = trimmed;
            }
            if (entries.isEmpty()) {
                continue;
            }
            json.beginObject();
            json.name("title").value(section.title());
            json.name("entries").beginArray();
            for (Map.Entry<String, String> e : entries.entrySet()) {
                json.beginArray();
                json.value(e.getKey());
                json.value(e.getValue());
                json.endArray();
            }
            json.endArray();
            json.endObject();
        }
        json.endArray();
    }

    // ---- small helpers ------------------------------------------------------------------------

    private static void writeNullable(JsonWriter json, String name, Long value) throws IOException {
        json.name(name);
        if (value == null) {
            json.nullValue();
        } else {
            json.value(value);
        }
    }

    private static void writeNullableDouble(JsonWriter json, String name, Double value) throws IOException {
        json.name(name);
        if (value == null) {
            json.nullValue();
        } else {
            json.value(round2(value));
        }
    }

    private static void writeDoubleSeries(JsonWriter json, String name, double[] series) throws IOException {
        json.name(name);
        if (series == null) {
            json.nullValue();
            return;
        }
        json.beginArray();
        for (double v : series) {
            json.value(round2(v));
        }
        json.endArray();
    }

    private static void writeLongSeries(JsonWriter json, String name, long[] series) throws IOException {
        json.name(name);
        if (series == null) {
            json.nullValue();
            return;
        }
        json.beginArray();
        for (long v : series) {
            json.value(v);
        }
        json.endArray();
    }

    private static double avg(double[] values) {
        if (values == null || values.length == 0) {
            return 0;
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private static double max(double[] values) {
        if (values == null || values.length == 0) {
            return 0;
        }
        double m = values[0];
        for (double v : values) {
            m = Math.max(m, v);
        }
        return m;
    }

    private static double min(double[] values) {
        if (values == null || values.length == 0) {
            return 0;
        }
        double m = values[0];
        for (double v : values) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static double round2(double v) {
        double r = Math.round(v * 100.0) / 100.0;
        return Double.isFinite(r) ? r : 0;
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
