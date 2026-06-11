package sh.harold.blackbox.core.incident;

import java.util.List;
import java.util.Objects;

public record IncidentSummary(
    String likelyCause,
    List<String> whatHappened,
    List<String> nextSteps
) {
    public IncidentSummary {
        Objects.requireNonNull(likelyCause, "likelyCause");
        Objects.requireNonNull(whatHappened, "whatHappened");
        Objects.requireNonNull(nextSteps, "nextSteps");
        whatHappened = List.copyOf(whatHappened);
        nextSteps = List.copyOf(nextSteps);
    }
}
