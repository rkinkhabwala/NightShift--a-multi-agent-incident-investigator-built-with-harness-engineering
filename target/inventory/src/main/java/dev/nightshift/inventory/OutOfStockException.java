package dev.nightshift.inventory;

public class OutOfStockException extends RuntimeException {

    private final String sku;

    public OutOfStockException(String sku, int requested) {
        super("not enough stock for " + sku + " (requested " + requested + ")");
        this.sku = sku;
    }

    public String sku() {
        return sku;
    }
}
