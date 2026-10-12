package dev.nightshift.common.settings;

import dev.nightshift.common.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.event.EventListener;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import testsupport.common.CommonTestApp;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(classes = {CommonTestApp.class, RuntimeSettingsIT.Events.class},
        properties = {"spring.application.name=demo", "nightshift.register=false"})
@AutoConfigureMockMvc
class RuntimeSettingsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres(false);

    @TestConfiguration
    static class Events {
        final List<SettingChange> seen = new CopyOnWriteArrayList<>();

        @EventListener
        void record(SettingChange change) {
            seen.add(change);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RuntimeSettings settings;
    @Autowired JdbcClient jdbc;
    @Autowired Events events;

    @Test
    void changePersistsRecordsHistoryAndPublishesEvent() throws Exception {
        assertThat(settings.configInt("demo.pool.max")).isEqualTo(10);

        mvc.perform(put("/admin/config/demo.pool.max").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"1\",\"changedBy\":\"test\",\"reason\":\"shrink\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oldValue").value("10"))
                .andExpect(jsonPath("$.newValue").value("1"));

        assertThat(settings.configInt("demo.pool.max")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT value FROM ops.settings WHERE service='demo' AND key='demo.pool.max'")
                .query(String.class).single()).isEqualTo("1");
        assertThat(events.seen).anySatisfy(c -> assertThat(c.key()).isEqualTo("demo.pool.max"));

        mvc.perform(get("/admin/config/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("demo.pool.max"))
                .andExpect(jsonPath("$[0].changedBy").value("test"))
                .andExpect(jsonPath("$[0].reason").value("shrink"));
    }

    @Test
    void flagsAreSeparateFromConfig() throws Exception {
        mvc.perform(put("/admin/flags/demo_flag").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"true\",\"changedBy\":\"test\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/flags")).andExpect(jsonPath("$.demo_flag").value("true"));
        mvc.perform(get("/admin/config")).andExpect(jsonPath("$.demo_flag").doesNotExist());
        mvc.perform(get("/admin/flags/history")).andExpect(jsonPath("$[0].kind").value("FLAG"));
    }

    @Test
    void rejectsInvalidAndUnknownSettings() throws Exception {
        mvc.perform(put("/admin/config/demo.pool.max").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"500\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/admin/config/nope").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"1\"}")).andExpect(status().isNotFound());
    }

    @Test
    void persistedOverridesSurviveARestart() {
        settings.change(SettingKind.CONFIG, "demo.pool.max", "7", "test", null);
        RuntimeSettings fresh = new RuntimeSettings(jdbc, null, e -> { },
                new dev.nightshift.common.release.ReleaseInfo("demo", "1.0"),
                SettingsCatalog.of(SettingDefinition.intConfig("demo.pool.max", 10, 1, 50, "")));
        fresh.load();
        assertThat(fresh.configInt("demo.pool.max")).isEqualTo(7);
    }
}
