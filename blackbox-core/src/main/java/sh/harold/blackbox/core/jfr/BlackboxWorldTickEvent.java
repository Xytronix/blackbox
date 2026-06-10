package sh.harold.blackbox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Periodic per-world tick sample committed by the platform adapter (every few seconds per world)
 * so the rolling recording carries TPS/MSPT/player history that plain JFR does not record.
 * Read back by {@code JfrTimeline} to build the tick-rate and player series in the report.
 */
@Name(BlackboxWorldTickEvent.NAME)
@Label("World Tick Sample")
@Category("Blackbox")
@StackTrace(false)
public final class BlackboxWorldTickEvent extends Event {
    public static final String NAME = "sh.harold.blackbox.WorldTick";

    @Label("World")
    public String world;

    @Label("TPS")
    public double tps;

    @Label("MSPT")
    public double mspt;

    @Label("Players")
    public int players;

    @Label("Entities")
    public int entities;

    @Label("Avg ping")
    public double avgPingMs;
}
