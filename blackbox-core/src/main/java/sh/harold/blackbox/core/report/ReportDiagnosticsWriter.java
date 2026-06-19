package sh.harold.blackbox.core.report;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sh.harold.blackbox.core.incident.DiagnosticSection;
import sh.harold.blackbox.core.json.JsonWriter;

final class ReportDiagnosticsWriter {

    private ReportDiagnosticsWriter() {
    }

    static void writePlugins(JsonWriter json, Map<String, String> plugins) throws IOException {
        writeModRows(json, "plugins", plugins);
    }

    static void writeAssetPacks(JsonWriter json, Map<String, String> assetPacks) throws IOException {
        writeModRows(json, "assetPacks", assetPacks);
    }

    private static void writeModRows(JsonWriter json, String key, Map<String, String> entries) throws IOException {
        if (entries == null || entries.isEmpty()) {
            json.name(key).nullValue();
            return;
        }
        json.name(key).beginArray();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String id = e.getKey().replaceFirst(" #\\d+$", "");
            int colon = id.indexOf(':');
            String author = colon > 0 ? id.substring(0, colon) : "";
            String name = colon > 0 ? id.substring(colon + 1) : id;
            String[] parts = e.getValue().split(" @ ", 3);
            json.beginArray();
            json.value(author);
            json.value(name);
            json.value(parts[0]);
            json.value(author.equalsIgnoreCase("hytale"));
            json.value(parts.length > 1 ? parts[1] : null);
            json.value(parts.length > 2 ? parts[2] : null);
            json.endArray();
        }
        json.endArray();
    }

    static void writeMixins(JsonWriter json, Map<String, String> mixins) throws IOException {
        boolean hasContent = mixins != null && mixins.keySet().stream().anyMatch(k ->
            k.startsWith("Conflict: ")
                || (!"Bootstrapper".equals(k) && !"Conflicts".equals(k)));
        if (!hasContent) {
            json.name("mixins").nullValue();
            return;
        }
        json.name("mixins").beginObject();
        json.name("bootstrapper").value(mixins.get("Bootstrapper"));
        json.name("configs").beginArray();
        for (Map.Entry<String, String> e : mixins.entrySet()) {
            if ("Bootstrapper".equals(e.getKey()) || "Conflicts".equals(e.getKey())
                || e.getKey().startsWith("Conflict: ")) {
                continue;
            }
            json.beginArray();
            json.value(e.getKey());
            json.beginArray();
            for (String cls : e.getValue().split(", ")) {
                if (!cls.isBlank()) {
                    json.value(cls);
                }
            }
            json.endArray();
            json.endArray();
        }
        json.endArray();
        boolean resolvable = "none".equals(mixins.get("Conflicts"));
        json.name("conflicts");
        boolean any = mixins.keySet().stream().anyMatch(k -> k.startsWith("Conflict: "));
        if (!any && !resolvable) {
            json.nullValue();
        } else {
            json.beginArray();
            for (Map.Entry<String, String> e : mixins.entrySet()) {
                if (!e.getKey().startsWith("Conflict: ")) {
                    continue;
                }
                json.beginArray();
                json.value(e.getKey().substring("Conflict: ".length()));
                json.beginArray();
                for (String participant : e.getValue().split("; ")) {
                    if (participant.isBlank()) {
                        continue;
                    }
                    int arrow = participant.indexOf(" → ");
                    json.beginArray();
                    if (arrow < 0) {
                        json.value(participant.trim());
                        json.nullValue();
                    } else {
                        json.value(participant.substring(0, arrow).trim());
                        json.value(participant.substring(arrow + " → ".length()).trim());
                    }
                    json.endArray();
                }
                json.endArray();
                json.endArray();
            }
            json.endArray();
        }
        json.endObject();
    }

    static void writeLog(JsonWriter json, List<ReportHtml.LogLine> entries) throws IOException {
        if (entries.isEmpty()) {
            json.name("log").nullValue();
            return;
        }
        json.name("log").beginArray();
        for (ReportHtml.LogLine entry : entries) {
            json.beginArray();
            json.value(entry.level());
            json.value(entry.source());
            json.value(entry.message());
            json.endArray();
        }
        json.endArray();
    }

    static void writeConfigs(JsonWriter json, List<DiagnosticSection> configs) throws IOException {
        json.name("configs").beginArray();
        for (DiagnosticSection section : configs) {
            json.beginObject();
            json.name("title").value(section.title());
            json.name("content").value(section.preformatted());
            json.endObject();
        }
        json.endArray();
    }

    static void writeGeneric(JsonWriter json, List<DiagnosticSection> generic, boolean hasPlugins)
        throws IOException {
        json.name("diag").beginArray();
        for (DiagnosticSection section : generic) {
            Map<String, String> entries = section.entries();
            if ("Environment".equals(section.title())) {
                Map<String, String> trimmed = new LinkedHashMap<>(entries);
                trimmed.remove("Server name");
                trimmed.remove("Hytale version");
                trimmed.remove("Container runtime");
                trimmed.values().removeIf(ReportSystemWriter::isLoaderEntry);
                if (hasPlugins) {
                    trimmed.remove("Mods");
                    trimmed.remove("Mods (count)");
                }
                entries = trimmed;
            }
            String pre = section.preformatted();
            boolean hasPre = pre != null && !pre.isBlank();
            if (entries.isEmpty() && !hasPre) {
                continue;
            }
            json.beginObject();
            json.name("title").value(section.title());
            json.name("entries").beginArray();
            for (Map.Entry<String, String> e : entries.entrySet()) {
                json.beginArray();
                json.value(e.getKey());
                json.value(e.getValue());
                json.endArray();
            }
            json.endArray();
            if (hasPre) {
                json.name("pre").value(pre);
            }
            json.endObject();
        }
        json.endArray();
    }
}
