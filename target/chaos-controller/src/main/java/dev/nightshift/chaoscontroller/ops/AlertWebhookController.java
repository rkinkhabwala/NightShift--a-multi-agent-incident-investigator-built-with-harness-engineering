package dev.nightshift.chaoscontroller.ops;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Stub alert receiver (Alertmanager webhook format). Logs each alert and keeps the last 100 in
 * memory. In Phase 2 Alertmanager points at the NightShift API instead.
 */
@RestController
@RequestMapping("/ops/alerts")
public class AlertWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookController.class);
    static final int KEEP = 100;

    public record ReceivedAlert(Instant receivedAt, String status, String alertname, String service,
                                String severity, String summary, String startsAt) {
    }

    private final Deque<ReceivedAlert> alerts = new ArrayDeque<>();

    @PostMapping
    public Map<String, Object> receive(@RequestBody JsonNode payload) {
        int n = 0;
        for (JsonNode a : payload.path("alerts")) {
            ReceivedAlert r = new ReceivedAlert(Instant.now(), a.path("status").asText(payload.path("status").asText()),
                    a.path("labels").path("alertname").asText(), a.path("labels").path("service_name").asText(),
                    a.path("labels").path("severity").asText(), a.path("annotations").path("summary").asText(),
                    a.path("startsAt").asText());
            log.atInfo().addKeyValue("alertname", r.alertname()).addKeyValue("alert_status", r.status())
                    .addKeyValue("alert_service", r.service())
                    .log("alert {} {} for {}: {}", r.alertname(), r.status(), r.service(), r.summary());
            synchronized (alerts) {
                alerts.addFirst(r);
                while (alerts.size() > KEEP) {
                    alerts.removeLast();
                }
            }
            n++;
        }
        return Map.of("received", n);
    }

    @GetMapping
    public List<ReceivedAlert> list() {
        synchronized (alerts) {
            return new ArrayList<>(alerts);
        }
    }

    @DeleteMapping
    public Map<String, Object> clear() {
        synchronized (alerts) {
            int n = alerts.size();
            alerts.clear();
            return Map.of("cleared", n);
        }
    }
}
