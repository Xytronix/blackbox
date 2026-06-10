package sh.harold.blackbox.hytale;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sh.harold.blackbox.core.incident.DiagnosticSection;

/**
 * Builds a "Mixins" diagnostics section: which mixin bootstrapper is active (Hyinit or Hyxin,
 * detected via the {@code mixin.service} system property) and every registered mixin config with
 * the mixin classes it applies. All access is reflective and best-effort — the Mixin library is
 * not on the compile classpath, and a server without a bootstrapper simply gets no section.
 */
final class HytaleMixins {
    private static final System.Logger LOGGER = System.getLogger(HytaleMixins.class.getName());

    private static final String MIXINS_CLASS = "org.spongepowered.asm.mixin.Mixins";
    private static final String IMIXIN_CONFIG_CLASS = "org.spongepowered.asm.mixin.extensibility.IMixinConfig";
    private static final String HYINIT_VERSION_CLASS = "cc.irori.hyinit.Main";
    private static final String HYXIN_VERSION_CLASS = "com.build_9.hyxin.Constants";

    private static final Pattern MIXIN_ARRAY =
        Pattern.compile("\"(?:mixins|client|server)\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private HytaleMixins() {
    }

    /** Returns {@code base} with a "Mixins" section appended when a bootstrapper or configs exist. */
    static List<DiagnosticSection> appendTo(List<DiagnosticSection> base) {
        try {
            String bootstrapper = detectBootstrapper();
            Map<String, RegisteredConfig> registered = registeredConfigs();
            Map<String, String> configs = configEntries(registered.keySet());
            if (bootstrapper == null && configs.isEmpty()) {
                return base;
            }

            Map<String, String> entries = new LinkedHashMap<>();
            if (bootstrapper != null) {
                entries.put("Bootstrapper", bootstrapper);
            }
            entries.putAll(configs);
            entries.putAll(conflictEntries(registered));

            List<DiagnosticSection> out = new ArrayList<>(base);
            out.add(new DiagnosticSection("Mixins", entries));
            return out;
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.DEBUG, "Mixin enumeration failed.", t);
            return base;
        }
    }

    /**
     * "Conflict: &lt;target class&gt;" → "config → MixinName; config → MixinName" for every class
     * targeted by more than one config (mixin names resolved from the live config via
     * {@code getMixinsFor(target)}, config name alone when that fails), or a single
     * "Conflicts" → "none" entry when targets were resolvable and nothing overlaps.
     * Empty when no config exposed its targets (conflicts unknown).
     */
    private static Map<String, String> conflictEntries(Map<String, RegisteredConfig> registered) {
        boolean resolvable = registered.values().stream().anyMatch(c -> !c.targets().isEmpty());
        if (!resolvable) {
            return Map.of();
        }
        Map<String, List<String>> byTarget = new TreeMap<>();
        for (Map.Entry<String, RegisteredConfig> config : registered.entrySet()) {
            for (String target : config.getValue().targets()) {
                byTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(config.getKey());
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : byTarget.entrySet()) {
            if (entry.getValue().size() <= 1) {
                continue;
            }
            List<String> participants = new ArrayList<>(entry.getValue().size());
            for (String configName : entry.getValue()) {
                String mixins = mixinNamesFor(registered.get(configName).config(), entry.getKey());
                participants.add(mixins == null ? configName : configName + " → " + mixins);
            }
            out.put("Conflict: " + entry.getKey(), String.join("; ", participants));
        }
        return out.isEmpty() ? Map.of("Conflicts", "none") : out;
    }

    /**
     * Names of the mixins a config applies to one target class, via the live config's public
     * {@code getMixinsFor(String)}; null when not resolvable.
     */
    private static String mixinNamesFor(Object mixinConfig, String target) {
        try {
            Method getMixinsFor = mixinConfig.getClass().getMethod("getMixinsFor", String.class);
            getMixinsFor.setAccessible(true);
            Object mixins = getMixinsFor.invoke(mixinConfig, target);
            if (!(mixins instanceof Iterable<?> iterable)) {
                return null;
            }
            List<String> names = new ArrayList<>();
            for (Object mixin : iterable) {
                if (mixin == null) {
                    continue;
                }
                Method getName = mixin.getClass().getMethod("getName");
                getName.setAccessible(true);
                Object name = getName.invoke(mixin);
                if (name != null && !String.valueOf(name).isBlank()) {
                    names.add(String.valueOf(name));
                }
            }
            return names.isEmpty() ? null : String.join(" + ", names);
        } catch (Throwable t) {
            return null;
        }
    }

    /** "Hyinit"/"Hyxin" (with version when resolvable), a raw service class name, or null. */
    private static String detectBootstrapper() {
        String service;
        try {
            service = System.getProperty("mixin.service");
        } catch (Throwable t) {
            return null;
        }
        if (service == null || service.isBlank()) {
            return null;
        }
        String lower = service.toLowerCase(Locale.ROOT);
        if (lower.contains("hyinit")) {
            return withVersion("Hyinit", HYINIT_VERSION_CLASS);
        }
        if (lower.contains("hyxin")) {
            return withVersion("Hyxin", HYXIN_VERSION_CLASS);
        }
        return service;
    }

    private static String withVersion(String name, String versionClass) {
        String version = HytaleEnvironment.loaderVersion(versionClass);
        return version == null ? name : name + " " + version;
    }

    /** config name → comma-joined mixin class names, alphabetical by config name. */
    private static Map<String, String> configEntries(Set<String> names) {
        Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : names) {
            String body = readConfigResource(name);
            if (body == null) {
                out.put(name, "(config not readable)");
                continue;
            }
            Set<String> mixins = parseMixinClasses(body);
            out.put(name, mixins.isEmpty() ? "(no mixins listed)" : String.join(", ", mixins));
        }
        return new LinkedHashMap<>(out);
    }

    /** A registered config: the live {@code IMixinConfig} object plus its resolved target classes. */
    private record RegisteredConfig(Object config, Set<String> targets) {}

    /**
     * Every mixin config registered with the live Mixin environment, reflectively:
     * config name → live config handle + the target classes it patches (empty set when
     * targets are not resolvable).
     */
    private static Map<String, RegisteredConfig> registeredConfigs() {
        Map<String, RegisteredConfig> out = new LinkedHashMap<>();
        try {
            Class<?> mixins = Class.forName(MIXINS_CLASS, false, HytaleMixins.class.getClassLoader());
            Method getConfigs = mixins.getMethod("getConfigs");
            Object configs = getConfigs.invoke(null);
            if (configs instanceof Iterable<?> iterable) {
                for (Object config : iterable) {
                    if (config == null) {
                        continue;
                    }
                    Object name = config.getClass().getMethod("getName").invoke(config);
                    Object mixinConfig = config.getClass().getMethod("getConfig").invoke(config);
                    if (name != null && !String.valueOf(name).isBlank() && mixinConfig != null) {
                        out.put(String.valueOf(name), new RegisteredConfig(mixinConfig, targetsOf(mixinConfig)));
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.DEBUG, "Mixin config enumeration failed.", t);
        }
        return out;
    }

    /** Target classes of one live {@code IMixinConfig}, via the public {@code getTargets()}. */
    private static Set<String> targetsOf(Object mixinConfig) {
        try {
            Class<?> iface = Class.forName(IMIXIN_CONFIG_CLASS, false, mixinConfig.getClass().getClassLoader());
            Object targets = iface.getMethod("getTargets").invoke(mixinConfig);
            if (targets instanceof Iterable<?> iterable) {
                Set<String> out = new LinkedHashSet<>();
                for (Object target : iterable) {
                    if (target != null && !String.valueOf(target).isBlank()) {
                        out.add(String.valueOf(target));
                    }
                }
                return out;
            }
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.DEBUG, "Mixin target enumeration failed.", t);
        }
        return Set.of();
    }

    private static String readConfigResource(String name) {
        ClassLoader[] loaders = {
            Thread.currentThread().getContextClassLoader(),
            HytaleMixins.class.getClassLoader(),
            ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            try (InputStream in = loader.getResourceAsStream(name)) {
                if (in != null) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Extracts the "mixins"/"client"/"server" string arrays, concatenated and de-duplicated. */
    private static Set<String> parseMixinClasses(String json) {
        Set<String> out = new LinkedHashSet<>();
        Matcher arrays = MIXIN_ARRAY.matcher(json);
        while (arrays.find()) {
            Matcher strings = STRING_LITERAL.matcher(arrays.group(1));
            while (strings.find()) {
                String value = strings.group(1).trim();
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
        }
        return out;
    }
}
