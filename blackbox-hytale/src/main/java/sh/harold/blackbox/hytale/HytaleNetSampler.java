package sh.harold.blackbox.hytale;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import jdk.jfr.consumer.RecordingStream;
import sh.harold.blackbox.core.trigger.net.NetSaturationDetector;

final class HytaleNetSampler implements AutoCloseable {
    private final Clock clock;
    private final System.Logger logger;
    private final Map<String, double[]> netRatesByInterface = new ConcurrentHashMap<>();
    private volatile RecordingStream netStream;

    HytaleNetSampler(Clock clock, System.Logger logger) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    synchronized void sync(boolean armed) {
        if (!armed && netStream != null) {
            try {
                netStream.close();
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Network sampler shutdown failed.", e);
            }
            netStream = null;
            return;
        }
        if (!armed || netStream != null) {
            return;
        }
        try {
            RecordingStream stream = new RecordingStream();
            stream.enable("jdk.NetworkUtilization").withPeriod(Duration.ofSeconds(5));
            stream.onEvent("jdk.NetworkUtilization", event -> {
                try {
                    String iface = event.getString("networkInterface");
                    if (iface == null || iface.isBlank()) {
                        return;
                    }
                    netRatesByInterface.put(iface, new double[] {
                        event.getLong("readRate") / 1e6,
                        event.getLong("writeRate") / 1e6,
                        clock.millis()
                    });
                } catch (Exception ignored) {
                }
            });
            stream.startAsync();
            this.netStream = stream;
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING,
                "Failed to start the network sampler; net-saturation captures disabled.", e);
        }
    }

    NetSaturationDetector.Rates latestRates() {
        long cutoff = clock.millis() - 15_000L;
        double in = -1;
        double out = -1;
        for (double[] rates : netRatesByInterface.values()) {
            if (rates[2] < cutoff) {
                continue;
            }
            in = Math.max(in, rates[0]);
            out = Math.max(out, rates[1]);
        }
        return in < 0 ? null : new NetSaturationDetector.Rates(in, out);
    }

    @Override
    public void close() {
        try {
            if (netStream != null) {
                netStream.close();
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Network sampler shutdown failed.", e);
        }
    }
}
