package dev.nightshift.chaoscontroller.chaos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal client for the Toxiproxy HTTP API (port 8474). */
@Component
public class ToxiproxyClient {

    private final RestClient client;

    public ToxiproxyClient(@Value("${nightshift.toxiproxy-url:http://toxiproxy:8474}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** Adds a toxic, replacing an existing one of the same name (so inject is repeatable). */
    public void addToxic(String proxy, String name, String type, String stream, Map<String, Object> attributes) {
        removeToxic(proxy, name);
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("type", type);
        body.put("stream", stream == null ? "downstream" : stream);
        body.put("toxicity", 1.0);
        body.put("attributes", attributes == null ? Map.of() : attributes);
        client.post().uri("/proxies/{p}/toxics", proxy).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toBodilessEntity();
    }

    /** Removes a toxic; a missing toxic is not an error. */
    public void removeToxic(String proxy, String name) {
        try {
            client.delete().uri("/proxies/{p}/toxics/{t}", proxy, name).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw e;
            }
        }
    }

    public void setEnabled(String proxy, boolean enabled) {
        client.patch().uri("/proxies/{p}", proxy).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", enabled)).retrieve().toBodilessEntity();
    }

    /** Enables all proxies and removes all toxics. */
    public void reset() {
        client.post().uri("/reset").retrieve().toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> proxy(String proxy) {
        return client.get().uri("/proxies/{p}", proxy).retrieve().body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> toxics(String proxy) {
        return client.get().uri("/proxies/{p}/toxics", proxy).retrieve().body(List.class);
    }
}
