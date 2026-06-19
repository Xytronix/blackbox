package sh.harold.blackbox.hytale;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import com.hypixel.hytale.logger.backend.HytaleLoggerBackend;

final class HytaleLogWatcher extends CopyOnWriteArrayList<LogRecord> {

    private static final long serialVersionUID = 1L;
    private static final int SEVERE = Level.SEVERE.intValue();
    private static final String OWN_PACKAGE = "sh.harold.blackbox";

    private final transient Consumer<LogRecord> onSevere;
    private final transient ThreadLocal<Boolean> handling = ThreadLocal.withInitial(() -> Boolean.FALSE);

    HytaleLogWatcher(Consumer<LogRecord> onSevere) {
        this.onSevere = onSevere;
    }

    void register() {
        HytaleLoggerBackend.subscribe(this);
    }

    void unregister() {
        HytaleLoggerBackend.unsubscribe(this);
    }

    @Override
    public boolean add(LogRecord record) {
        try {
            handle(record);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void handle(LogRecord record) {
        if (record == null || record.getLevel() == null
            || record.getLevel().intValue() < SEVERE) {
            return;
        }
        String loggerName = record.getLoggerName();
        if (loggerName != null && loggerName.startsWith(OWN_PACKAGE)) {
            return;
        }
        if (Boolean.TRUE.equals(handling.get())) {
            return;
        }
        handling.set(Boolean.TRUE);
        try {
            onSevere.accept(record);
        } finally {
            handling.set(Boolean.FALSE);
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
