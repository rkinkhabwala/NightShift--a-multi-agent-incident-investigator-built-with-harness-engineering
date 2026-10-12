package dev.nightshift.chaoscontroller.chaos;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ToxiproxyClientIT {

    @Container
    static ToxiproxyContainer toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0");

    static ToxiproxyClient client;

    @BeforeAll
    static void createProxy() {
        String api = "http://" + toxiproxy.getHost() + ":" + toxiproxy.getControlPort();
        RestClient.create(api).post().uri("/proxies").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "redis", "listen", "0.0.0.0:16379", "upstream", "redis:6379"))
                .retrieve().toBodilessEntity();
        client = new ToxiproxyClient(api);
    }

    @Test
    void addReplaceAndRemoveToxics() {
        client.addToxic("redis", "redis_latency", "latency", "downstream", Map.of("latency", 2000, "jitter", 100));
        client.addToxic("redis", "redis_latency", "latency", "downstream", Map.of("latency", 1500));
        assertThat(client.toxics("redis")).singleElement()
                .satisfies(t -> assertThat(((Map<?, ?>) t.get("attributes")).get("latency")).isEqualTo(1500));
        client.removeToxic("redis", "redis_latency");
        client.removeToxic("redis", "redis_latency"); // idempotent
        assertThat(client.toxics("redis")).isEmpty();
    }

    @Test
    void disableEnableAndReset() {
        client.setEnabled("redis", false);
        assertThat(client.proxy("redis").get("enabled")).isEqualTo(false);
        client.addToxic("redis", "t", "latency", null, Map.of("latency", 10));
        client.reset();
        assertThat(client.proxy("redis").get("enabled")).isEqualTo(true);
        assertThat(client.toxics("redis")).isEmpty();
    }
}
