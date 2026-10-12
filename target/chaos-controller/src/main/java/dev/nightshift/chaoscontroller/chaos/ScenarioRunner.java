package dev.nightshift.chaoscontroller.chaos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Injects and resets scenarios, one at a time, and remembers what happened. */
@Service
public class ScenarioRunner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);

    public enum Phase { IDLE, INJECTING, ACTIVE, RESETTING, FAILED }

    public record StepResult(String phase, String action, boolean ok, String error, Instant at) {
    }

    public record Status(String scenario, Phase phase, Instant injectedAt, Instant resetAt, List<StepResult> steps) {
    }

    /** Thrown when another scenario is still active. */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    private final ScenarioLoader scenarios;
    private final StepExecutor executor;
    private final BaselineRestorer baseline;

    private String active;
    private Phase phase = Phase.IDLE;
    private Instant injectedAt;
    private Instant resetAt;
    private final List<StepResult> steps = new ArrayList<>();

    public ScenarioRunner(ScenarioLoader scenarios, StepExecutor executor, BaselineRestorer baseline) {
        this.scenarios = scenarios;
        this.executor = executor;
        this.baseline = baseline;
    }

    public synchronized Status status() {
        return new Status(active, phase, injectedAt, resetAt, List.copyOf(steps));
    }

    /** Runs the inject steps in order; stops at the first failure (phase FAILED). */
    public synchronized Status inject(String id) {
        Scenario s = scenarios.get(id);
        if (active != null && phase != Phase.IDLE && !active.equals(s.id())) {
            throw new ConflictException("scenario " + active + " is " + phase + "; reset it first");
        }
        active = s.id();
        phase = Phase.INJECTING;
        injectedAt = Instant.now();
        resetAt = null;
        steps.clear();
        log.info("injecting {} ({} steps)", s.id(), s.inject().size());
        for (Step step : s.inject()) {
            if (!run("inject", step, s.id(), true)) {
                phase = Phase.FAILED;
                return status();
            }
        }
        phase = Phase.ACTIVE;
        return status();
    }

    /** Runs the scenario's reset steps (best effort), then restores the full baseline. */
    public synchronized Status reset(String id) {
        Scenario s = scenarios.get(id);
        if (active == null) {
            active = s.id();
        }
        phase = Phase.RESETTING;
        log.info("resetting {}", s.id());
        for (Step step : s.reset()) {
            run("reset", step, s.id(), false);
        }
        restoreBaseline();
        return status();
    }

    public synchronized Status resetAll() {
        phase = Phase.RESETTING;
        restoreBaseline();
        return status();
    }

    private void restoreBaseline() {
        try {
            for (String action : baseline.restore()) {
                steps.add(new StepResult("baseline", action, !action.startsWith("FAILED"), null, Instant.now()));
            }
            phase = Phase.IDLE;
            active = null;
            resetAt = Instant.now();
        } catch (RuntimeException e) {
            steps.add(new StepResult("baseline", "restore", false, e.toString(), Instant.now()));
            phase = Phase.FAILED;
        }
    }

    private boolean run(String stage, Step step, String scenarioId, boolean stopOnError) {
        try {
            executor.execute(step, scenarioId);
            steps.add(new StepResult(stage, step.describe(), true, null, Instant.now()));
            return true;
        } catch (RuntimeException e) {
            log.warn("{} step {} of {} failed: {}", stage, step.describe(), scenarioId, e.toString());
            steps.add(new StepResult(stage, step.describe(), false, e.toString(), Instant.now()));
            return !stopOnError;
        }
    }
}
