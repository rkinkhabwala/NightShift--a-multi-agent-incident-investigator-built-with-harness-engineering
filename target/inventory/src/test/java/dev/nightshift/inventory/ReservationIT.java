package dev.nightshift.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {"nightshift.register=false", "APP_VERSION=2.2"})
@AutoConfigureMockMvc
class ReservationIT implements InventoryTestContainers {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired StringRedisTemplate redis;

    @Test
    void reservesStockAndUpdatesCache() throws Exception {
        int before = available("SKU-007");
        mvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":42,\"items\":[{\"sku\":\"SKU-007\",\"qty\":3}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationIds.length()").value(1));
        assertThat(available("SKU-007")).isEqualTo(before - 3);
        assertThat(redis.opsForValue().get("stock:SKU-007")).isEqualTo(Integer.toString(before - 3));
        assertThat(jdbc.sql("SELECT count(*) FROM inventory.reservations WHERE order_id = 42")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void outOfStockIsAConflictAndChangesNothing() throws Exception {
        mvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":43,\"items\":[{\"sku\":\"SKU-001\",\"qty\":1},{\"sku\":\"SKU-OOS\",\"qty\":1}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("out_of_stock"))
                .andExpect(jsonPath("$.sku").value("SKU-OOS"));
        assertThat(jdbc.sql("SELECT count(*) FROM inventory.reservations WHERE order_id = 43")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void rejectsInvalidRequests() throws Exception {
        mvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":44,\"items\":[]}")).andExpect(status().isBadRequest());
    }

    private int available(String sku) {
        return jdbc.sql("SELECT available FROM inventory.stock WHERE sku = ?").param(sku).query(Integer.class).single();
    }
}
