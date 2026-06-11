package sh.harold.blackbox.core.trigger;

import java.time.Duration;
import java.util.Objects;

public record DetectorPolicy(
    ModulePolicy modules,
    int heapPressurePct,
    Duration heapPressureSustain,
    int gcPressurePct,
    Duration gcPressureWindow,
    int cpuSaturationPct,
    Duration cpuSaturationSustain,
    long netInMbps,
    long netOutMbps,
    Duration netSustain,
    int playerDropPct,
    Duration playerDropWindow,
    int playerDropMinPlayers,
    boolean logErrorSkipSentry,
    boolean logErrorRequireThrowable
) {
    public DetectorPolicy {
        modules = modules == null ? ModulePolicy.all() : modules;
        Objects.requireNonNull(heapPressureSustain, "heapPressureSustain");
        Objects.requireNonNull(gcPressureWindow, "gcPressureWindow");
        Objects.requireNonNull(cpuSaturationSustain, "cpuSaturationSustain");
        Objects.requireNonNull(netSustain, "netSustain");
        Objects.requireNonNull(playerDropWindow, "playerDropWindow");
        requirePct(heapPressurePct, "heapPressurePct");
        requirePct(gcPressurePct, "gcPressurePct");
        requirePct(cpuSaturationPct, "cpuSaturationPct");
        requirePct(playerDropPct, "playerDropPct");
        if (netInMbps < 0 || netOutMbps < 0) {
            throw new IllegalArgumentException("net thresholds must be >= 0 (0 = disabled).");
        }
        if (playerDropMinPlayers < 0) {
            throw new IllegalArgumentException("playerDropMinPlayers must be >= 0.");
        }
        requireNonNegative(heapPressureSustain, "heapPressureSustain");
        requireNonNegative(gcPressureWindow, "gcPressureWindow");
        requireNonNegative(cpuSaturationSustain, "cpuSaturationSustain");
        requireNonNegative(netSustain, "netSustain");
        requireNonNegative(playerDropWindow, "playerDropWindow");
    }

    public static DetectorPolicy defaults() {
        return new DetectorPolicy(
            ModulePolicy.all(),
            90, Duration.ofSeconds(60),
            25, Duration.ofSeconds(60),
            95, Duration.ofSeconds(60),
            0, 0, Duration.ofSeconds(30),
            50, Duration.ofSeconds(60), 8,
            true,
            true
        );
    }

    private static void requirePct(int value, String name) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(name + " must be within 0..100 (0 = disabled).");
        }
    }

    private static void requireNonNegative(Duration value, String name) {
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must be >= 0.");
        }
    }
}
