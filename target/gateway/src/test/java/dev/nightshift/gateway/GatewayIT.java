package dev.nightshift.gateway;

import dev.nightshift.common.PostgresTestSupport;
import dev.nightshift.common.StubServer;
import dev.nightshift.common.switches.FaultSwitches;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {"nightshift.register=false", "APP_VERSION=2.0"})
@AutoConfigureMockMvc
class GatewayIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres(false);

    static final StubServer orders = new StubServer();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("nightshift.orders-url", orders::url);
    }

    @Autowired MockMvc mvc;
    @Autowired FaultSwitches switches;

    static final String ORDER = "{\"customerId\":1,\"items\":[{\"sku\":\"SKU-001\",\"qty\":1}]}";

    @AfterEach
    void reset() {
        switches.replace(Map.of());
        orders.respond(201, "{}");
    }

    @Test
    void relaysSuccessAndClientErrors() throws Exception {
        orders.respond(201, "{\"id\":5,\"status\":\"PAID\"}");
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PAID"));
        orders.respond(422, "{\"error\":\"unknown_sku\"}");
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("unknown_sku"));
        orders.respond(404, "");
        mvc.perform(get("/api/orders/77")).andExpect(status().isNotFound());
    }

    @Test
    void upstreamFailuresBecomeBadGatewayOrTimeout(CapturedOutput output) throws Exception {
        orders.respond(502, "{\"error\":\"payment_error\"}");
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.upstream").value("orders"));
        assertThat(output.getOut()).contains("upstream orders failed for POST /orders: HTTP 502");

        mvc.perform(put("/admin/config/gateway.orders-client.timeout-ms").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"50\",\"changedBy\":\"test\"}")).andExpect(status().isOk());
        orders.respond(201, "{}").delay(300);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isGatewayTimeout());
        mvc.perform(put("/admin/config/gateway.orders-client.timeout-ms").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"8000\",\"changedBy\":\"test\"}")).andExpect(status().isOk());
    }

    @Test
    void skippedKeyRefreshWarnsOnEveryRequestButDoesNotFailIt(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER)).andExpect(status().isCreated());
        assertThat(output.getOut()).doesNotContain("JWKS refresh skipped");

        mvc.perform(put("/internal/switches").contentType(MediaType.APPLICATION_JSON)
                .content("{\"gateway.jwks-refresh-skipped\":\"on\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER)).andExpect(status().isCreated());
        assertThat(output.getOut()).contains("JWKS refresh skipped, using cached signing keys");
    }
}
