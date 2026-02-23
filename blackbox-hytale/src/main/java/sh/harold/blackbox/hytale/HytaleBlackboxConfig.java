package sh.harold.blackbox.hytale;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.util.Config;

import sh.harold.blackbox.core.capture.CapturePolicy;
import sh.harold.blackbox.core.config.BlackboxConfig;
import sh.harold.blackbox.core.env.TextRedactor;
import sh.harold.blackbox.core.notify.discord.DiscordWebhookConfig;
import sh.harold.blackbox.core.retention.RetentionPolicy;
import sh.harold.blackbox.core.trigger.TriggerPolicy;

/**
 * Loads {@link BlackboxConfig} via Hytale's built-in {@link Config} system.
 *
 * <p>Stored at {@code <pluginDataDir>/blackbox.json}.
 */
final class HytaleBlackboxConfig {
    private static final String FILE_NAME = "blackbox";

    private static final Duration DEFAULT_JFR_MAX_AGE = Duration.ofMinutes(15);
    private static final long DEFAULT_JFR_MAX_SIZE_BYTES = 256L * 1024L * 1024L;
    private static final String DEFAULT_JFR_RECORDING_NAME = "blackbox";

    private static final Duration DEFAULT_TRIGGER_COOLDOWN = Duration.ofSeconds(30);
    private static final Duration DEFAULT_TRIGGER_DEBOUNCE = Duration.ofSeconds(2);
    private static final long DEFAULT_STALL_DEGRADED_MS = 2_000L;
    private static final long DEFAULT_STALL_CRITICAL_MS = 10_000L;

    private static final int DEFAULT_RETENTION_MAX_COUNT = 25;
    private static final long DEFAULT_RETENTION_MAX_TOTAL_BYTES = 1024L * 1024L * 1024L;
    private static final Duration DEFAULT_RETENTION_MAX_AGE = Duration.ofDays(7);

    private static final String DEFAULT_DISCORD_WEBHOOK_URL = "";
    private static final Duration DEFAULT_DISCORD_COOLDOWN = Duration.ofMinutes(1);
    private static final Duration DEFAULT_DISCORD_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String DEFAULT_DISCORD_USERNAME = "Blackbox";

    private static final boolean DEFAULT_WEB_ENABLED = false;

    private static final boolean DEFAULT_CAPTURE_ENABLED = true;
    private static final boolean DEFAULT_CAPTURE_ALLOW_PLUGIN_EXTRAS = true;
    private static final int DEFAULT_CAPTURE_LOG_TAIL_LINES = 500;
    private static final List<String> DEFAULT_CAPTURE_REDACT_PATTERNS = TextRedactor.DEFAULT_PATTERNS;

    private HytaleBlackboxConfig() {
    }

    static Path path(Path dataDir) {
        Objects.requireNonNull(dataDir, "dataDir");
        return dataDir.resolve(FILE_NAME + ".json");
    }

    static BlackboxConfig loadOrCreate(Path dataDir, System.Logger logger) {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(logger, "logger");

        Path configPath = path(dataDir);
        boolean existed;
        try {
            existed = Files.exists(configPath);
        } catch (Exception e) {
            existed = false;
        }

        Config<FileConfig> configFile = new Config<>(dataDir, FILE_NAME, FileConfig.CODEC);

        FileConfig raw;
        try {
            raw = configFile.load().join();
        } catch (Exception e) {
            logger.log(
                System.Logger.Level.WARNING,
                String.format("Failed to load Blackbox config from %s; using defaults.", configPath),
                e
            );
            raw = new FileConfig();
        }

        if (!existed) {
            configFile.save().exceptionally(ex -> {
                logger.log(System.Logger.Level.WARNING,
                    String.format("Failed to write default Blackbox config to %s", configPath), ex);
                return null;
            });
            logger.log(System.Logger.Level.INFO, String.format("Wrote default config: %s", configPath));
        }

        return raw.toCoreConfig(logger);
    }

