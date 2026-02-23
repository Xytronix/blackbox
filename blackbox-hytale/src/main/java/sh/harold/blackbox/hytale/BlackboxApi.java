package sh.harold.blackbox.hytale;

import sh.harold.blackbox.core.capture.BundleExtrasProvider;

/**
 * Public API for third-party plugins to integrate with Blackbox.
 * Example:
 * BlackboxApi.registerExtras((report, event) -> List.of(
 *     new BundleAttachment("extras/my-plugin.txt", myData.getBytes())
 * ));
 */
public final class BlackboxApi {
    private static volatile BlackboxRuntime runtime;

    private BlackboxApi() {
    }

    static void init(BlackboxRuntime runtime) {
        BlackboxApi.runtime = runtime;
    }

    static void shutdown() {
        BlackboxApi.runtime = null;
    }

    public static void registerExtras(BundleExtrasProvider provider) {
        BlackboxRuntime rt = runtime;
        if (rt == null) {
            throw new IllegalStateException("Blackbox is not initialized.");
        }
        if (!rt.config().capturePolicy().allowPluginExtras()) {
            throw new IllegalStateException("Plugin extras are disabled (Capture.AllowPluginExtras=false).");
        }
        rt.extrasRegistry().register(provider);
    }
}
