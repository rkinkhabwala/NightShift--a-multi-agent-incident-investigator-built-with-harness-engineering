package dev.nightshift.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Release 2.4 ships a query against a column that does not exist (scenario S09's real fault). */
@Testcontainers
@SpringBootTest(properties = {"nightshift.register=false", "APP_VERSION=2.4"})
@AutoConfigureMockMvc
class ReservationV24IT implements InventoryTestContainers {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void everyReservationFailsWithASqlErrorAndStockIsRolledBack() {
        int before = jdbc.sql("SELECT available FROM inventory.stock WHERE sku = 'SKU-002'").query(Integer.class).single();
        assertThatThrownBy(() -> mvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":50,\"items\":[{\"sku\":\"SKU-002\",\"qty\":1}]}")))
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .hasMessageContaining("qty_reserved")
                .cause().isInstanceOf(BadSqlGrammarException.class);
        int after = jdbc.sql("SELECT available FROM inventory.stock WHERE sku = 'SKU-002'").query(Integer.class).single();
        assertThat(after).isEqualTo(before);
    }
}