    private static BlackboxConfig defaultCoreConfig() {
        TriggerPolicy triggerPolicy = new TriggerPolicy(
            DEFAULT_TRIGGER_COOLDOWN,
            DEFAULT_TRIGGER_DEBOUNCE,
            DEFAULT_STALL_DEGRADED_MS,
            DEFAULT_STALL_CRITICAL_MS
        );
        RetentionPolicy retentionPolicy = new RetentionPolicy(
            DEFAULT_RETENTION_MAX_COUNT,
            DEFAULT_RETENTION_MAX_TOTAL_BYTES,
            DEFAULT_RETENTION_MAX_AGE
        );
        DiscordWebhookConfig discord = new DiscordWebhookConfig(
            DEFAULT_DISCORD_WEBHOOK_URL,
            DEFAULT_DISCORD_COOLDOWN,
            DEFAULT_DISCORD_REQUEST_TIMEOUT,
            DEFAULT_DISCORD_USERNAME
        );
        return new BlackboxConfig(
            DEFAULT_JFR_MAX_AGE,
            DEFAULT_JFR_MAX_SIZE_BYTES,
            DEFAULT_JFR_RECORDING_NAME,
            List.of(),
            triggerPolicy,
            new CapturePolicy(retentionPolicy, DEFAULT_CAPTURE_ENABLED,
                DEFAULT_CAPTURE_ALLOW_PLUGIN_EXTRAS, DEFAULT_CAPTURE_LOG_TAIL_LINES,
                DEFAULT_CAPTURE_REDACT_PATTERNS),
            discord,
            DEFAULT_WEB_ENABLED
        );
    }

    private static final class FileConfig {
        public int version = 1;
        public Jfr jfr = new Jfr();
        public Trigger trigger = new Trigger();
        public Retention retention = new Retention();
        public Capture capture = new Capture();
        public Discord discord = new Discord();
        public Web web = new Web();

        static final BuilderCodec<FileConfig> CODEC = BuilderCodec
            .builder(FileConfig.class, FileConfig::new)
            .append(new KeyedCodec<>("Version", Codec.INTEGER), (c, v, ei) -> {
                if (v != null) {
                    c.version = v;
                }
            }, (c, ei) -> c.version).add()
            .append(new KeyedCodec<>("Jfr", Jfr.CODEC), (c, v, ei) -> {
                if (v != null) {
                    c.jfr = v;
                }
            }, (c, ei) -> c.jfr).add()
            .append(new KeyedCodec<>("Trigger", Trigger.CODEC), (c, v, ei) -> {
                if (v != null) {
                    c.trigger = v;
                }
            }, (c, ei) -> c.trigger).add()
            .append(new KeyedCodec<>("Retention", Retention.CODEC), (c, v, ei) -> {
                if (v != null) {
                    c.retention = v;
                }
            }, (c, ei) -> c.retention).add()
            .append(new KeyedCodec<>("Capture", Capture.CODEC), (c, v, ei) -> {
                if (v != null) {
                    c.capture = v;
                }
            }, (c, ei) -> c.capture).add()
            .append(new KeyedCodec<>("Discord", Discord.CODEC), (c, v, ei) -> {
                if (v != null) {
                    c.discord = v;
                }
            }, (c, ei) -> c.discord).add()
            .append(new KeyedCodec<>("Web", Web.CODEC), (c, v, ei) -> {
                if (v != null) {
                    c.web = v;
                }
            }, (c, ei) -> c.web).add()
            .build();

