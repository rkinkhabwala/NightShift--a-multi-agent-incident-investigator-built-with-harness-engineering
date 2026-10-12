package dev.nightshift.common.release;

import dev.nightshift.common.switches.FaultSwitches;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;

/**
 * On startup, tells the deploy registry (chaos controller ops API) which version is running and
 * receives the fault switches that apply to this service. Retries in the background until the
 * registry answers, so services can start in any order.
 */
@Component
public class DeployRegistration {

    private static final Logger log = LoggerFactory.getLogger(DeployRegistration.class);

    record Registration(String service, String version, String instance, Instant startedAt) {
    }

    record RegistrationResponse(Map<String, String> switches) {
    }

    private final ReleaseInfo release;
    private final FaultSwitches switches;
    private final RestClient client;
    private final boolean enabled;
    private final Instant startedAt = Instant.now();

    public DeployRegistration(ReleaseInfo release, FaultSwitches switches,
                              @Value("${nightshift.ops-url:http://chaos-controller:8090}") String opsUrl,
                              @Value("${nightshift.register:true}") boolean enabled) {
        this.release = release;
        this.switches = switches;
        this.enabled = enabled;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        this.client = RestClient.builder().baseUrl(opsUrl).requestFactory(factory).build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            return;
        }
        Thread.ofVirtual().name("deploy-registration").start(this::registerWithRetry);
    }

    void registerWithRetry() {
        Registration body = new Registration(release.service(), release.version(), hostname(), startedAt);
        for (int attempt = 1; attempt <= 60; attempt++) {
            try {
                RegistrationResponse response = client.post().uri("/ops/register")
                        .body(body).retrieve().body(RegistrationResponse.class);
                switches.replace(response == null ? Map.of() : response.switches());
                log.info("registered {} version {} with the deploy registry", release.service(), release.version());
                return;
            } catch (RuntimeException e) {
                log.debug("deploy registry not reachable yet (attempt {}): {}", attempt, e.toString());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.warn("gave up registering with the deploy registry");
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
