package sh.harold.blackbox.core.bundle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import sh.harold.blackbox.core.env.EnvCollector;
import sh.harold.blackbox.core.health.JfrTimeline;
import sh.harold.blackbox.core.incident.IncidentReport;
import sh.harold.blackbox.core.json.IncidentJson;
import sh.harold.blackbox.core.report.ReportHtml;

public final class BundleBuilder {
    private final Clock clock;
    private final System.Logger logger;

    public BundleBuilder(Clock clock) {
        this(clock, System.getLogger(BundleBuilder.class.getName()));
    }

    public BundleBuilder(Clock clock, System.Logger logger) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public Path build(
        IncidentReport report,
        Path recordingJfr,
        Path outputZip,
        List<BundleAttachment> extras
    ) throws IOException {
        return build(report, recordingJfr, outputZip, extras, BundleArtifacts.ALL);
    }

    public Path build(
        IncidentReport report,
        Path recordingJfr,
        Path outputZip,
        List<BundleAttachment> extras,
        Set<String> artifacts
    ) throws IOException {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(recordingJfr, "recordingJfr");
        Objects.requireNonNull(outputZip, "outputZip");
        Set<String> enabled = artifacts == null ? BundleArtifacts.ALL : artifacts;

        List<BundleAttachment> sortedExtras = new ArrayList<>(extras == null ? List.of() : extras);
        sortedExtras.sort(Comparator.comparing(BundleAttachment::pathInZip));

        Path parent = outputZip.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outputZip))) {
            writeIncidentJson(report, zip);
            if (enabled.contains(BundleArtifacts.REPORT)) {
                writeReportHtml(report, recordingJfr, zip);
            }
            if (enabled.contains(BundleArtifacts.JFR)) {
                writeRecording(recordingJfr, zip);
            }
            if (enabled.contains(BundleArtifacts.ENV)) {
                writeEnvFiles(zip);
            }
            writeExtras(sortedExtras, zip, enabled);
        }

        return outputZip;
    }

    private void writeIncidentJson(IncidentReport report, ZipOutputStream zip) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        IncidentJson.write(report, buffer);
        ZipEntry entry = zipEntry("incident.json");
        zip.putNextEntry(entry);
        zip.write(buffer.toByteArray());
        zip.closeEntry();
    }

    private void writeRecording(Path recordingJfr, ZipOutputStream zip) throws IOException {
        ZipEntry entry = zipEntry("recording.jfr");
        zip.putNextEntry(entry);
        try (InputStream in = Files.newInputStream(recordingJfr)) {
            in.transferTo(zip);
        }
        zip.closeEntry();
    }

    private void writeReportHtml(IncidentReport report, Path recordingJfr, ZipOutputStream zip) throws IOException {
        JfrTimeline timeline = null;
        try {
            timeline = JfrTimeline.parse(recordingJfr);
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "JFR timeline parse failed; report renders without series.", e);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ReportHtml.write(report, timeline, buffer);
        ZipEntry entry = zipEntry("report.html");
        zip.putNextEntry(entry);
        zip.write(buffer.toByteArray());
        zip.closeEntry();
    }

    private void writeEnvFiles(ZipOutputStream zip) throws IOException {
        ZipEntry jvm = zipEntry("env/jvm.txt");
        zip.putNextEntry(jvm);
        zip.write(EnvCollector.jvmInfo().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();

        ZipEntry os = zipEntry("env/os.txt");
        zip.putNextEntry(os);
        zip.write(EnvCollector.osInfo().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void writeExtras(List<BundleAttachment> extras, ZipOutputStream zip, Set<String> enabled) throws IOException {
        for (BundleAttachment extra : extras) {
            String key = BundleArtifacts.keyForPath(extra.pathInZip());
            if (key != null && !enabled.contains(key)) {
                continue;
            }
            ZipEntry entry = zipEntry(extra.pathInZip());
            zip.putNextEntry(entry);
            zip.write(extra.data());
            zip.closeEntry();
        }
    }

    private static ZipEntry zipEntry(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        return entry;
    }
}
