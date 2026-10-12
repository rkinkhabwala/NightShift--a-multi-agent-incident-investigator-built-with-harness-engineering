package dev.nightshift.inventory;

import dev.nightshift.common.release.ReleaseInfo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    // Release 2.4 renamed the column in code only ("qty" -> "qty_reserved") and shipped without
    // the matching migration, so every insert fails with a SQL error. Fixed by rolling back.
    static final String INSERT_V22 = "INSERT INTO inventory.reservations (order_id, sku, qty) VALUES (?, ?, ?) RETURNING id";
    static final String INSERT_V24 = "INSERT INTO inventory.reservations (order_id, sku, qty_reserved) VALUES (?, ?, ?) RETURNING id";

    private final JdbcClient jdbc;
    private final String insertSql;

    public ReservationRepository(JdbcClient jdbc, ReleaseInfo release) {
        this.jdbc = jdbc;
        this.insertSql = release.isAtLeast("2.4") ? INSERT_V24 : INSERT_V22;
    }

    public long insert(long orderId, String sku, int qty) {
        return jdbc.sql(insertSql).params(orderId, sku, qty).query(Long.class).single();
    }
}
