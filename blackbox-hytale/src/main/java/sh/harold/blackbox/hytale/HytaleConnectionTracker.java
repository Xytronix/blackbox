package sh.harold.blackbox.hytale;

import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerSetupConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerSetupDisconnectEvent;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import sh.harold.blackbox.core.jfr.BlackboxConnectionEvent;
import sh.harold.blackbox.core.jfr.BlackboxPluginEvent;
import sh.harold.blackbox.core.metrics.HealthGauges;

final class HytaleConnectionTracker {
    private final BlackboxPlugin plugin;
    private final System.Logger logger;
    private final PlayerNameMasker nameMasker;
    private final HealthGauges healthGauges;
    private final Map<UUID, Long> joinStartNanos = new ConcurrentHashMap<>();

    HytaleConnectionTracker(BlackboxPlugin plugin, System.Logger logger, PlayerNameMasker nameMasker,
                            HealthGauges healthGauges) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.nameMasker = Objects.requireNonNull(nameMasker, "nameMasker");
        this.healthGauges = Objects.requireNonNull(healthGauges, "healthGauges");
    }

    void register() {
        try {
            plugin.getEventRegistry().registerGlobal(PlayerSetupConnectEvent.class,
                e -> {
                    markJoinStart(e.getUuid());
                    nameMasker.remember(e.getUsername(), e.getUuid());
                    commitConnectionEvent(e.getUsername(), "connect", null);
                });
            plugin.getEventRegistry().registerGlobal(PlayerSetupDisconnectEvent.class,
                e -> {
                    joinStartNanos.remove(e.getUuid());
                    commitConnectionEvent(e.getUsername(), "setup disconnect",
                        String.valueOf(e.getDisconnectReason()));
                });
            plugin.getEventRegistry().registerGlobal(PlayerConnectEvent.class,
                e -> {
                    var ref = e.getPlayerRef();
                    String world = e.getWorld() == null ? null : e.getWorld().getName();
                    String dur = joinDuration(ref == null ? null : ref.getUuid());
                    String detail = world == null ? dur : (dur == null ? world : world + " · " + dur);
                    commitConnectionEvent(ref == null ? null : ref.getUsername(), "join", detail);
                });
            plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class,
                e -> {
                    String reason = disconnectReason(e);
                    healthGauges.incrementDisconnect(reason);
                    commitConnectionEvent(e.getPlayerRef() == null ? null : e.getPlayerRef().getUsername(),
                        "leave", reason);
                });
            plugin.getEventRegistry().registerGlobal(ShutdownEvent.class,
                e -> commitServerMarker("shutdown initiated"));
        } catch (Throwable t) {
            logger.log(System.Logger.Level.WARNING,
                "Failed to subscribe to player connection events; connection timeline disabled.", t);
        }
    }

    private void markJoinStart(UUID uuid) {
        if (uuid == null) {
            return;
        }
        if (joinStartNanos.size() > 512) {
            joinStartNanos.clear();
        }
        joinStartNanos.put(uuid, System.nanoTime());
    }

    private String joinDuration(UUID uuid) {
        Long start = uuid == null ? null : joinStartNanos.remove(uuid);
        if (start == null) {
            return null;
        }
        return String.format(Locale.ROOT, "joined in %.1fs", (System.nanoTime() - start) / 1e9);
    }

    private void commitServerMarker(String message) {
        try {
            BlackboxPluginEvent event = new BlackboxPluginEvent();
            event.category = "Server";
            event.message = message;
            event.commit();
        } catch (Throwable t) {
            logger.log(System.Logger.Level.DEBUG, "Failed to commit server marker.", t);
        }
    }

    private static String disconnectReason(PlayerDisconnectEvent event) {
        try {
            Object reason = event.getDisconnectReason();
            return reason == null ? null : reason.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private void commitConnectionEvent(String player, String phase, String detail) {
        try {
            BlackboxConnectionEvent event = new BlackboxConnectionEvent();
            event.player = nameMasker.maskName(player);
            event.phase = phase;
            event.detail = detail;
            event.commit();
        } catch (Throwable t) {
            logger.log(System.Logger.Level.DEBUG, "Failed to commit connection event.", t);
        }
    }
}
