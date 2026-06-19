package sh.harold.blackbox.core.trigger;

public record ModulePolicy(
    boolean heartbeatStall,
    boolean tickDegraded,
    boolean deadlock,
    boolean heapPressure,
    boolean gcPressure,
    boolean cpuSaturation,
    boolean netSaturation,
    boolean playerDrop,
    boolean logError
) {
    public static ModulePolicy all() {
        return new ModulePolicy(true, true, true, true, true, true, true, true, true);
    }
}
