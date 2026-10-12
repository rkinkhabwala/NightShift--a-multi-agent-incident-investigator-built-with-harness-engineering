package dev.nightshift.chaoscontroller.chaos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Posts an Alertmanager-format webhook payload to the alert receiver (the stub now, the
 * NightShift API from Phase 2). Used by S10, where an alert fires without any fault.
 */
@Component
public class AlertSender {

    private final RestClient client;
    private final String receiverUrl;

    public AlertSender(@Value("${nightshift.alert-receiver-url:http://localhost:8090/ops/alerts}") String receiverUrl) {
        this.client = RestClient.create();
        this.receiverUrl = receiverUrl;
    }

    public void fire(String alertname, String service, String severity, String summary) {
        client.post().uri(receiverUrl).contentType(MediaType.APPLICATION_JSON)
                .body(payload(alertname, service, severity, summary, Instant.now()))
                .retrieve().toBodilessEntity();
    }

    static Map<String, Object> payload(String alertname, String service, String severity, String summary, Instant now) {
        Map<String, String> labels = Map.of("alertname", alertname, "service_name", service, "severity", severity);
        Map<String, Object> alert = Map.of(
                "status", "firing",
                "labels", labels,
                "annotations", Map.of("summary", summary),
                "startsAt", now.toString(),
                "endsAt", "0001-01-01T00:00:00Z",
                "generatorURL", "http://localhost:9090/graph",
                "fingerprint", Integer.toHexString((alertname + service).hashCode()));
        return Map.of(
                "version", "4",
                "groupKey", "{}:{alertname=\"" + alertname + "\"}",
                "status", "firing",
                "receiver", "nightshift",
                "groupLabels", Map.of("alertname", alertname),
                "commonLabels", labels,
                "commonAnnotations", Map.of("summary", summary),
                "externalURL", "http://localhost:9093",
                "alerts", List.of(alert));
    }
}
