package sh.harold.blackbox.core.trigger;

/**
 * Supported trigger types.
 */
public enum TriggerKind {
    MANUAL,
    HEARTBEAT_STALL,
    WORLD_FAILURE,
    TICK_DEGRADED,
    DEADLOCK,
    HEAP_PRESSURE,
    GC_PRESSURE,
    CPU_SATURATION,
    NET_SATURATION,
    PLAYER_DROP,
    LOG_ERROR
}
