package sh.harold.blackbox.core.capture;

import java.util.List;

import sh.harold.blackbox.core.incident.DiagnosticSection;

/**
 * Supplies plugin-contributed diagnostic sections for the incident report.
 */
@FunctionalInterface
public interface DiagnosticsProvider {
    List<DiagnosticSection> sections();

    static DiagnosticsProvider none() {
        return List::of;
    }
}
