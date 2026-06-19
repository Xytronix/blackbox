package sh.harold.blackbox.hytale;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;

final class HytaleEnvironment {
    private static final String[] HYINIT_CLASSES = {
        "cc.irori.hyinit.shared.SourceMetaStore",
        "cc.irori.hyinit.HyinitGlobalProperties",
        "cc.irori.hyinit.Main"
    };
    private static final String[] HYXIN_CLASSES = {
        "com.build_9.hyxin.Constants",
        "com.build_9.hyxin.HyxinTransformer"
    };

    private HytaleEnvironment() {
    }

    static Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        putIfPresent(out, "Hyinit", detectLoader(HYINIT_CLASSES));
        putIfPresent(out, "Hyxin", detectLoader(HYXIN_CLASSES));
        putIfPresent(out, "Server name", serverName());
        putIfPresent(out, "Hytale version", HytaleServerVersion.get());
        putIfPresent(out, "Container runtime", containerRuntime());
        try {
            List<PluginBase> plugins = PluginManager.get().getPlugins();
            if (!plugins.isEmpty()) {
                out.put("Mods (count)", String.valueOf(plugins.size()));
                out.put("Mods", plugins.stream()
                    .map(HytaleEnvironment::describe)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.joining(", ")));
            }
        } catch (Throwable t) {
            out.put("Mods", "<unavailable: " + t.getClass().getSimpleName() + ">");
        }
        return out;
    }

    private static String containerRuntime() {
        try {
            if (System.getenv("KUBERNETES_SERVICE_HOST") != null
                || java.nio.file.Files.exists(java.nio.file.Path.of("/var/run/secrets/kubernetes.io"))) {
                return "Kubernetes";
            }
            String cgroup = procOneCgroup();
            if (java.nio.file.Files.exists(java.nio.file.Path.of("/.dockerenv"))
                || cgroup.contains("docker")) {
                return "Docker";
            }
            if (java.nio.file.Files.exists(java.nio.file.Path.of("/run/.containerenv"))) {
                return "Podman";
            }
            if (cgroup.contains("kubepods") || cgroup.contains("containerd") || cgroup.contains("libpod")) {
                return "Container";
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String procOneCgroup() {
        try {
            java.nio.file.Path path = java.nio.file.Path.of("/proc/1/cgroup");
            return java.nio.file.Files.isReadable(path)
                ? java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8)
                : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String serverName() {
        try {
            return com.hypixel.hytale.server.core.HytaleServer.get().getServerName();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void putIfPresent(Map<String, String> out, String key, String value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private static String detectLoader(String[] classes) {
        ClassLoader cl = HytaleEnvironment.class.getClassLoader();
        for (String name : classes) {
            try {
                Class.forName(name, false, cl);
                String version = loaderVersion(name);
                return version == null ? "installed" : "installed (" + version + ")";
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static final java.util.regex.Pattern MANIFEST_VERSION =
        java.util.regex.Pattern.compile("\"Version\"\\s*:\\s*\"([^\"]+)\"");

    static String loaderVersion(String className) {
        try {
            Class<?> c = Class.forName(className, false, HytaleEnvironment.class.getClassLoader());
            String version = c.getPackage() == null ? null : c.getPackage().getImplementationVersion();
            if (version != null) {
                return version;
            }
            version = codeSourceVersion(c);
            if (version != null) {
                return version;
            }
            version = earlyPluginVersion(className);
            if (version != null) {
                return version;
            }
            version = resourceJarVersion(c, className);
            if (version != null) {
                return version;
            }
            version = javaAgentVersion(className);
            if (version != null) {
                return version;
            }
            return launchCommandVersion();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String javaAgentVersion(String className) {
        try {
            String hint = className.startsWith("cc.irori.") ? "hyinit"
                : className.startsWith("com.build_9.") ? "hyxin" : null;
            if (hint == null) {
                return null;
            }
            List<java.nio.file.Path> jars = new java.util.ArrayList<>();
            for (String arg : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (arg == null || !arg.startsWith("-javaagent:")) {
                    continue;
                }
                String spec = arg.substring("-javaagent:".length());
                int eq = spec.indexOf('=');
                if (eq >= 0) {
                    spec = spec.substring(0, eq);
                }
                if (spec.isBlank()) {
                    continue;
                }
                java.nio.file.Path jar = java.nio.file.Path.of(spec);
                if (!jar.isAbsolute()) {
                    jar = java.nio.file.Path.of(System.getProperty("user.dir", "."), spec);
                }
                if (java.nio.file.Files.isRegularFile(jar)) {
                    jars.add(jar);
                }
            }
            for (java.nio.file.Path jar : jars) {
                if (jar.getFileName().toString().toLowerCase(Locale.ROOT).contains(hint)) {
                    String version = manifestJsonVersion(jar);
                    if (version != null) {
                        return version;
                    }
                }
            }
            for (java.nio.file.Path jar : jars) {
                if (hint.equalsIgnoreCase(manifestJsonName(jar))) {
                    String version = manifestJsonVersion(jar);
                    if (version != null) {
                        return version;
                    }
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String codeSourceVersion(Class<?> c) {
        try {
            java.security.CodeSource source = c.getProtectionDomain() == null
                ? null : c.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            java.nio.file.Path jar = java.nio.file.Path.of(source.getLocation().toURI());
            return java.nio.file.Files.isRegularFile(jar) ? manifestJsonVersion(jar) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String resourceJarVersion(Class<?> c, String className) {
        try {
            String resource = className.replace('.', '/') + ".class";
            ClassLoader loader = c.getClassLoader();
            java.net.URL url = loader != null
                ? loader.getResource(resource)
                : ClassLoader.getSystemResource(resource);
            if (url == null) {
                return null;
            }
            String spec = url.toString();
            int bang = spec.indexOf("!/");
            if (!spec.startsWith("jar:") || bang < 0) {
                return null;
            }
            java.nio.file.Path jar = java.nio.file.Path.of(java.net.URI.create(spec.substring(4, bang)));
            return java.nio.file.Files.isRegularFile(jar) ? manifestJsonVersion(jar) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final java.util.regex.Pattern JAR_FILENAME_VERSION =
        java.util.regex.Pattern.compile(".*-(\\d+\\.\\d+[^/]*)\\.jar$");

    private static String launchCommandVersion() {
        try {
            String command = System.getProperty("sun.java.command");
            if (command == null || command.isBlank()) {
                return null;
            }
            String first = command.trim().split("\\s+")[0];
            if (!first.endsWith(".jar")) {
                return null;
            }
            java.nio.file.Path jar = java.nio.file.Path.of(first);
            if (!jar.isAbsolute()) {
                jar = java.nio.file.Path.of(System.getProperty("user.dir", "."), first);
            }
            if (java.nio.file.Files.isRegularFile(jar)) {
                String version = manifestJsonVersion(jar);
                if (version != null) {
                    return version;
                }
            }
            java.util.regex.Matcher m = JAR_FILENAME_VERSION.matcher(first);
            return m.matches() ? m.group(1) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final java.util.regex.Pattern MANIFEST_NAME =
        java.util.regex.Pattern.compile("\"Name\"\\s*:\\s*\"([^\"]+)\"");

    private static String manifestJsonVersion(java.nio.file.Path jar) {
        return manifestJsonField(jar, MANIFEST_VERSION);
    }

    private static String manifestJsonName(java.nio.file.Path jar) {
        return manifestJsonField(jar, MANIFEST_NAME);
    }

    private static String manifestJsonField(java.nio.file.Path jar, java.util.regex.Pattern pattern) {
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar.toFile())) {
            java.util.zip.ZipEntry entry = jarFile.getEntry("manifest.json");
            if (entry == null) {
                return null;
            }
            String body = new String(jarFile.getInputStream(entry).readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = pattern.matcher(body);
            return m.find() ? m.group(1) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String earlyPluginVersion(String className) {
        java.nio.file.Path dir = java.nio.file.Paths.get("earlyplugins");
        if (!java.nio.file.Files.isDirectory(dir)) {
            return null;
        }
        String classEntry = className.replace('.', '/') + ".class";
        try (java.util.stream.Stream<java.nio.file.Path> jars = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path jar : jars
                    .filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar.toFile())) {
                    if (jarFile.getEntry(classEntry) != null) {
                        return manifestJsonVersion(jar);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    private static String describe(PluginBase plugin) {
        return String.valueOf(plugin.getIdentifier()) + " " + version(plugin);
    }

    private static String version(PluginBase plugin) {
        try {
            return String.valueOf(plugin.getManifest().getVersion());
        } catch (Throwable t) {
            return "?";
        }
    }
}
