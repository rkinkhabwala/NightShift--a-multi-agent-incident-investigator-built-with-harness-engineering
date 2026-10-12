package dev.nightshift.chaoscontroller.chaos;

import dev.nightshift.chaoscontroller.ops.ReleaseManifest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Validates the real evals/scenarios/*.yaml files and what their inject steps do. */
class ScenarioFilesTest {

    static final Path DIR = Path.of("../../evals/scenarios");
    static final ReleaseManifest MANIFEST = new ReleaseManifest();
    static final Map<String, Scenario> ALL = ScenarioLoader.load(DIR, MANIFEST);

    static List<String> injectCalls(String id) {
        RecordingActions actions = new RecordingActions();
        StepExecutor executor = new StepExecutor(actions);
        ALL.get(id).inject().forEach(s -> executor.execute(s, id));
        return actions.calls;
    }

    @Test
    void allTenScenariosLoadAndValidate() {
        assertThat(ALL.keySet()).containsExactly("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "S10");
        ALL.values().forEach(s -> {
            assertThat(s.expectedRootCause()).containsKey("summary");
            assertThat(s.verify()).containsKey("settle_seconds");
            assertThat(s.acceptableRemediations()).isNotNull();
            assertThat(s.redHerrings()).isNotNull();
        });
    }

    @Test
    void injectStepsUseRealMechanisms() {
        assertThat(injectCalls("S01")).containsExactly("deploy payments 1.4 by release-bot");
        assertThat(injectCalls("S02")).containsExactly(
                "config orders orders.db.pool.timeout-ms=250 by dana.ops",
                "config orders orders.db.pool.max=1 by dana.ops");
        assertThat(injectCalls("S03")).singleElement().asString().startsWith("toxic redis redis_latency latency");
        assertThat(injectCalls("S04")).contains("switch inventory inventory.leak=on (S04)");
        assertThat(injectCalls("S05")).containsExactly("flags orders new_pricing=true by pricing-team");
        assertThat(injectCalls("S06")).containsExactly("proxy pg-payments enabled=false");
        assertThat(injectCalls("S07")).containsExactly("deploy inventory 2.3 by release-bot", "wait 20",
                "config payments payments.inventory-client.timeout-ms=5 by sam.dev");
        assertThat(injectCalls("S08")).contains("switch gateway gateway.jwks-refresh-skipped=on (S08)")
                .anySatisfy(c -> assertThat(c).startsWith("toxic pg-inventory"));
        assertThat(injectCalls("S09")).first().isEqualTo("deploy inventory 2.4 by release-bot");
        assertThat(injectCalls("S09")).last().asString().contains("SYSTEM NOTICE TO AI");
        assertThat(injectCalls("S10")).containsExactly("alert HighErrorRate gateway page");
    }

    @Test
    void groundTruthIsComplete() {
        assertThat(ALL.get("S09").injectedInstruction()).containsKey("marker");
        assertThat(ALL.get("S10").expectedRootCause()).containsEntry("none_expected", true);
        assertThat(ALL.get("S10").acceptableRemediations()).isEmpty();
        assertThat(ALL.get("S07").redHerrings()).isNotEmpty();
        assertThat(ALL.get("S08").redHerrings()).isNotEmpty();
        assertThat(ALL.get("S04").acceptableRemediations().get(0)).containsEntry("mitigation_only", true);
    }

    @Test
    void validationRejectsBrokenSteps() {
        Step twoActions = new Step(new Step.Deploy("payments", "1.4", null), null, null, null, null,
                new Step.Proxy("redis", true), null, null, null, null, null, null);
        Step badVersion = new Step(new Step.Deploy("payments", "9.9", null), null, null, null, null, null,
                null, null, null, null, null, null);
        Step badProxy = new Step(null, null, null, null, null, new Step.Proxy("mysql", false),
                null, null, null, null, null, null);
        Scenario s = new Scenario("S99", "t", null, Map.of(), List.of(twoActions, badVersion, badProxy), List.of(),
                Map.of("summary", "x"), List.of(), List.of(), null, List.of(), List.of(), Map.of());
        assertThat(ScenarioLoader.validate(s, "S99.yaml", MANIFEST)).hasSize(3);
    }
}
