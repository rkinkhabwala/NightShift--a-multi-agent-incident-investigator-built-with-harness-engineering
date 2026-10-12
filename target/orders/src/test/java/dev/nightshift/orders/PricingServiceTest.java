package dev.nightshift.orders;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PricingServiceTest {

    @Test
    void adjustmentIsBoundedToTenPercent() {
        // total 900 over 90 days -> 70/week on average
        assertThat(PricingService.adjust(1000, new PricingService.Demand(70, 900, 0))).isEqualTo(1000);
        assertThat(PricingService.adjust(1000, new PricingService.Demand(7000, 900, 0))).isEqualTo(1100);
        assertThat(PricingService.adjust(1000, new PricingService.Demand(0, 900, 0))).isEqualTo(900);
    }

    @Test
    void unknownDemandKeepsListPrice() {
        assertThat(PricingService.adjust(1234, null)).isEqualTo(1234);
        assertThat(PricingService.adjust(1234, new PricingService.Demand(0, 0, 0))).isEqualTo(1234);
    }
}
