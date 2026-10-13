package dev.nightshift.chaoscontroller.ops;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Ops API: the deploy registry (read by the Change Investigator later) and service control. */
@RestController
@RequestMapping("/ops")
public class OpsController {

    public record Registration(@NotBlank String service, @NotBlank String version, String instance, @NotNull Instant startedAt) {
    }

    public record DeployRequest(@NotBlank String version, String initiatedBy) {
    }

    private final DeployService deploys;
    private final DeployRegistry registry;
    private final ReleaseManifest manifest;
    private final ServiceClient services;

    public OpsController(DeployService deploys, DeployRegistry registry, ReleaseManifest manifest, ServiceClient services) {
        this.deploys = deploys;
        this.registry = registry;
        this.manifest = manifest;
        this.services = services;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@Valid @RequestBody Registration r) {
        return Map.of("switches", deploys.register(r.service(), r.version(), r.instance() == null ? "unknown" : r.instance(), r.startedAt()));
    }

    @GetMapping("/deploys")
    public List<Deploy> list(@RequestParam(required = false) String service,
                             @RequestParam(required = false) Instant since,
                             @RequestParam(defaultValue = "50") int limit) {
        return registry.list(service, since != null ? since : Instant.now().minus(Duration.ofDays(7)), limit);
    }

    @GetMapping("/deploys/{id}")
    public ResponseEntity<Deploy> get(@PathVariable long id) {
        return registry.find(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/services/{service}/deploys")
    public ResponseEntity<Deploy> deploy(@PathVariable String service, @Valid @RequestBody DeployRequest request) {
        Deploy d = deploys.deploy(service, request.version(), request.initiatedBy() == null ? "ops-api" : request.initiatedBy());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(d);
    }

    @PostMapping("/services/{service}/restart")
    public ResponseEntity<Map<String, String>> restart(@PathVariable String service) {
        manifest.service(service);
        services.restart(service);
        return ResponseEntity.accepted().body(Map.of("service", service, "status", "restarting"));
    }

    /**
     * Read-only view of a service's runtime config or feature flags, with change history. This is
     * how NightShift tools read config/flags without network access to the target services.
     */
    @GetMapping("/services/{service}/{kind:config|flags}")
    public Map<String, Object> settings(@PathVariable String service, @PathVariable String kind,
                                        @RequestParam(required = false) Instant since) {
        manifest.service(service);
        Instant from = since != null ? since : Instant.now().minus(Duration.ofHours(24));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", service);
        out.put("current", services.settings(service, kind));
        out.put("history", services.history(service, kind, from.toString()));
        return out;
    }

    /** Current version, last start and recent restarts per service. */
    @GetMapping("/services")
    public Map<String, Object> services() {
        Map<String, Integer> starts = registry.startsSince(Instant.now().minus(Duration.ofMinutes(10)));
        Map<String, Object> out = new LinkedHashMap<>();
        manifest.services().forEach((name, svc) -> {
            Map<String, Object> s = new LinkedHashMap<>();
            Deploy latest = registry.latest(name).orElse(null);
            s.put("version", latest == null ? null : latest.version());
            s.put("deployStatus", latest == null ? null : latest.status());
            s.put("desiredVersion", deploys.desiredVersion(name));
            s.put("baseline", svc.baseline());
            s.put("lastStart", registry.lastStart(name).orElse(null));
            s.put("startsLast10m", starts.getOrDefault(name, 0));
            out.put(name, s);
        });
        return out;
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
