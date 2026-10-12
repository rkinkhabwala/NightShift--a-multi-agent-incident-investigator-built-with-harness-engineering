package dev.nightshift.orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Background job: refreshes the daily sales report (revenue per day and top SKUs over 90 days)
 * every 10 s, in one read-only transaction. It holds a database connection for about a second
 * each run, which is unnoticeable with a normal pool and painful with a pool of one.
 */
@Component
@RestController
public class SalesReport {

    private static final Logger log = LoggerFactory.getLogger(SalesReport.class);

    record DailySales(String day, long lines, long revenueCents) {
    }

    record TopSku(String sku, long units) {
    }

    record Report(Instant refreshedAt, long tookMs, List<DailySales> days, List<TopSku> topSkus) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private volatile Report latest;

    public SalesReport(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 20000)
    public void refresh() {
        long start = System.nanoTime();
        try {
            Report r = tx.execute(status -> {
                status.setRollbackOnly();
                List<DailySales> days = jdbc.sql("""
                        SELECT to_char(date_trunc('day', created_at), 'YYYY-MM-DD') AS day, count(*) AS lines,
                               sum(qty::bigint * unit_price_cents) AS revenue
                        FROM orders.order_lines WHERE created_at > now() - interval '90 days'
                        GROUP BY 1 ORDER BY 1 DESC
                        """).query((rs, n) -> new DailySales(rs.getString(1), rs.getLong(2), rs.getLong(3))).list();
                List<TopSku> top = jdbc.sql("""
                        SELECT sku, sum(qty) AS units FROM orders.order_lines
                        WHERE created_at > now() - interval '90 days'
                        GROUP BY sku ORDER BY units DESC LIMIT 10
                        """).query((rs, n) -> new TopSku(rs.getString(1), rs.getLong(2))).list();
                return new Report(Instant.now(), (System.nanoTime() - start) / 1_000_000, days, top);
            });
            latest = r;
            log.debug("sales report refreshed in {} ms", r == null ? -1 : r.tookMs());
        } catch (RuntimeException e) {
            log.warn("sales report refresh failed: {}", e.toString());
        }
    }

    @GetMapping("/reports/daily-sales")
    public Map<String, Object> get() {
        Report r = latest;
        return r == null ? Map.of("status", "not ready") : Map.of("refreshedAt", r.refreshedAt(), "tookMs", r.tookMs(),
                "days", r.days().size(), "topSkus", r.topSkus());
    }
}
