package sh.harold.blackbox.core.incident;

import java.util.Map;
import java.util.Objects;

/**
 * Full report data used to build an incident bundle.
 */
public record IncidentReport(IncidentMetadata meta, IncidentSummary summary, Map<String, String> context) {
    public IncidentReport {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(context, "context");
        context = Map.copyOf(context);
    }

    public IncidentReport(IncidentMetadata meta, IncidentSummary summary) {
        this(meta, summary, Map.of());
    }
}
