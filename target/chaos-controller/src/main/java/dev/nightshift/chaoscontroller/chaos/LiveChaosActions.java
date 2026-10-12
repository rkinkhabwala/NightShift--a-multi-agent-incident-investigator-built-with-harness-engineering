package dev.nightshift.chaoscontroller.chaos;

import dev.nightshift.chaoscontroller.ops.DeployRegistry;
import dev.nightshift.chaoscontroller.ops.DeployService;
import dev.nightshift.chaoscontroller.ops.FaultSwitchStore;
import dev.nightshift.chaoscontroller.ops.ServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class LiveChaosActions implements ChaosActions {

    private static final Logger log = LoggerFactory.getLogger(LiveChaosActions.class);

    private final DeployService deploys;
    private final DeployRegistry registry;
    private final ServiceClient services;
    private final FaultSwitchStore switches;
    private final ToxiproxyClient toxiproxy;
    private final AlertSender alerts;
    private final RestClient gateway;
    private final Duration deployTimeout;

    public LiveChaosActions(DeployService deploys, DeployRegistry registry, ServiceClient services,
                            FaultSwitchStore switches, ToxiproxyClient toxiproxy, AlertSender alerts,
                            @Value("${nightshift.gateway-url:http://gateway:8080}") String gatewayUrl,
                            @Value("${nightshift.deploy-timeout-seconds:240}") long deployTimeoutSeconds) {
        this.deploys = deploys;
        this.registry = registry;
        this.services = services;
        this.switches = switches;
        this.toxiproxy = toxiproxy;
        this.alerts = alerts;
        this.gateway = RestClient.builder().baseUrl(gatewayUrl).build();
        this.deployTimeout = Duration.ofSeconds(deployTimeoutSeconds);
    }

    @Override
    public void deploy(String service, String version, String initiatedBy) {
        long id = deploys.deploy(service, version, initiatedBy).id();
        Instant deadline = Instant.now().plus(deployTimeout);
        while (Instant.now().isBefore(deadline)) {
            String status = registry.find(id).map(d -> d.status()).orElse("UNKNOWN");
            if ("COMPLETED".equals(status) && healthy(service)) {
                return;
            }
            sleepMillis(2000);
        }
        throw new IllegalStateException("deploy of " + service + " " + version + " did not complete in " + deployTimeout);
    }

    @Override
    public void changeSetting(String service, String kind, String key, String value, String changedBy, String reason) {
        services.changeSetting(service, kind, key, value, changedBy, reason);
    }

    @Override
    public void addToxic(String proxy, String name, String type, String stream, Map<String, Object> attributes) {
        toxiproxy.addToxic(proxy, name, type, stream, attributes);
    }

    @Override
    public void removeToxic(String proxy, String name) {
        toxiproxy.removeToxic(proxy, name);
    }

    @Override
    public void setProxyEnabled(String proxy, boolean enabled) {
        toxiproxy.setEnabled(proxy, enabled);
    }

    @Override
    public void setSwitch(String service, String name, String value, String scenario) {
        switches.set(service, name, value, scenario);
        push(service);
    }

    @Override
    public void clearSwitch(String service, String name) {
        switches.clear(service, name);
        push(service);
    }

    @Override
    public void restart(String service) {
        try {
            services.restart(service);
        } catch (RuntimeException e) {
            log.warn("restart of {} failed: {}", service, e.toString());
        }
        sleepMillis(3000);
        Instant deadline = Instant.now().plus(deployTimeout);
        while (Instant.now().isBefore(deadline) && !healthy(service)) {
            sleepMillis(2000);
        }
    }

    @Override
    public void fireAlert(String alertname, String service, String severity, String summary) {
        alerts.fire(alertname, service, severity, summary);
    }

    @Override
    public void sendOrders(int count, int intervalSeconds, long customerId, String sku, String customerNote) {
        for (int i = 0; i < count; i++) {
            Map<String, Object> body = new HashMap<>();
            body.put("customerId", customerId);
            body.put("items", List.of(Map.of("sku", sku, "qty", 1)));
            body.put("customerNote", customerNote);
            try {
                gateway.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON).body(body)
                        .exchange((req, res) -> res.getStatusCode().value());
            } catch (RuntimeException e) {
                log.debug("order {} of {} failed: {}", i + 1, count, e.toString());
            }
            if (i < count - 1) {
                sleep(intervalSeconds);
            }
        }
    }

    @Override
    public void sleep(int seconds) {
        sleepMillis(seconds * 1000L);
    }

    /** Pushes the stored switches to a running service (a restarted one fetches them itself). */
    private void push(String service) {
        try {
            services.pushSwitches(service, switches.forService(service));
        } catch (RuntimeException e) {
            log.warn("could not push switches to {} ({}); it will receive them when it registers", service, e.toString());
        }
    }

    private boolean healthy(String service) {
        try {
            return services.version(service) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
