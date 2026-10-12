package dev.nightshift.common.switches;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Hidden fault switches set by the chaos controller. They are deliberately not part of the
 * config or flag endpoints, so a fault injected this way leaves no "change" for agents to find.
 * The controller pushes them over /internal/switches; a restarted service gets them back when it
 * registers (see {@link dev.nightshift.common.release.DeployRegistration}).
 */
@Component
public class FaultSwitches {

    private static final Logger log = LoggerFactory.getLogger(FaultSwitches.class);

    private volatile Map<String, String> current = Map.of();

    public boolean isOn(String name) {
        String v = current.get(name);
        return v != null && !v.equalsIgnoreCase("off") && !v.equalsIgnoreCase("false");
    }

    public int intValue(String name, int fallback) {
        try {
            return Integer.parseInt(current.getOrDefault(name, ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public Map<String, String> snapshot() {
        return current;
    }

    public void replace(Map<String, String> switches) {
        Map<String, String> next = switches == null ? Map.of() : Map.copyOf(switches);
        if (!next.equals(current)) {
            log.debug("fault switches now {}", next.keySet());
        }
        current = next;
    }
}
