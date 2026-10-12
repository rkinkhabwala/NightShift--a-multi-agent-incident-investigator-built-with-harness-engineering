package dev.nightshift.inventory;

import dev.nightshift.common.switches.FaultSwitches;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a copy of each reservation payload for auditing. With the hidden {@code inventory.leak}
 * switch on, entries are never released (a classic unbounded static collection), so the heap
 * grows until the JVM dies with OutOfMemoryError and the container restarts.
 */
@Component
public class ReservationAuditTrail {

    static final String SWITCH = "inventory.leak";
    static final String SWITCH_KB = "inventory.leak.kb-per-reservation";

    private static final List<byte[]> RETAINED = new ArrayList<>();

    private final FaultSwitches switches;

    public ReservationAuditTrail(FaultSwitches switches) {
        this.switches = switches;
    }

    public void record(long orderId, String sku, int qty) {
        if (!switches.isOn(SWITCH)) {
            return;
        }
        byte[] payload = new byte[switches.intValue(SWITCH_KB, 256) * 1024];
        payload[0] = (byte) orderId;
        synchronized (RETAINED) {
            RETAINED.add(payload);
        }
    }

    public int retainedEntries() {
        synchronized (RETAINED) {
            return RETAINED.size();
        }
    }
}
