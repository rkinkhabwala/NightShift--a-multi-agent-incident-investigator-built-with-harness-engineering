package dev.nightshift.chaoscontroller.chaos;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Chaos API. Calls are synchronous: inject returns once the fault is in place (deploys included),
 * reset once the system is back at baseline. Not reachable by NightShift agents.
 */
@RestController
@RequestMapping("/chaos")
public class ChaosController {

    private final ScenarioLoader scenarios;
    private final ScenarioRunner runner;

    public ChaosController(ScenarioLoader scenarios, ScenarioRunner runner) {
        this.scenarios = scenarios;
        this.runner = runner;
    }

    @GetMapping("/scenarios")
    public List<Map<String, Object>> list() {
        return scenarios.all().values().stream()
                .map(s -> Map.<String, Object>of("id", s.id(), "title", s.title(), "fault", s.fault()))
                .toList();
    }

    @GetMapping("/scenarios/{id}")
    public Scenario get(@PathVariable String id) {
        return scenarios.get(id);
    }

    @PostMapping("/scenarios/{id}/inject")
    public ResponseEntity<ScenarioRunner.Status> inject(@PathVariable String id) {
        ScenarioRunner.Status st = runner.inject(id);
        return ResponseEntity.status(st.phase() == ScenarioRunner.Phase.FAILED ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.OK).body(st);
    }

    @PostMapping("/scenarios/{id}/reset")
    public ResponseEntity<ScenarioRunner.Status> reset(@PathVariable String id) {
        ScenarioRunner.Status st = runner.reset(id);
        return ResponseEntity.status(st.phase() == ScenarioRunner.Phase.FAILED ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.OK).body(st);
    }

    @PostMapping("/reset-all")
    public ResponseEntity<ScenarioRunner.Status> resetAll() {
        ScenarioRunner.Status st = runner.resetAll();
        return ResponseEntity.status(st.phase() == ScenarioRunner.Phase.FAILED ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.OK).body(st);
    }

    @GetMapping("/status")
    public ScenarioRunner.Status status() {
        return runner.status();
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ScenarioRunner.ConflictException.class)
    ResponseEntity<Map<String, String>> conflict(ScenarioRunner.ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
