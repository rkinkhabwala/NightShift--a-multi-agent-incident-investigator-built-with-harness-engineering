package dev.nightshift.chaoscontroller.chaos;

import org.springframework.stereotype.Component;

/** Translates one YAML step into a {@link ChaosActions} call. */
@Component
public class StepExecutor {

    private final ChaosActions actions;

    public StepExecutor(ChaosActions actions) {
        this.actions = actions;
    }

    public void execute(Step s, String scenarioId) {
        if (s.deploy() != null) {
            actions.deploy(s.deploy().service(), s.deploy().version(), orDefault(s.deploy().initiatedBy(), "release-bot"));
        } else if (s.config() != null) {
            Step.Setting c = s.config();
            actions.changeSetting(c.service(), "config", c.key(), c.value(), orDefault(c.changedBy(), "ops"), c.reason());
        } else if (s.flag() != null) {
            Step.Setting f = s.flag();
            actions.changeSetting(f.service(), "flags", f.key(), f.value(), orDefault(f.changedBy(), "ops"), f.reason());
        } else if (s.toxic() != null) {
            Step.Toxic t = s.toxic();
            actions.addToxic(t.proxy(), t.name(), t.type(), t.stream(), t.attributes());
        } else if (s.toxicRemove() != null) {
            actions.removeToxic(s.toxicRemove().proxy(), s.toxicRemove().name());
        } else if (s.proxy() != null) {
            actions.setProxyEnabled(s.proxy().name(), s.proxy().enabled());
        } else if (s.switchOn() != null) {
            actions.setSwitch(s.switchOn().service(), s.switchOn().name(), s.switchOn().value(), scenarioId);
        } else if (s.switchClear() != null) {
            actions.clearSwitch(s.switchClear().service(), s.switchClear().name());
        } else if (s.restart() != null) {
            actions.restart(s.restart().service());
        } else if (s.waitSeconds() != null) {
            actions.sleep(s.waitSeconds());
        } else if (s.fireAlert() != null) {
            Step.FireAlert a = s.fireAlert();
            actions.fireAlert(a.alertname(), a.service(), orDefault(a.severity(), "page"), orDefault(a.summary(), a.alertname()));
        } else if (s.sendOrders() != null) {
            Step.SendOrders o = s.sendOrders();
            actions.sendOrders(o.count() == null ? 1 : o.count(), o.intervalSeconds() == null ? 0 : o.intervalSeconds(),
                    o.customerId() == null ? 1 : o.customerId(), orDefault(o.sku(), "SKU-001"), o.customerNote());
        } else {
            throw new IllegalArgumentException("step has no action");
        }
    }

    private static String orDefault(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
