package sh.harold.blackbox.core.incident;

public record IncidentId(String value) {
    public IncidentId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("IncidentId value must be non-blank.");
        }
    }
}
