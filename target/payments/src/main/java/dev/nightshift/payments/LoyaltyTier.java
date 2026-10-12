package dev.nightshift.payments;

public enum LoyaltyTier {
    BRONZE(2, 1.0), SILVER(5, 1.0), GOLD(10, 1.5);

    private final int percent;
    private final double promoMultiplier;

    LoyaltyTier(int percent, double promoMultiplier) {
        this.percent = percent;
        this.promoMultiplier = promoMultiplier;
    }

    public int percent() {
        return percent;
    }

    public double promoMultiplier() {
        return promoMultiplier;
    }

    /** Null when the customer has no tier. */
    public static LoyaltyTier fromCode(String code) {
        return code == null || code.isBlank() ? null : LoyaltyTier.valueOf(code.trim().toUpperCase());
    }
}
