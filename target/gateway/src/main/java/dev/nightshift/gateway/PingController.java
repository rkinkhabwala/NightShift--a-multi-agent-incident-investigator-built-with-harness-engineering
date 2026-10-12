package dev.nightshift.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Liveness probe for humans and smoke tests: one request = one span, one log line. */
@RestController
class PingController {

    private static final Logger log = LoggerFactory.getLogger(PingController.class);

    @GetMapping("/api/ping")
    Map<String, String> ping() {
        log.info("ping received");
        return Map.of("status", "ok");
    }
}
