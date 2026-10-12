package dev.nightshift.inventory;

import dev.nightshift.common.switches.FaultSwitches;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationAuditTrailTest {

    @Test
    void retainsNothingUnlessTheLeakSwitchIsOn() {
        FaultSwitches switches = new FaultSwitches();
        ReservationAuditTrail trail = new ReservationAuditTrail(switches);
        int before = trail.retainedEntries();

        trail.record(1, "SKU-001", 1);
        assertThat(trail.retainedEntries()).isEqualTo(before);

        switches.replace(Map.of(ReservationAuditTrail.SWITCH, "on", ReservationAuditTrail.SWITCH_KB, "1"));
        trail.record(2, "SKU-001", 1);
        trail.record(3, "SKU-001", 1);
        assertThat(trail.retainedEntries()).isEqualTo(before + 2);
    }
}
