package dev.nightshift.orders;

import dev.nightshift.common.http.DownstreamClient;
import dev.nightshift.common.http.DownstreamResult;
import dev.nightshift.common.settings.RuntimeSettings;
import dev.nightshift.common.settings.SettingChange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentsClient {

    static final String TIMEOUT_KEY = "orders.payments-client.timeout-ms";

    private final DownstreamClient client;

    public PaymentsClient(@Value("${nightshift.payments-url:http://payments:8082}") String baseUrl,
                          RuntimeSettings settings) {
        this.client = new DownstreamClient(baseUrl, Duration.ofSeconds(1), Duration.ofMillis(settings.configInt(TIMEOUT_KEY)));
    }

    @EventListener
    public void onSettingChange(SettingChange change) {
        if (TIMEOUT_KEY.equals(change.key())) {
            client.setReadTimeout(Duration.ofMillis(Long.parseLong(change.newValue())));
        }
    }

    public DownstreamResult charge(long orderId, OrderRepository.Customer customer, long amountCents,
                                   List<CreateOrderRequest.Item> items) {
        Map<String, Object> body = new HashMap<>();
        body.put("orderId", orderId);
        body.put("customerId", customer.id());
        body.put("loyaltyTier", customer.loyaltyTier());
        body.put("amountCents", amountCents);
        body.put("items", items);
        return client.post("/charges", body);
    }
}
