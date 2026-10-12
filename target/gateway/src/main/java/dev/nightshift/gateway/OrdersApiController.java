package dev.nightshift.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nightshift.common.http.DownstreamResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public API. Passes client errors through; any upstream failure becomes 502/504. */
@RestController
@RequestMapping("/api/orders")
public class OrdersApiController {

    private static final Logger log = LoggerFactory.getLogger(OrdersApiController.class);

    private final OrdersProxy orders;
    private final SigningKeys keys;

    public OrdersApiController(OrdersProxy orders, SigningKeys keys) {
        this.orders = orders;
        this.keys = keys;
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody JsonNode body) {
        keys.currentKeyId();
        return relay("POST /orders", orders.create(body));
    }

    @GetMapping("/{id}")
    public ResponseEntity<String> get(@PathVariable long id) {
        keys.currentKeyId();
        return relay("GET /orders/{id}", orders.get(id));
    }

    private static ResponseEntity<String> relay(String call, DownstreamResult result) {
        if (result.failure() == DownstreamResult.Failure.NONE && result.status() < 500) {
            return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
        }
        log.error("upstream orders failed for {}: {}", call, result.describe());
        HttpStatus status = result.failure() == DownstreamResult.Failure.TIMEOUT ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"upstream_error\",\"upstream\":\"orders\",\"detail\":\"" + result.describe().replace("\"", "'") + "\"}");
    }
}
