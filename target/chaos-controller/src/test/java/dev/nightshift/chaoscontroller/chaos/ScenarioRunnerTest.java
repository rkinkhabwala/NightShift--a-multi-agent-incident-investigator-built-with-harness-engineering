package dev.nightshift.chaoscontroller.chaos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioRunnerTest {

    RecordingActions actions;
    BaselineRestorer baseline;
    ScenarioRunner runner;

    @BeforeEach
    void setUp() {
        actions = new RecordingActions();
        baseline = mock(BaselineRestorer.class);
        when(baseline.restore()).thenReturn(List.of());
        ScenarioLoader loader = mock(ScenarioLoader.class);
        ScenarioFilesTest.ALL.forEach((id, s) -> when(loader.get(id)).thenReturn(s));
        runner = new ScenarioRunner(loader, new StepExecutor(actions), baseline);
    }

    @Test
    void injectThenResetReturnsToIdle() {
        assertThat(runner.inject("S06").phase()).isEqualTo(ScenarioRunner.Phase.ACTIVE);
        assertThat(runner.status().scenario()).isEqualTo("S06");
        ScenarioRunner.Status st = runner.reset("S06");
        assertThat(st.phase()).isEqualTo(ScenarioRunner.Phase.IDLE);
        assertThat(st.scenario()).isNull();
        assertThat(actions.calls).containsExactly("proxy pg-payments enabled=false", "proxy pg-payments enabled=true");
        verify(baseline, times(1)).restore();
    }

    @Test
    void anotherScenarioCannotBeInjectedWhileOneIsActive() {
        runner.inject("S02");
        assertThatThrownBy(() -> runner.inject("S05")).isInstanceOf(ScenarioRunner.ConflictException.class);
        runner.reset("S02");
        assertThat(runner.inject("S05").phase()).isEqualTo(ScenarioRunner.Phase.ACTIVE);
    }

    @Test
    void failedInjectStopsAndIsReported() {
        actions.failOn = "wait";
        ScenarioRunner.Status st = runner.inject("S07");
        assertThat(st.phase()).isEqualTo(ScenarioRunner.Phase.FAILED);
        assertThat(actions.calls).doesNotContain("config payments payments.inventory-client.timeout-ms=5 by sam.dev");
        assertThat(st.steps()).last().satisfies(r -> assertThat(r.ok()).isFalse());
    }

    @Test
    void resetStepsAreBestEffortAndBaselineStillRuns() {
        runner.inject("S08");
        actions.failOn = "toxic_remove";
        ScenarioRunner.Status st = runner.reset("S08");
        assertThat(actions.calls).contains("switch_clear gateway gateway.jwks-refresh-skipped");
        assertThat(st.phase()).isEqualTo(ScenarioRunner.Phase.IDLE);
    }

    @Test
    void alertPayloadIsAlertmanagerShaped() {
        var p = AlertSender.payload("HighErrorRate", "gateway", "page", "s", java.time.Instant.parse("2026-10-12T10:00:00Z"));
        assertThat(p).containsEntry("version", "4").containsEntry("status", "firing");
        assertThat((List<?>) p.get("alerts")).hasSize(1);
    }
}
