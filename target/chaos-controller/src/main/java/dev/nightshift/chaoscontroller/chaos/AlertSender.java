package dev.nightshift.chaoscontroller.chaos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Posts an Alertmanager-format webhook payload to the alert receivers (comma-separated URLs: the
 * stub and the NightShift API). Succeeds if at least one receiver accepts it. Used by S10, where an
 * alert fires without any fault.
 */
@Component
public class AlertSender {

    private final RestClient client;
    private final List<String> receiverUrls;

    public AlertSender(@Value("${nightshift.alert-receiver-url:http://localhost:8090/ops/alerts}") String receiverUrls) {
        org.springframework.http.client.SimpleClientHttpRequestFactory f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(2000);
        f.setReadTimeout(5000);
        this.client = RestClient.builder().requestFactory(f).build();
        this.receiverUrls = java.util.Arrays.stream(receiverUrls.split(",")).map(String::trim).filter(u -> !u.isEmpty()).toList();
    }

    public void fire(String alertname, String service, String severity, String summary) {
        Map<String, Object> body = payload(alertname, service, severity, summary, Instant.now());
        List<String> failures = new java.util.ArrayList<>();
        for (String url : receiverUrls) {
            try {
                client.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
            } catch (RuntimeException e) {
                failures.add(url + ": " + e.getMessage());
            }
        }
        if (failures.size() == receiverUrls.size()) {
            throw new IllegalStateException("no alert receiver accepted the alert: " + failures);
        }
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
