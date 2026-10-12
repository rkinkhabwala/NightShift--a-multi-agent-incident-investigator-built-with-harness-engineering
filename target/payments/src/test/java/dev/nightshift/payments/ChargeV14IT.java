package dev.nightshift.payments;

import dev.nightshift.common.PostgresTestSupport;
import dev.nightshift.common.StubServer;
import jakarta.servlet.ServletException;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Scenario S01's real fault: release 1.4 fails for customers without a loyalty tier. */
@Testcontainers
@SpringBootTest(properties = {"nightshift.register=false", "APP_VERSION=1.4"})
@AutoConfigureMockMvc
class ChargeV14IT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres(false);

    static final StubServer inventory = new StubServer();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("nightshift.inventory-url", inventory::url);
    }

    @Autowired MockMvc mvc;

    @Test
    void customersWithATierStillWork() throws Exception {
        mvc.perform(post("/charges").contentType(MediaType.APPLICATION_JSON).content(ChargeIT.GOLD))
                .andExpect(status().isCreated());
    }

    @Test
    void customersWithoutATierHitANullPointer() {
        assertThatThrownBy(() -> mvc.perform(post("/charges").contentType(MediaType.APPLICATION_JSON)
                .content(ChargeIT.NO_TIER)))
                .isInstanceOf(ServletException.class)
                .hasRootCauseInstanceOf(NullPointerException.class);
    }
}
