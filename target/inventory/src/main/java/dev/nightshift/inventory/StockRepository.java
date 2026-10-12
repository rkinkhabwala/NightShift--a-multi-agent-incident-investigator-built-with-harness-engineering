package dev.nightshift.inventory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class StockRepository {

    private final JdbcClient jdbc;

    public StockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Integer> available(String sku) {
        return jdbc.sql("SELECT available FROM inventory.stock WHERE sku = ?").param(sku)
                .query(Integer.class).optional();
    }

    /** Atomically takes qty from stock; returns the new available level, or empty if too little. */
    public Optional<Integer> take(String sku, int qty) {
        return jdbc.sql("""
                UPDATE inventory.stock SET available = available - ?, reserved = reserved + ?, updated_at = now()
                WHERE sku = ? AND available >= ? RETURNING available
                """).params(qty, qty, sku, qty).query(Integer.class).optional();
    }

    /** Tops up every SKU below the threshold (except the discontinued one). Returns rows changed. */
    public int restock(int threshold, int level) {
        return jdbc.sql("""
                UPDATE inventory.stock SET available = ?, updated_at = now()
                WHERE available < ? AND sku <> 'SKU-OOS'
                """).params(level, threshold).update();
    }
}
