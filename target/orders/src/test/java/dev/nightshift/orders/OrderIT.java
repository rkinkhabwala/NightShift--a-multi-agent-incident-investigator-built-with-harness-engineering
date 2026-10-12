package dev.nightshift.orders;

import dev.nightshift.common.PostgresTestSupport;
import dev.nightshift.common.StubServer;
import dev.nightshift.common.settings.RuntimeSettings;
import dev.nightshift.common.settings.SettingKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {"nightshift.register=false", "APP_VERSION=3.1"})
@AutoConfigureMockMvc
class OrderIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres(false);

    static final StubServer payments = new StubServer();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("nightshift.payments-url", payments::url);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired RuntimeSettings settings;
    @Autowired PoolSettings pool;

    static final String ORDER = "{\"customerId\":21,\"items\":[{\"sku\":\"SKU-003\",\"qty\":2},{\"sku\":\"SKU-010\",\"qty\":1}]}";

    @AfterEach
    void reset() {
        payments.respond(201, "{\"chargeId\":1,\"status\":\"CAPTURED\"}");
        settings.change(SettingKind.CONFIG, PoolSettings.MAX_KEY, "10", "test", null);
        settings.change(SettingKind.FLAG, PricingService.NEW_PRICING_FLAG, "false", "test", null);
    }

    @Test
    void paidOrderIsStoredWithItsLines() throws Exception {
        payments.respond(201, "{\"chargeId\":1,\"status\":\"CAPTURED\"}");
        String body = mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(get("/orders/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void paymentServerErrorFailsTheOrderWithBadGateway() throws Exception {
        payments.respond(500, "{\"error\":\"boom\"}");
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("payment_error"));
        assertThat(jdbc.sql("SELECT status FROM orders.orders ORDER BY id DESC LIMIT 1").query(String.class).single())
                .isEqualTo("FAILED");
    }

    @Test
    void declinesAndStockConflictsPassThrough() throws Exception {
        payments.respond(402, "{\"error\":\"payment_declined\"}");
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isPaymentRequired());
        payments.respond(409, "{\"error\":\"out_of_stock\"}");
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("out_of_stock"));
    }

    @Test
    void unknownSkuAndCustomerAreUnprocessable() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":21,\"items\":[{\"sku\":\"SKU-999\",\"qty\":1}]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("unknown_sku"));
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":99999,\"items\":[{\"sku\":\"SKU-001\",\"qty\":1}]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("unknown_customer"));
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{\"customerId\":21,\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void overlongCustomerNoteIsDroppedAndLogged(CapturedOutput output) throws Exception {
        String note = "x".repeat(130);
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":21,\"customerNote\":\"" + note + "\",\"items\":[{\"sku\":\"SKU-001\",\"qty\":1}]}"))
                .andExpect(status().isCreated());
        assertThat(output.getOut()).contains("customer note dropped for customer 21").contains(note);
        assertThat(jdbc.sql("SELECT customer_note FROM orders.orders ORDER BY id DESC LIMIT 1")
                .query(String.class).optional()).isEmpty();
    }

    @Test
    void poolSizeConfigResizesTheLivePool() throws Exception {
        mvc.perform(put("/admin/config/orders.db.pool.max").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"1\",\"changedBy\":\"test\"}")).andExpect(status().isOk());
        assertThat(pool.currentMax()).isEqualTo(1);
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER)).andExpect(status().isCreated());
    }

    @Test
    void newPricingRunsTheDemandQuery() throws Exception {
        settings.change(SettingKind.FLAG, PricingService.NEW_PRICING_FLAG, "true", "test", null);
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAID"));
    }
}
