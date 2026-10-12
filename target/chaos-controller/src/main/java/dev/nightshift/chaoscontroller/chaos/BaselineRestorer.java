package dev.nightshift.chaoscontroller.chaos;

import dev.nightshift.chaoscontroller.ops.DeployRegistry;
import dev.nightshift.chaoscontroller.ops.DeployService;
import dev.nightshift.chaoscontroller.ops.FaultSwitchStore;
import dev.nightshift.chaoscontroller.ops.ReleaseManifest;
import dev.nightshift.chaoscontroller.ops.ServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Puts the whole target system back to its baseline, whatever was injected: no toxics, all
 * proxies enabled, no fault switches, baseline versions, default config and flags.
 */
@Component
public class BaselineRestorer {

    private static final Logger log = LoggerFactory.getLogger(BaselineRestorer.class);
    static final String RESET_ACTOR = "ops-baseline";

    private final ToxiproxyClient toxiproxy;
    private final FaultSwitchStore switches;
    private final ReleaseManifest manifest;
    private final DeployService deploys;
    private final DeployRegistry registry;
    private final ServiceClient services;
    private final ChaosActions actions;

    public BaselineRestorer(ToxiproxyClient toxiproxy, FaultSwitchStore switches, ReleaseManifest manifest,
                            DeployService deploys, DeployRegistry registry, ServiceClient services, ChaosActions actions) {
        this.toxiproxy = toxiproxy;
        this.switches = switches;
        this.manifest = manifest;
        this.deploys = deploys;
        this.registry = registry;
        this.services = services;
        this.actions = actions;
    }

    /** Returns a description of each corrective action taken (empty when already at baseline). */
    public List<String> restore() {
        List<String> done = new ArrayList<>();
        toxiproxy.reset();

        List<String> hadSwitches = new ArrayList<>();
        for (String service : manifest.services().keySet()) {
            if (!switches.forService(service).isEmpty()) {
                hadSwitches.add(service);
            }
        }
        switches.clearAll();
        for (String service : manifest.services().keySet()) {
            try {
                services.pushSwitches(service, Map.of());
            } catch (RuntimeException e) {
                log.warn("could not clear switches on {}: {}", service, e.toString());
            }
        }

        for (var e : manifest.services().entrySet()) {
            String service = e.getKey();
            String baseline = e.getValue().baseline();
            String running = registry.latest(service).map(d -> d.version()).orElse(baseline);
            if (!baseline.equals(deploys.desiredVersion(service)) || !baseline.equals(running)) {
                actions.deploy(service, baseline, "release-bot");
                done.add("deployed " + service + " " + baseline);
                hadSwitches.remove(service);
            }
        }
        // In-memory effects of a switch (e.g. a leaked heap) only go away with a restart.
        for (String service : hadSwitches) {
            actions.restart(service);
            done.add("restarted " + service + " (fault switches were set)");
        }

        for (String service : manifest.services().keySet()) {
            for (String kind : List.of("config", "flags")) {
                done.addAll(restoreDefaults(service, kind));
            }
        }
        if (!done.isEmpty()) {
            log.info("baseline restored: {}", done);
        }
        return done;
    }

    private List<String> restoreDefaults(String service, String kind) {
        List<String> done = new ArrayList<>();
        try {
            Map<?, ?> current = services.settings(service, kind);
            for (Object o : services.definitions(service, kind)) {
                Map<?, ?> def = (Map<?, ?>) o;
                String key = String.valueOf(def.get("key"));
                String dflt = String.valueOf(def.get("defaultValue"));
                if (!dflt.equals(String.valueOf(current.get(key)))) {
                    actions.changeSetting(service, kind, key, dflt, RESET_ACTOR, "restore baseline");
                    done.add(service + " " + kind + " " + key + " -> " + dflt);
                }
            }
        } catch (RuntimeException e) {
            log.warn("could not restore {} {} defaults: {}", service, kind, e.toString());
            done.add("FAILED to restore " + service + " " + kind + ": " + e.getMessage());
        }
        return done;
    }
}
