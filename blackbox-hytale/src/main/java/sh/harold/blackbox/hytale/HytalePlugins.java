package sh.harold.blackbox.hytale;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;

import sh.harold.blackbox.core.incident.DiagnosticSection;

final class HytalePlugins {
    private static final System.Logger LOGGER = System.getLogger(HytalePlugins.class.getName());

    private HytalePlugins() {
    }

    static List<DiagnosticSection> appendTo(List<DiagnosticSection> base) {
        Map<String, String> entries = entries();
        if (entries.isEmpty()) {
            return base;
        }
        List<DiagnosticSection> out = new ArrayList<>(base);
        out.add(new DiagnosticSection("Plugins", entries));
        return out;
    }

    private static Map<String, String> entries() {
        Map<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String serverVersion = HytaleServerVersion.get();
        try {
            List<PluginBase> plugins = PluginManager.get().getPlugins();
            for (PluginBase plugin : plugins) {
                try {
                    String key = String.valueOf(plugin.getIdentifier());
                    String version = versionInfo(plugin, serverVersion);
                    String unique = key;
                    int suffix = 2;
                    while (sorted.containsKey(unique)) {
                        unique = key + " #" + suffix++;
                    }
                    sorted.put(unique, version);
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.DEBUG, "Plugin enumeration failed.", t);
        }
        return new LinkedHashMap<>(sorted);
    }

    private static String versionInfo(PluginBase plugin, String serverVersion) {
        String version;
        try {
            version = String.valueOf(plugin.getManifest().getVersion());
        } catch (Throwable t) {
            return "?";
        }
        try {
            var range = plugin.getManifest().getServerVersion();
            if (range == null) {
                return version;
            }
            var check = PluginManifest.checkServerVersionCompatibility(range, serverVersion);
            return version + " @ " + range + " @ " + check.name();
        } catch (Throwable t) {
            return version;
        }
    }

}
