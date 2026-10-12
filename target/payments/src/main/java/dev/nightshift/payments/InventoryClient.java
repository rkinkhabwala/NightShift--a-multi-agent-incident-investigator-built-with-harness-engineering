package dev.nightshift.payments;

import dev.nightshift.common.http.DownstreamClient;
import dev.nightshift.common.http.DownstreamResult;
import dev.nightshift.common.settings.RuntimeSettings;
import dev.nightshift.common.settings.SettingChange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
public class InventoryClient {

    static final String TIMEOUT_KEY = "payments.inventory-client.timeout-ms";

    private final DownstreamClient client;

    public InventoryClient(@Value("${nightshift.inventory-url:http://inventory:8083}") String baseUrl,
                           RuntimeSettings settings) {
        this.client = new DownstreamClient(baseUrl, Duration.ofSeconds(1),
                Duration.ofMillis(settings.configInt(TIMEOUT_KEY)));
    }

    @EventListener
    public void onSettingChange(SettingChange change) {
        if (TIMEOUT_KEY.equals(change.key())) {
            client.setReadTimeout(Duration.ofMillis(Long.parseLong(change.newValue())));
        }
    }

    public DownstreamResult reserve(ChargeRequest request) {
        return client.post("/reservations", Map.of("orderId", request.orderId(), "items", request.items()));
    }
}
