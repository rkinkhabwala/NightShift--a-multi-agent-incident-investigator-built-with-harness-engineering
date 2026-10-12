package dev.nightshift.payments;

import dev.nightshift.common.PostgresTestSupport;
import dev.nightshift.common.StubServer;
import dev.nightshift.common.settings.RuntimeSettings;
import dev.nightshift.common.settings.SettingKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {"nightshift.register=false", "APP_VERSION=1.3"})
@AutoConfigureMockMvc
class ChargeIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres(false);

    static final StubServer inventory = new StubServer();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("nightshift.inventory-url", inventory::url);
    }

    @Autowired MockMvc mvc;
    @Autowired RuntimeSettings settings;

    static final String NO_TIER = "{\"orderId\":1,\"customerId\":7,\"amountCents\":5000,\"items\":[{\"sku\":\"SKU-001\",\"qty\":1}]}";
    static final String GOLD = "{\"orderId\":2,\"customerId\":8,\"loyaltyTier\":\"GOLD\",\"amountCents\":5000,\"items\":[{\"sku\":\"SKU-001\",\"qty\":1}]}";

    @AfterEach
    void resetStub() {
        inventory.respond(201, "{}");
        settings.change(SettingKind.CONFIG, "payments.inventory-client.timeout-ms", "2000", "test", null);
    }

    @Test
    void capturesAChargeAfterReservingStock() throws Exception {
        inventory.respond(201, "{\"orderId\":2,\"reservationIds\":[1]}");
        mvc.perform(post("/charges").contentType(MediaType.APPLICATION_JSON).content(GOLD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.discountCents").value(500))
                .andExpect(jsonPath("$.amountCents").value(4500));
    }

    @Test
    void release13HandlesCustomersWithoutATier() throws Exception {
        mvc.perform(post("/charges").contentType(MediaType.APPLICATION_JSON).content(NO_TIER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discountCents").value(0));
    }

    @Test
    void outOfStockIsPassedOnAsConflict() throws Exception {
        inventory.respond(409, "{\"error\":\"out_of_stock\",\"sku\":\"SKU-OOS\"}");
        mvc.perform(post("/charges").contentType(MediaType.APPLICATION_JSON).content(GOLD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("out_of_stock"));
    }

    @Test
    void inventoryServerErrorBecomesBadGateway() throws Exception {
        inventory.respond(500, "{\"error\":\"boom\"}");
        mvc.perform(post("/charges").contentType(MediaType.APPLICATION_JSON).content(GOLD))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("inventory_unavailable"));
    }

    @Test
    void timeoutConfigChangeAppliesWithoutRestart() throws Exception {
        inventory.respond(201, "{}").delay(300);
        mvc.perform(post("/charges").contentType(MediaType.APPLICATION_JSON).content(GOLD))
                .andExpect(status().isCreated());
        settings.change(SettingKind.CONFIG, "payments.inventory-client.timeout-ms", "50", "test", "S07-style");
        mvc.perform(post("/charges").contentType(MediaType.APPLICATION_JSON).content(GOLD))
                .andExpect(status().isGatewayTimeout());
    }

    @Test
    void largeChargesAreDeclined() throws Exception {
        String big = "{\"orderId\":3,\"customerId\":9,\"amountCents\":500000,\"items\":[{\"sku\":\"SKU-001\",\"qty\":1}]}";
        int before = inventory.requests();
        mvc.perform(post("/charges").contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").value("payment_declined"));
        org.assertj.core.api.Assertions.assertThat(inventory.requests()).isEqualTo(before);
    }
}
