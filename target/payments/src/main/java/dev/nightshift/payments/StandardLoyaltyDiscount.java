package dev.nightshift.payments;

/** Release 1.3: flat percentage per tier; customers without a tier get no discount. */
public class StandardLoyaltyDiscount implements LoyaltyDiscount {

    @Override
    public long discountCents(ChargeRequest request) {
        LoyaltyTier tier = LoyaltyTier.fromCode(request.loyaltyTier());
        if (tier == null) {
            return 0;
        }
        return request.amountCents() * tier.percent() / 100;
    }
}