        BlackboxConfig toCoreConfig(System.Logger logger) {
            Objects.requireNonNull(logger, "logger");

            Jfr jfrCfg = this.jfr == null ? new Jfr() : this.jfr;
            Trigger triggerCfg = this.trigger == null ? new Trigger() : this.trigger;
            Retention retentionCfg = this.retention == null ? new Retention() : this.retention;
            Capture captureCfg = this.capture == null ? new Capture() : this.capture;
            Discord discordCfg = this.discord == null ? new Discord() : this.discord;
            Web webCfg = this.web == null ? new Web() : this.web;

            Duration jfrMaxAge = positiveDuration(jfrCfg.maxAge, DEFAULT_JFR_MAX_AGE, "Jfr.MaxAge", logger);
            long jfrMaxSizeBytes = positiveLong(jfrCfg.maxSizeBytes, DEFAULT_JFR_MAX_SIZE_BYTES, "Jfr.MaxSizeBytes", logger);
            String recordingName = nonBlankString(
                jfrCfg.recordingName,
                DEFAULT_JFR_RECORDING_NAME,
                "Jfr.RecordingName",
                logger
            );
            List<String> jfrDisabledEvents = jfrCfg.disabledEvents == null ? List.of() : List.copyOf(jfrCfg.disabledEvents);

            Duration cooldown = nonNegativeDuration(
                triggerCfg.cooldown,
                DEFAULT_TRIGGER_COOLDOWN,
                "Trigger.Cooldown",
                logger
            );
            Duration debounce = nonNegativeDuration(
                triggerCfg.debounce,
                DEFAULT_TRIGGER_DEBOUNCE,
                "Trigger.Debounce",
                logger
            );
            long stallDegradedMs = positiveLong(
                triggerCfg.stallDegradedMs,
                DEFAULT_STALL_DEGRADED_MS,
                "Trigger.StallDegradedMs",
                logger
            );
            long stallCriticalMs = positiveLong(
                triggerCfg.stallCriticalMs,
                DEFAULT_STALL_CRITICAL_MS,
                "Trigger.StallCriticalMs",
                logger
            );
            if (stallCriticalMs < stallDegradedMs) {
                logger.log(
                    System.Logger.Level.WARNING,
                    String.format("Config Trigger.StallCriticalMs (%d) is < Trigger.StallDegradedMs (%d); clamping.",
                        stallCriticalMs, stallDegradedMs)
                );
                stallCriticalMs = stallDegradedMs;
            }

            int maxCount = nonNegativeInt(retentionCfg.maxCount, DEFAULT_RETENTION_MAX_COUNT, "Retention.MaxCount", logger);
            long maxTotalBytes = nonNegativeLong(
                retentionCfg.maxTotalBytes,
                DEFAULT_RETENTION_MAX_TOTAL_BYTES,
                "Retention.MaxTotalBytes",
                logger
            );
            Duration maxAge = retentionCfg.maxAge;
            if (maxAge != null && maxAge.isNegative()) {
                logger.log(
                    System.Logger.Level.WARNING,
                    String.format("Config Retention.MaxAge is negative; using default %s.", DEFAULT_RETENTION_MAX_AGE)
                );
                maxAge = DEFAULT_RETENTION_MAX_AGE;
            }

            String webhookUrl = discordCfg.webhookUrl == null ? DEFAULT_DISCORD_WEBHOOK_URL : discordCfg.webhookUrl;
            Duration webhookCooldown = nonNegativeDuration(
                discordCfg.cooldown,
                DEFAULT_DISCORD_COOLDOWN,
                "Discord.Cooldown",
                logger
            );
            Duration requestTimeout = nonNegativeDuration(
                discordCfg.requestTimeout,
                DEFAULT_DISCORD_REQUEST_TIMEOUT,
                "Discord.RequestTimeout",
                logger
            );
            String username = nonBlankString(discordCfg.username, DEFAULT_DISCORD_USERNAME, "Discord.Username", logger);

            int logTailLines = nonNegativeInt(
                captureCfg.logTailLines,
                DEFAULT_CAPTURE_LOG_TAIL_LINES,
                "Capture.LogTailLines",
                logger
            );

            try {
                return new BlackboxConfig(
                    jfrMaxAge,
                    jfrMaxSizeBytes,
                    recordingName,
                    jfrDisabledEvents,
                    new TriggerPolicy(cooldown, debounce, stallDegradedMs, stallCriticalMs),
                    new CapturePolicy(
                        new RetentionPolicy(maxCount, maxTotalBytes, maxAge),
                        captureCfg.enabled,
                        captureCfg.allowPluginExtras,
                        logTailLines,
                        captureCfg.redactPatterns == null ? DEFAULT_CAPTURE_REDACT_PATTERNS : List.copyOf(captureCfg.redactPatterns)
                    ),
                    new DiscordWebhookConfig(webhookUrl, webhookCooldown, requestTimeout, username),
                    webCfg.enabled
                );
            } catch (RuntimeException e) {
                logger.log(System.Logger.Level.WARNING, "Invalid Blackbox config; falling back to defaults.", e);
                return defaultCoreConfig();
            }
        }

