package dev.nightshift.chaoscontroller;

import dev.nightshift.chaoscontroller.ops.DeployRegistry;
import dev.nightshift.chaoscontroller.ops.FaultSwitchStore;
import dev.nightshift.common.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class DeployRegistryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres(false);

    @TempDir
    static Path releases;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("nightshift.releases-dir", () -> releases.toString());
        // No target services in this test: restart calls fail, which deploy tolerates by design.
        r.add("nightshift.service-host-suffix", () -> ".invalid");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired DeployRegistry registry;
    @Autowired FaultSwitchStore switches;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM ops.deploys").update();
        jdbc.sql("DELETE FROM ops.service_starts").update();
        switches.clearAll();
    }

    @Test
    void firstRegistrationRecordsTheInitialDeployAndReturnsSwitches() throws Exception {
        switches.set("inventory", "inventory.leak", "on", "S04");
        mvc.perform(post("/ops/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service\":\"inventory\",\"version\":\"2.2\",\"instance\":\"c1\",\"startedAt\":\"2026-10-12T10:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.switches['inventory.leak']").value("on"));
        mvc.perform(get("/ops/deploys").param("service", "inventory").param("since", "2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$[0].version").value("2.2"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].initiatedBy").value("bootstrap"));
    }

    @Test
    void deployWritesTheReleaseFileAndCompletesOnRegistration() throws Exception {
        registry.insert("payments", "1.3", null, "Baseline release.", "bootstrap", "COMPLETED", java.time.Instant.now());

        mvc.perform(post("/ops/services/payments/deploys").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"1.4\",\"initiatedBy\":\"chaos:S01\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.previousVersion").value("1.3"))
                .andExpect(jsonPath("$.changelog").value(org.hamcrest.Matchers.containsString("Loyalty")));
        assertThat(Files.readString(releases.resolve("payments.version")).trim()).isEqualTo("1.4");

        mvc.perform(post("/ops/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"service\":\"payments\",\"version\":\"1.4\",\"instance\":\"c1\",\"startedAt\":\"" + java.time.Instant.now() + "\"}"))
                .andExpect(status().isOk());
        assertThat(registry.latest("payments").orElseThrow().status()).isEqualTo("COMPLETED");
        mvc.perform(get("/ops/services"))
                .andExpect(jsonPath("$.payments.version").value("1.4"))
                .andExpect(jsonPath("$.payments.desiredVersion").value("1.4"))
                .andExpect(jsonPath("$.payments.startsLast10m").value(1));
    }

    @Test
    void unknownVersionsAndServicesAreRejected() throws Exception {
        mvc.perform(post("/ops/services/payments/deploys").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"9.9\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/ops/services/nope/deploys").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"1.0\"}")).andExpect(status().isNotFound());
    }
}
