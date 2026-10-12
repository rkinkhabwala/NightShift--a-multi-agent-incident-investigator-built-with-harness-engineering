package dev.nightshift.payments;

/**
 * Release 1.4: adds the promotional multiplier per tier. The refactor dropped the "no tier"
 * guard, so customers without a loyalty tier hit a NullPointerException on every charge.
 */
public class TieredLoyaltyDiscount implements LoyaltyDiscount {

    @Override
    public long discountCents(ChargeRequest request) {
        LoyaltyTier tier = LoyaltyTier.fromCode(request.loyaltyTier());
        double rate = tier.percent() * tier.promoMultiplier() / 100.0;
        return Math.round(request.amountCents() * rate);
    }
}
