package dev.nightshift.orders;

import dev.nightshift.common.settings.RuntimeSettings;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prices order lines. With the {@code new_pricing} flag on, each unit price is nudged by recent
 * demand for the SKU. The demand statistics are computed by aggregating (and sorting, for the
 * median) the whole order history on every order: correct, but expensive.
 */
@Service
public class PricingService {

    static final String NEW_PRICING_FLAG = "new_pricing";

    static final String DEMAND_SQL = """
            SELECT count(*) FILTER (WHERE ol.created_at > now() - interval '7 days') AS recent,
                   count(*) AS total,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY ol.unit_price_cents) AS median_price
            FROM orders.order_lines ol
            WHERE ol.order_id IN (SELECT order_id FROM orders.order_lines WHERE sku = ?)
            """;

    record Demand(long recent, long total, double medianPrice) {
    }

    private final JdbcClient jdbc;
    private final RuntimeSettings settings;

    public PricingService(JdbcClient jdbc, RuntimeSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    public Map<String, Integer> unitPrices(List<String> skus, Map<String, Integer> listPrices) {
        if (!settings.flag(NEW_PRICING_FLAG)) {
            return listPrices;
        }
        Map<String, Integer> out = new HashMap<>();
        for (String sku : skus) {
            Demand demand = jdbc.sql(DEMAND_SQL).param(sku)
                    .query((rs, n) -> new Demand(rs.getLong("recent"), rs.getLong("total"), rs.getDouble("median_price")))
                    .optional().orElse(null);
            out.put(sku, adjust(listPrices.get(sku), demand));
        }
        return out;
    }

    /** ±10 % depending on how the last 7 days compare with the long-run weekly average. */
    static int adjust(int listPrice, Demand d) {
        if (d == null || d.total() == 0) {
            return listPrice;
        }
        double weeklyAverage = d.total() / (115.0 / 7.0);
        double ratio = weeklyAverage == 0 ? 1.0 : d.recent() / weeklyAverage;
        double factor = 1.0 + Math.max(-0.1, Math.min(0.1, (ratio - 1.0) * 0.1));
        return (int) Math.max(1, Math.round(listPrice * factor));
    }
}
