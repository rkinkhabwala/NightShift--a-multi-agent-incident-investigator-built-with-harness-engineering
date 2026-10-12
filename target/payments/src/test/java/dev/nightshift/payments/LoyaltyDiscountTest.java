package dev.nightshift.payments;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoyaltyDiscountTest {

    private static ChargeRequest charge(String tier) {
        return new ChargeRequest(1L, 1L, tier, 10000, List.of(new ChargeRequest.Item("SKU-001", 1)));
    }

    @Test
    void release13GivesNoDiscountWithoutATier() {
        LoyaltyDiscount v13 = new StandardLoyaltyDiscount();
        assertThat(v13.discountCents(charge(null))).isZero();
        assertThat(v13.discountCents(charge("GOLD"))).isEqualTo(1000);
        assertThat(v13.discountCents(charge("BRONZE"))).isEqualTo(200);
    }

    @Test
    void release14AppliesPromoMultiplier() {
        assertThat(new TieredLoyaltyDiscount().discountCents(charge("GOLD"))).isEqualTo(1500);
        assertThat(new TieredLoyaltyDiscount().discountCents(charge("SILVER"))).isEqualTo(500);
    }

    @Test
    void release14ThrowsNullPointerForCustomersWithoutATier() {
        assertThatThrownBy(() -> new TieredLoyaltyDiscount().discountCents(charge(null)))
                .isInstanceOf(NullPointerException.class);
    }
}
