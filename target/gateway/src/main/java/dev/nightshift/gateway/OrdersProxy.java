package dev.nightshift.gateway;

import dev.nightshift.common.http.DownstreamClient;
import dev.nightshift.common.http.DownstreamResult;
import dev.nightshift.common.settings.RuntimeSettings;
import dev.nightshift.common.settings.SettingChange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OrdersProxy {

    static final String TIMEOUT_KEY = "gateway.orders-client.timeout-ms";

    private final DownstreamClient client;

    public OrdersProxy(@Value("${nightshift.orders-url:http://orders:8081}") String baseUrl, RuntimeSettings settings) {
        this.client = new DownstreamClient(baseUrl, Duration.ofSeconds(1), Duration.ofMillis(settings.configInt(TIMEOUT_KEY)));
    }

    @EventListener
    public void onSettingChange(SettingChange change) {
        if (TIMEOUT_KEY.equals(change.key())) {
            client.setReadTimeout(Duration.ofMillis(Long.parseLong(change.newValue())));
        }
    }

    public DownstreamResult create(Object body) {
        return client.post("/orders", body);
    }

    public DownstreamResult get(long id) {
        return client.get("/orders/{id}", id);
    }
}
