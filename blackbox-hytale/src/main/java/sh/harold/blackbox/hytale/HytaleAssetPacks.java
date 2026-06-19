package sh.harold.blackbox.hytale;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.server.core.asset.AssetModule;

import sh.harold.blackbox.core.incident.DiagnosticSection;

final class HytaleAssetPacks {
    private static final System.Logger LOGGER = System.getLogger(HytaleAssetPacks.class.getName());

    private HytaleAssetPacks() {
    }

    static List<DiagnosticSection> appendTo(List<DiagnosticSection> base) {
        Map<String, String> entries = entries();
        if (entries.isEmpty()) {
            return base;
        }
        List<DiagnosticSection> out = new ArrayList<>(base);
        out.add(new DiagnosticSection("Asset packs", entries));
        return out;
    }

    private static Map<String, String> entries() {
        Map<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String serverVersion = HytaleServerVersion.get();
        try {
            List<AssetPack> packs = AssetModule.get().getAssetPacks();
            for (AssetPack pack : packs) {
                try {
                    if (pack == null || pack.isCoreMod() || pack.getSource() == AssetPack.PackSource.CLASSPATH) {
                        continue;
                    }
                    String key = pack.getName();
                    if (key == null || key.isBlank()) {
                        continue;
                    }
                    String unique = key;
                    int suffix = 2;
                    while (sorted.containsKey(unique)) {
                        unique = key + " #" + suffix++;
                    }
                    sorted.put(unique, versionInfo(pack, serverVersion));
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.DEBUG, "Asset pack enumeration failed.", t);
        }
        return new LinkedHashMap<>(sorted);
    }

    private static String versionInfo(AssetPack pack, String serverVersion) {
        PluginManifest manifest;
        try {
            manifest = pack.getManifest();
        } catch (Throwable t) {
            return "?";
        }
        if (manifest == null) {
            return "?";
        }
        String version = String.valueOf(manifest.getVersion());
        try {
            var range = manifest.getServerVersion();
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
