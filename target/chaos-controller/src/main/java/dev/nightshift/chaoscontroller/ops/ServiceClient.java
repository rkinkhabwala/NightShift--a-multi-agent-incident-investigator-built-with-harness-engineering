package dev.nightshift.chaoscontroller.ops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Calls the admin/internal endpoints of the target services. */
@Component
public class ServiceClient {

    private final RestClient client;
    private final ReleaseManifest manifest;
    private final String hostSuffix;

    public ServiceClient(ReleaseManifest manifest, @Value("${nightshift.service-host-suffix:}") String hostSuffix) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(10000);
        this.client = RestClient.builder().requestFactory(factory).build();
        this.manifest = manifest;
        this.hostSuffix = hostSuffix;
    }

    /** Base URL of a service, e.g. http://payments:8082. Overridable for tests. */
    public String baseUrl(String service) {
        return "http://" + service + hostSuffix + ":" + manifest.service(service).port();
    }

    public void restart(String service) {
        client.post().uri(baseUrl(service) + "/admin/restart").retrieve().toBodilessEntity();
    }

    public void pushSwitches(String service, Map<String, String> switches) {
        client.put().uri(baseUrl(service) + "/internal/switches").contentType(MediaType.APPLICATION_JSON)
                .body(switches).retrieve().toBodilessEntity();
    }

    /** kind is "config" or "flags". */
    public Map<?, ?> changeSetting(String service, String kind, String key, String value, String changedBy, String reason) {
        return client.put().uri(baseUrl(service) + "/admin/" + kind + "/" + key).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("value", value, "changedBy", changedBy, "reason", reason == null ? "" : reason))
                .retrieve().body(Map.class);
    }

    public Map<?, ?> settings(String service, String kind) {
        return client.get().uri(baseUrl(service) + "/admin/" + kind).retrieve().body(Map.class);
    }

    public java.util.List<?> history(String service, String kind, String since) {
        return client.get().uri(baseUrl(service) + "/admin/" + kind + "/history?since={since}&limit=200", since)
                .retrieve().body(java.util.List.class);
    }

    public java.util.List<?> definitions(String service, String kind) {
        return client.get().uri(baseUrl(service) + "/admin/" + kind + "/definitions").retrieve().body(java.util.List.class);
    }

    public Map<?, ?> version(String service) {
        return client.get().uri(baseUrl(service) + "/admin/version").retrieve().body(Map.class);
    }
}