        private static Duration positiveDuration(
            Duration value,
            Duration defaultValue,
            String key,
            System.Logger logger
        ) {
            if (value == null || value.isZero() || value.isNegative()) {
                logger.log(System.Logger.Level.WARNING,
                    String.format("Config %s must be > 0; using default %s.", key, defaultValue));
                return defaultValue;
            }
            return value;
        }

        private static Duration nonNegativeDuration(
            Duration value,
            Duration defaultValue,
            String key,
            System.Logger logger
        ) {
            if (value == null || value.isNegative()) {
                logger.log(System.Logger.Level.WARNING,
                    String.format("Config %s must be >= 0; using default %s.", key, defaultValue));
                return defaultValue;
            }
            return value;
        }

        private static long positiveLong(long value, long defaultValue, String key, System.Logger logger) {
            if (value <= 0L) {
                logger.log(System.Logger.Level.WARNING,
                    String.format("Config %s must be > 0; using default %d.", key, defaultValue));
                return defaultValue;
            }
            return value;
        }

        private static long nonNegativeLong(long value, long defaultValue, String key, System.Logger logger) {
            if (value < 0L) {
                logger.log(System.Logger.Level.WARNING,
                    String.format("Config %s must be >= 0; using default %d.", key, defaultValue));
                return defaultValue;
            }
            return value;
        }

        private static int nonNegativeInt(int value, int defaultValue, String key, System.Logger logger) {
            if (value < 0) {
                logger.log(System.Logger.Level.WARNING,
                    String.format("Config %s must be >= 0; using default %d.", key, defaultValue));
                return defaultValue;
            }
            return value;
        }

        private static String nonBlankString(String value, String defaultValue, String key, System.Logger logger) {
            if (value == null || value.isBlank()) {
                logger.log(System.Logger.Level.WARNING,
                    String.format("Config %s must be non-blank; using default %s.", key, defaultValue));
                return defaultValue;
            }
            return value.trim();
        }
    }

    private static final class Jfr {
        public Duration maxAge = DEFAULT_JFR_MAX_AGE;
        public long maxSizeBytes = DEFAULT_JFR_MAX_SIZE_BYTES;
        public String recordingName = DEFAULT_JFR_RECORDING_NAME;
        public List<String> disabledEvents = List.of();

