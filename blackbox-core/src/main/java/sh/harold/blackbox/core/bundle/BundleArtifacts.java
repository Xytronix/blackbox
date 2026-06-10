package sh.harold.blackbox.core.bundle;

import java.util.Set;

/**
 * Artifact keys for per-artifact bundle inclusion control.
 * incident.json is always included and has no key.
 */
public final class BundleArtifacts {
    public static final String JFR = "jfr";
    public static final String REPORT = "report";
    public static final String ENV = "env";
    public static final String THREADS = "threads";
    public static final String SERVER_LOG = "serverLog";
    public static final String PLUGINS = "plugins";
    public static final String WORLDS = "worlds";
    public static final String HEARTBEATS = "heartbeats";
    public static final String SERVER = "server";

    public static final Set<String> ALL = Set.of(
        JFR, REPORT, ENV, THREADS, SERVER_LOG, PLUGINS, WORLDS, HEARTBEATS, SERVER);

    private BundleArtifacts() {
    }

    /** Maps a zip entry path to its artifact key, or null if it is always included. */
    public static String keyForPath(String zipPath) {
        if (zipPath.equals("recording.jfr")) {
            return JFR;
        }
        if (zipPath.equals("report.html")) {
            return REPORT;
        }
        if (zipPath.startsWith("env/")) {
            return ENV;
        }
        if (zipPath.equals("extras/threads.txt")) {
            return THREADS;
        }
        if (zipPath.equals("extras/server-log.txt")) {
            return SERVER_LOG;
        }
        if (zipPath.equals("extras/plugins.txt")) {
            return PLUGINS;
        }
        if (zipPath.equals("extras/worlds.txt")) {
            return WORLDS;
        }
        if (zipPath.equals("extras/heartbeats.txt")) {
            return HEARTBEATS;
        }
        if (zipPath.equals("extras/server.txt")) {
            return SERVER;
        }
        return null;
    }
}
