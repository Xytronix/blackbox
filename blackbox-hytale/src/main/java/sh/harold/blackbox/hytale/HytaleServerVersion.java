package sh.harold.blackbox.hytale;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import com.hypixel.hytale.server.core.HytaleServer;

final class HytaleServerVersion {

    private HytaleServerVersion() {
    }

    static String get() {
        try {
            Package pkg = HytaleServer.class.getPackage();
            String version = pkg == null ? null : pkg.getImplementationVersion();
            if (version != null) {
                return version;
            }
            Path jar = serverJar();
            return jar == null ? null : manifestVersion(jar);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Path serverJar() {
        try {
            java.security.ProtectionDomain domain = HytaleServer.class.getProtectionDomain();
            java.security.CodeSource source = domain == null ? null : domain.getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path jar = Path.of(source.getLocation().toURI());
                if (Files.isRegularFile(jar)) {
                    return jar;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String resource = HytaleServer.class.getName().replace('.', '/') + ".class";
            ClassLoader loader = HytaleServer.class.getClassLoader();
            java.net.URL url = loader != null ? loader.getResource(resource) : ClassLoader.getSystemResource(resource);
            if (url != null) {
                String spec = url.toString();
                int bang = spec.indexOf("!/");
                if (spec.startsWith("jar:") && bang >= 0) {
                    Path jar = Path.of(URI.create(spec.substring(4, bang)));
                    if (Files.isRegularFile(jar)) {
                        return jar;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String manifestVersion(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return null;
            }
            Attributes attrs = manifest.getMainAttributes();
            if (!"com.hypixel.hytale".equals(attrs.getValue("Implementation-Vendor-Id"))) {
                return null;
            }
            return attrs.getValue("Implementation-Version");
        } catch (Throwable t) {
            return null;
        }
    }
}
