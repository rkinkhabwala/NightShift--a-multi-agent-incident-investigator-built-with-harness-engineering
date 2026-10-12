package dev.nightshift.common.admin;

import dev.nightshift.common.release.ReleaseInfo;
import dev.nightshift.common.settings.RuntimeSettings;
import dev.nightshift.common.settings.SettingChange;
import dev.nightshift.common.settings.SettingDefinition;
import dev.nightshift.common.settings.SettingKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Config, feature flags (each with change history), version and restart for one service. */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    public record ChangeRequest(String value, String changedBy, String reason) {
    }

    private final RuntimeSettings settings;
    private final ReleaseInfo release;
    private final ApplicationContext context;

    public AdminController(RuntimeSettings settings, ReleaseInfo release, ApplicationContext context) {
        this.settings = settings;
        this.release = release;
        this.context = context;
    }

    @GetMapping("/version")
    public Map<String, String> version() {
        return Map.of("service", release.service(), "version", release.version());
    }

    @GetMapping("/config")
    public Map<String, String> config() {
        return settings.all(SettingKind.CONFIG);
    }

    @GetMapping("/flags")
    public Map<String, String> flags() {
        return settings.all(SettingKind.FLAG);
    }

    @GetMapping("/config/definitions")
    public List<SettingDefinition> configDefinitions() {
        return settings.definitions(SettingKind.CONFIG);
    }

    @GetMapping("/flags/definitions")
    public List<SettingDefinition> flagDefinitions() {
        return settings.definitions(SettingKind.FLAG);
    }

    @PutMapping("/config/{key}")
    public SettingChange changeConfig(@PathVariable String key, @RequestBody ChangeRequest request) {
        return settings.change(SettingKind.CONFIG, key, request.value(), request.changedBy(), request.reason());
    }

    @PutMapping("/flags/{key}")
    public SettingChange changeFlag(@PathVariable String key, @RequestBody ChangeRequest request) {
        return settings.change(SettingKind.FLAG, key, request.value(), request.changedBy(), request.reason());
    }

    @GetMapping("/config/history")
    public List<SettingChange> configHistory(@RequestParam(required = false) Instant since,
                                             @RequestParam(defaultValue = "100") int limit) {
        return settings.history(SettingKind.CONFIG, sinceOrDefault(since), limit);
    }

    @GetMapping("/flags/history")
    public List<SettingChange> flagHistory(@RequestParam(required = false) Instant since,
                                           @RequestParam(defaultValue = "100") int limit) {
        return settings.history(SettingKind.FLAG, sinceOrDefault(since), limit);
    }

    /** Exits the JVM cleanly; the container restart policy starts it again (used by deploys). */
    @PostMapping("/restart")
    public ResponseEntity<Map<String, String>> restart() {
        log.info("restart requested for {} {}", release.service(), release.version());
        Thread.ofPlatform().name("admin-restart").start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.exit(SpringApplication.exit(context, () -> 0));
        });
        return ResponseEntity.accepted().body(Map.of("status", "restarting"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> unknownKey(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidValue(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private static Instant sinceOrDefault(Instant since) {
        return since != null ? since : Instant.now().minus(Duration.ofDays(7));
    }
}
