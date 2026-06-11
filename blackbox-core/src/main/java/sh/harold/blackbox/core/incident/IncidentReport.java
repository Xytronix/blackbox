package sh.harold.blackbox.core.incident;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import sh.harold.blackbox.core.health.HealthSnapshot;

/**
 * Full report data used to build an incident bundle.
 */
public record IncidentReport(
    IncidentMetadata meta,
    IncidentSummary summary,
    Map<String, String> context,
    HealthSnapshot snapshot,
    List<DiagnosticSection> diagnostics
) {
    public IncidentReport {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(context, "context");
        context = Map.copyOf(context);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public IncidentReport(IncidentMetadata meta, IncidentSummary summary, Map<String, String> context,
                          HealthSnapshot snapshot) {
        this(meta, summary, context, snapshot, List.of());
    }

    public IncidentReport(IncidentMetadata meta, IncidentSummary summary, Map<String, String> context) {
        this(meta, summary, context, null, List.of());
    }

    public IncidentReport(IncidentMetadata meta, IncidentSummary summary) {
        this(meta, summary, Map.of(), null, List.of());
    }
}
