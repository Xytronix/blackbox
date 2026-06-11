package sh.harold.blackbox.core.retention;

public record RetentionStats(
    int scanned,
    int deleted,
    long bytesDeleted,
    int deleteFailures,
    long finalBytes,
    int finalCount
) {
}