        static final BuilderCodec<Jfr> CODEC = BuilderCodec
            .builder(Jfr.class, Jfr::new)
            .append(new KeyedCodec<>("MaxAge", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.maxAge = v;
                }
            }, (c, ei) -> c.maxAge).add()
            .append(new KeyedCodec<>("MaxSizeBytes", Codec.LONG), (c, v, ei) -> {
                if (v != null) {
                    c.maxSizeBytes = v;
                }
            }, (c, ei) -> c.maxSizeBytes).add()
            .append(new KeyedCodec<>("RecordingName", Codec.STRING), (c, v, ei) -> {
                if (v != null) {
                    c.recordingName = v;
                }
            }, (c, ei) -> c.recordingName).add()
            .append(new KeyedCodec<>("DisabledEvents", Codec.STRING_ARRAY), (c, v, ei) -> {
                if (v != null) {
                    c.disabledEvents = List.of(v);
                }
            }, (c, ei) -> c.disabledEvents.toArray(new String[0])).add()
            .build();
    }

    private static final class Capture {
        public boolean enabled = DEFAULT_CAPTURE_ENABLED;
        public boolean allowPluginExtras = DEFAULT_CAPTURE_ALLOW_PLUGIN_EXTRAS;
        public int logTailLines = DEFAULT_CAPTURE_LOG_TAIL_LINES;
        public List<String> redactPatterns = new ArrayList<>(DEFAULT_CAPTURE_REDACT_PATTERNS);

        static final BuilderCodec<Capture> CODEC = BuilderCodec
            .builder(Capture.class, Capture::new)
            .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.enabled = v;
                }
            }, (c, ei) -> c.enabled).add()
            .append(new KeyedCodec<>("AllowPluginExtras", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.allowPluginExtras = v;
                }
            }, (c, ei) -> c.allowPluginExtras).add()
            .append(new KeyedCodec<>("LogTailLines", Codec.INTEGER), (c, v, ei) -> {
                if (v != null) {
                    c.logTailLines = v;
                }
            }, (c, ei) -> c.logTailLines).add()
            .append(new KeyedCodec<>("RedactPatterns", Codec.STRING_ARRAY), (c, v, ei) -> {
                if (v != null) {
                    c.redactPatterns = new ArrayList<>(List.of(v));
                }
            }, (c, ei) -> c.redactPatterns.toArray(new String[0])).add()
            .build();
    }

    private static final class Trigger {
        public Duration cooldown = DEFAULT_TRIGGER_COOLDOWN;
        public Duration debounce = DEFAULT_TRIGGER_DEBOUNCE;
        public long stallDegradedMs = DEFAULT_STALL_DEGRADED_MS;
        public long stallCriticalMs = DEFAULT_STALL_CRITICAL_MS;

        static final BuilderCodec<Trigger> CODEC = BuilderCodec
            .builder(Trigger.class, Trigger::new)
            .append(new KeyedCodec<>("Cooldown", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.cooldown = v;
                }
            }, (c, ei) -> c.cooldown).add()
            .append(new KeyedCodec<>("Debounce", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.debounce = v;
                }
            }, (c, ei) -> c.debounce).add()
            .append(new KeyedCodec<>("StallDegradedMs", Codec.LONG), (c, v, ei) -> {
                if (v != null) {
                    c.stallDegradedMs = v;
                }
            }, (c, ei) -> c.stallDegradedMs).add()
            .append(new KeyedCodec<>("StallCriticalMs", Codec.LONG), (c, v, ei) -> {
                if (v != null) {
                    c.stallCriticalMs = v;
                }
            }, (c, ei) -> c.stallCriticalMs).add()
            .build();
    }

    private static final class Retention {
        public int maxCount = DEFAULT_RETENTION_MAX_COUNT;
        public long maxTotalBytes = DEFAULT_RETENTION_MAX_TOTAL_BYTES;
        public Duration maxAge = DEFAULT_RETENTION_MAX_AGE;

        static final BuilderCodec<Retention> CODEC = BuilderCodec
            .builder(Retention.class, Retention::new)
            .append(new KeyedCodec<>("MaxCount", Codec.INTEGER), (c, v, ei) -> {
                if (v != null) {
                    c.maxCount = v;
                }
            }, (c, ei) -> c.maxCount).add()
            .append(new KeyedCodec<>("MaxTotalBytes", Codec.LONG), (c, v, ei) -> {
                if (v != null) {
                    c.maxTotalBytes = v;
                }
            }, (c, ei) -> c.maxTotalBytes).add()
            .append(new KeyedCodec<>("MaxAge", Codec.DURATION),
                (c, v, ei) -> c.maxAge = v, (c, ei) -> c.maxAge).add()
            .build();
    }

    private static final class Discord {
        public String webhookUrl = DEFAULT_DISCORD_WEBHOOK_URL;
        public Duration cooldown = DEFAULT_DISCORD_COOLDOWN;
        public Duration requestTimeout = DEFAULT_DISCORD_REQUEST_TIMEOUT;
        public String username = DEFAULT_DISCORD_USERNAME;

        static final BuilderCodec<Discord> CODEC = BuilderCodec
            .builder(Discord.class, Discord::new)
            .append(new KeyedCodec<>("WebhookUrl", Codec.STRING), (c, v, ei) -> {
                if (v != null) {
                    c.webhookUrl = v;
                }
            }, (c, ei) -> c.webhookUrl).add()
            .append(new KeyedCodec<>("Cooldown", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.cooldown = v;
                }
            }, (c, ei) -> c.cooldown).add()
            .append(new KeyedCodec<>("RequestTimeout", Codec.DURATION), (c, v, ei) -> {
                if (v != null) {
                    c.requestTimeout = v;
                }
            }, (c, ei) -> c.requestTimeout).add()
            .append(new KeyedCodec<>("Username", Codec.STRING), (c, v, ei) -> {
                if (v != null) {
                    c.username = v;
                }
            }, (c, ei) -> c.username).add()
            .build();
    }

    private static final class Web {
        public boolean enabled = DEFAULT_WEB_ENABLED;

        static final BuilderCodec<Web> CODEC = BuilderCodec
            .builder(Web.class, Web::new)
            .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN), (c, v, ei) -> {
                if (v != null) {
                    c.enabled = v;
                }
            }, (c, ei) -> c.enabled).add()
            .build();
    }
}
