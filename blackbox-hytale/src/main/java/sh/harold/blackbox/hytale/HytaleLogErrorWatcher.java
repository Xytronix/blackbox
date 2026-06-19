package sh.harold.blackbox.hytale;

import com.hypixel.hytale.logger.sentry.SkipSentryException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.logging.LogRecord;
import sh.harold.blackbox.core.trigger.DetectorPolicy;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

final class HytaleLogErrorWatcher {
    private static final String FLOGGER_LOG_SITE_STACK_TRACE = "com.google.common.flogger.LogSiteStackTrace";

    private final BlackboxRuntime runtime;
    private final Clock clock;
    private final System.Logger logger;
    private final Supplier<DetectorPolicy> detectors;
    private final Map<String, Instant> recentLogErrors = new ConcurrentHashMap<>();
    private HytaleLogWatcher logWatcher;

    HytaleLogErrorWatcher(BlackboxRuntime runtime, Clock clock, System.Logger logger,
                          Supplier<DetectorPolicy> detectors) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.detectors = Objects.requireNonNull(detectors, "detectors");
    }

    void register() {
        try {
            HytaleLogWatcher watcher = new HytaleLogWatcher(this::onSevereLog);
            watcher.register();
            this.logWatcher = watcher;
        } catch (Throwable t) {
            logger.log(System.Logger.Level.WARNING,
                "Failed to subscribe to the server log; log-error captures disabled.", t);
        }
    }

    void unregister() {
        if (logWatcher != null) {
            try {
                logWatcher.unregister();
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Log watcher shutdown failed.", e);
            }
        }
    }

    private void onSevereLog(LogRecord record) {
        DetectorPolicy detectors = this.detectors.get();
        if (detectors == null || !detectors.modules().logError()) {
            return;
        }
        Throwable thrown = record.getThrown();
        if (thrown != null && FLOGGER_LOG_SITE_STACK_TRACE.equals(thrown.getClass().getName())) {
            thrown = thrown.getCause();
        }
        if (detectors.logErrorRequireThrowable() && thrown == null) {
            return;
        }
        if (detectors.logErrorSkipSentry() && SkipSentryException.hasSkipSentry(thrown)) {
            return;
        }
        Map<String, String> attrs = logErrorAttrs(record, thrown);
        if (isIgnoredLogError(detectors, attrs.get("error"))) {
            return;
        }
        if (isDuplicateLogError(detectors, logErrorSignature(record, thrown))) {
            return;
        }
        TriggerEvent event = new TriggerEvent(
            TriggerKind.LOG_ERROR, "server", clock.instant(), attrs);
        try {
            runtime.worker().execute(() -> runtime.capture(event));
        } catch (RejectedExecutionException e) {
        }
    }

    private static boolean isIgnoredLogError(DetectorPolicy detectors, String errorText) {
        List<Pattern> ignore = detectors.logErrorIgnore();
        if (ignore.isEmpty() || errorText == null || errorText.isEmpty()) {
            return false;
        }
        for (Pattern pattern : ignore) {
            if (pattern.matcher(errorText).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean isDuplicateLogError(DetectorPolicy detectors, String signature) {
        Duration window = detectors.logErrorDedupeWindow();
        if (window == null || window.isZero()) {
            return false;
        }
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        recentLogErrors.values().removeIf(seen -> seen.isBefore(cutoff));
        return recentLogErrors.putIfAbsent(signature, now) != null;
    }

    private static String logErrorSignature(LogRecord record, Throwable thrown) {
        if (thrown != null) {
            StringBuilder sig = new StringBuilder(thrown.getClass().getName());
            String message = thrown.getMessage();
            if (message != null && !message.isBlank()) {
                sig.append(": ").append(message);
            }
            StackTraceElement[] frames = thrown.getStackTrace();
            if (frames.length > 0) {
                sig.append(" @ ").append(frames[0]);
            }
            return sig.toString();
        }
        String loggerName = record.getLoggerName();
        String message = record.getMessage();
        String firstLine = message == null ? "" : message.lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty())
            .findFirst()
            .orElse("");
        return (loggerName == null || loggerName.isBlank() ? "log" : loggerName) + ": " + firstLine;
    }

    private static Map<String, String> logErrorAttrs(LogRecord record, Throwable thrown) {
        Map<String, String> attrs = HytaleWorldFailureListener.failureAttrs(thrown);
        if (!attrs.isEmpty()) {
            return attrs;
        }
        String message = record.getMessage();
        if (message == null) {
            return Map.of();
        }
        String firstLine = message.lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty())
            .findFirst()
            .orElse("");
        if (firstLine.isEmpty()) {
            return Map.of();
        }
        String error = firstLine.length() > 200 ? firstLine.substring(0, 200) + "…" : firstLine;
        return Map.of("error", error);
    }
}
