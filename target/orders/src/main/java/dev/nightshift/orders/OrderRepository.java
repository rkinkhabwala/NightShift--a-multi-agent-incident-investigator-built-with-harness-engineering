package dev.nightshift.orders;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class OrderRepository {

    public record Customer(long id, String loyaltyTier) {
    }

    public record Line(String sku, int qty, int unitPriceCents) {
    }

    public record Order(long id, long customerId, String status, long totalCents, String failure,
                        Instant createdAt, List<Line> lines) {
    }

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Customer> customer(long id) {
        return jdbc.sql("SELECT id, loyalty_tier FROM orders.customers WHERE id = ?").param(id)
                .query((rs, n) -> new Customer(rs.getLong(1), rs.getString(2))).optional();
    }

    public Map<String, Integer> prices(List<String> skus) {
        return jdbc.sql("SELECT sku, price_cents FROM orders.price_list WHERE sku IN (:skus)")
                .param("skus", skus)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getInt(2)))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public long insert(long customerId, long totalCents, String note, List<Line> lines) {
        long id = jdbc.sql("""
                INSERT INTO orders.orders (customer_id, status, total_cents, customer_note)
                VALUES (?, 'PENDING', ?, ?) RETURNING id
                """).params(customerId, totalCents, note).query(Long.class).single();
        for (Line line : lines) {
            jdbc.sql("INSERT INTO orders.order_lines (order_id, sku, qty, unit_price_cents) VALUES (?, ?, ?, ?)")
                    .params(id, line.sku(), line.qty(), line.unitPriceCents()).update();
        }
        return id;
    }

    public void finish(long id, String status, String failure) {
        jdbc.sql("UPDATE orders.orders SET status = ?, failure = ?, updated_at = now() WHERE id = ?")
                .params(status, failure, id).update();
    }

    public Optional<Order> find(long id) {
        List<Line> lines = jdbc.sql("SELECT sku, qty, unit_price_cents FROM orders.order_lines WHERE order_id = ? ORDER BY id")
                .param(id).query((rs, n) -> new Line(rs.getString(1), rs.getInt(2), rs.getInt(3))).list();
        return jdbc.sql("SELECT id, customer_id, status, total_cents, failure, created_at FROM orders.orders WHERE id = ?")
                .param(id)
                .query((rs, n) -> new Order(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getTimestamp(6).toInstant(), lines))
                .optional();
    }
}
