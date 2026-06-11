package sh.harold.blackbox.core.capture;

import java.nio.file.Path;
import sh.harold.blackbox.core.incident.IncidentReport;

@FunctionalInterface
public interface IncidentNotifier {
    void onIncident(IncidentReport report, Path zip);

    static IncidentNotifier noop() {
        return (report, zip) -> {
        };
    }
}
