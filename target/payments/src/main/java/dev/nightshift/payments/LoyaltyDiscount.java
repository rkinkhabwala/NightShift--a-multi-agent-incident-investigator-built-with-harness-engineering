package dev.nightshift.payments;

public interface LoyaltyDiscount {

    /** Discount in cents for this charge. */
    long discountCents(ChargeRequest request);
}
