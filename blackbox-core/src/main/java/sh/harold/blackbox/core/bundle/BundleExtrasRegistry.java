package sh.harold.blackbox.core.bundle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import sh.harold.blackbox.core.capture.BundleExtrasProvider;
import sh.harold.blackbox.core.incident.IncidentReport;
import sh.harold.blackbox.core.trigger.TriggerEvent;

public final class BundleExtrasRegistry implements BundleExtrasProvider {
    private final List<BundleExtrasProvider> providers = new CopyOnWriteArrayList<>();
    private final System.Logger logger;

    public BundleExtrasRegistry(System.Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void register(BundleExtrasProvider provider) {
        Objects.requireNonNull(provider, "provider");
        providers.add(provider);
    }

    @Override
    public List<BundleAttachment> extras(IncidentReport report, TriggerEvent triggerEvent) {
        List<BundleAttachment> all = new ArrayList<>();
        for (BundleExtrasProvider provider : providers) {
            try {
                List<BundleAttachment> providerExtras = provider.extras(report, triggerEvent);
                if (providerExtras != null) {
                    all.addAll(providerExtras);
                }
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING,
                    "BundleExtrasProvider " + provider.getClass().getName() + " failed.", e);
            }
        }
        return all;
    }

    public int size() {
        return providers.size();
    }
}
