package dev.nightshift.chaoscontroller.chaos;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.nightshift.chaoscontroller.ops.ReleaseManifest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Loads and validates evals/scenarios/*.yaml. Invalid files fail loudly at startup. */
@Component
public class ScenarioLoader {

    static final Set<String> PROXIES = Set.of("redis", "pg-payments", "pg-inventory");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Map<String, Scenario> scenarios;

    public ScenarioLoader(@Value("${nightshift.scenarios-dir:/scenarios}") String dir, ReleaseManifest manifest) {
        this.scenarios = load(Path.of(dir), manifest);
    }

    public Map<String, Scenario> all() {
        return scenarios;
    }

    public Scenario get(String id) {
        Scenario s = scenarios.get(id.toUpperCase());
        if (s == null) {
            throw new NoSuchElementException("unknown scenario " + id + " (known: " + scenarios.keySet() + ")");
        }
        return s;
    }

    static Map<String, Scenario> load(Path dir, ReleaseManifest manifest) {
        Map<String, Scenario> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                Scenario s;
                try {
                    s = YAML.readValue(f.toFile(), Scenario.class);
                } catch (IOException e) {
                    throw new IllegalStateException("cannot parse " + f.getFileName() + ": " + e.getMessage(), e);
                }
                List<String> problems = validate(s, f.getFileName().toString(), manifest);
                if (!problems.isEmpty()) {
                    throw new IllegalStateException("invalid scenario " + f.getFileName() + ": " + problems);
                }
                out.put(s.id(), s);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read scenarios from " + dir, e);
        }
        return out;
    }

    static List<String> validate(Scenario s, String fileName, ReleaseManifest manifest) {
        List<String> p = new ArrayList<>();
        if (s.id() == null || !fileName.equals(s.id() + ".yaml")) p.add("id must match the file name");
        if (s.title() == null) p.add("title is required");
        if (s.fault() == null) p.add("fault is required");
        if (s.inject() == null || s.inject().isEmpty()) p.add("inject must have at least one step");
        if (s.reset() == null) p.add("reset is required (may be empty)");
        if (s.expectedRootCause() == null || s.expectedRootCause().get("summary") == null) p.add("expected_root_cause.summary is required");
        if (s.acceptableRemediations() == null) p.add("acceptable_remediations is required (may be empty)");
        if (s.redHerrings() == null) p.add("red_herrings is required (may be empty)");
        if (s.verify() == null) p.add("verify is required");
        List<Step> steps = new ArrayList<>();
        if (s.inject() != null) steps.addAll(s.inject());
        if (s.reset() != null) steps.addAll(s.reset());
        for (Step step : steps) {
            if (step.actions().size() != 1) {
                p.add("each step needs exactly one action, got " + step.actions());
                continue;
            }
            checkStep(step, manifest, p);
        }
        return p;
    }

    private static void checkStep(Step st, ReleaseManifest m, List<String> p) {
        try {
            if (st.deploy() != null) m.changelog(st.deploy().service(), st.deploy().version());
            if (st.config() != null) m.service(st.config().service());
            if (st.flag() != null) m.service(st.flag().service());
            if (st.switchOn() != null) m.service(st.switchOn().service());
            if (st.switchClear() != null) m.service(st.switchClear().service());
            if (st.restart() != null) m.service(st.restart().service());
        } catch (RuntimeException e) {
            p.add(e.getMessage());
        }
        if (st.toxic() != null && (!PROXIES.contains(st.toxic().proxy()) || st.toxic().name() == null || st.toxic().type() == null)) {
            p.add("toxic needs a known proxy, a name and a type: " + st.toxic());
        }
        if (st.toxicRemove() != null && !PROXIES.contains(st.toxicRemove().proxy())) p.add("unknown proxy " + st.toxicRemove().proxy());
        if (st.proxy() != null && (!PROXIES.contains(st.proxy().name()) || st.proxy().enabled() == null)) p.add("proxy needs a known name and enabled");
        if (st.waitSeconds() != null && (st.waitSeconds() < 0 || st.waitSeconds() > 600)) p.add("wait must be 0..600 s");
        if (st.config() != null && (st.config().key() == null || st.config().value() == null)) p.add("config needs key and value");
        if (st.flag() != null && (st.flag().key() == null || st.flag().value() == null)) p.add("flag needs key and value");
    }
}
