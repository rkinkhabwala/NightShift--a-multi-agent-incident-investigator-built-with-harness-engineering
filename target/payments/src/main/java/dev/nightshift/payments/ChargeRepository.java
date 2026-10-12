package dev.nightshift.payments;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ChargeRepository {

    private final JdbcClient jdbc;

    public ChargeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(ChargeRequest request, long amountCents, long discountCents, String status) {
        return jdbc.sql("""
                INSERT INTO payments.charges (order_id, customer_id, amount_cents, discount_cents, status)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """)
                .params(request.orderId(), request.customerId(), amountCents, discountCents, status)
                .query(Long.class).single();
    }
}
