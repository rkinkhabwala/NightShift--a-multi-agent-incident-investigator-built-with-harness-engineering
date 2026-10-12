package dev.nightshift.inventory;

import dev.nightshift.common.settings.RuntimeSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background job: keeps steady traffic from draining stock. */
@Component
public class Restocker {

    private static final Logger log = LoggerFactory.getLogger(Restocker.class);
    static final int RESTOCK_LEVEL = 100000;

    private final StockRepository stock;
    private final RuntimeSettings settings;

    public Restocker(StockRepository stock, RuntimeSettings settings) {
        this.stock = stock;
        this.settings = settings;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void restock() {
        try {
            int n = stock.restock(settings.configInt("inventory.restock.threshold"), RESTOCK_LEVEL);
            if (n > 0) {
                log.info("restocked {} SKUs to {}", n, RESTOCK_LEVEL);
            }
        } catch (RuntimeException e) {
            log.warn("restock failed: {}", e.toString());
        }
    }
}
