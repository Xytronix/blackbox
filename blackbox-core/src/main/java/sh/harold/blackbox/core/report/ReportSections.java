package sh.harold.blackbox.core.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sh.harold.blackbox.core.incident.DiagnosticSection;

record ReportSections(Map<String, String> mixins, Map<String, String> plugins,
                      Map<String, String> assetPacks, String serverLog,
                      Map<String, String> environment, Map<String, String> tickSystems,
                      Map<String, String> modCpu, Map<String, String> heapHistogram,
                      Map<String, String> memPools, Map<String, String> entities,
                      List<DiagnosticSection> configs, List<DiagnosticSection> generic) {

    static ReportSections route(List<DiagnosticSection> diagnostics) {
        Map<String, String> mixins = null;
        Map<String, String> plugins = null;
        Map<String, String> assetPacks = null;
        String serverLog = null;
        Map<String, String> environment = null;
        Map<String, String> tickSystems = null;
        Map<String, String> modCpu = null;
        Map<String, String> heapHistogram = null;
        Map<String, String> memPools = null;
        Map<String, String> entities = null;
        List<DiagnosticSection> configs = new ArrayList<>();
        List<DiagnosticSection> generic = new ArrayList<>();
        for (DiagnosticSection section : diagnostics == null ? List.<DiagnosticSection>of() : diagnostics) {
            if (section.preformatted() != null) {
                if ("Server log".equals(section.title())) {
                    serverLog = section.preformatted();
                } else if ("Stalled thread".equals(section.title()) || "Deadlocked thread".equals(section.title())) {
                    generic.add(section);
                } else {
                    configs.add(section);
                }
            } else if ("Mixins".equals(section.title())) {
                mixins = section.entries();
            } else if ("Plugins".equals(section.title())) {
                plugins = section.entries();
            } else if ("Asset packs".equals(section.title())) {
                assetPacks = section.entries();
            } else if ("Tick systems".equals(section.title())) {
                tickSystems = section.entries();
            } else if ("Mod hot-path contribution (JFR)".equals(section.title())) {
                modCpu = section.entries();
            } else if ("Heap histogram".equals(section.title())) {
                heapHistogram = section.entries();
            } else if ("Memory pools".equals(section.title())) {
                memPools = section.entries();
            } else if ("Entities".equals(section.title())) {
                entities = section.entries();
            } else if ("Environment".equals(section.title())) {
                environment = section.entries();
                generic.add(section);
            } else {
                generic.add(section);
            }
        }
        return new ReportSections(mixins, plugins, assetPacks, serverLog, environment, tickSystems, modCpu,
            heapHistogram, memPools, entities, configs, generic);
    }
}
