package dev.nightshift.chaoscontroller.chaos;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One inject/reset step. Exactly one field is set; the YAML key names the action, e.g.
 * {@code - deploy: {service: payments, version: "1.4"}}.
 */
public record Step(
        Deploy deploy,
        Setting config,
        Setting flag,
        Toxic toxic,
        ToxicRemove toxicRemove,
        Proxy proxy,
        @JsonProperty("switch") Switch switchOn,
        SwitchClear switchClear,
        Restart restart,
        @JsonProperty("wait") Integer waitSeconds,
        FireAlert fireAlert,
        SendOrders sendOrders) {

    public record Deploy(String service, String version, String initiatedBy) {
    }

    public record Setting(String service, String key, String value, String changedBy, String reason) {
    }

    public record Toxic(String proxy, String name, String type, String stream, Map<String, Object> attributes) {
    }

    public record ToxicRemove(String proxy, String name) {
    }

    public record Proxy(String name, Boolean enabled) {
    }

    public record Switch(String service, String name, String value) {
    }

    public record SwitchClear(String service, String name) {
    }

    public record Restart(String service) {
    }

    public record FireAlert(String alertname, String service, String severity, String summary) {
    }

    public record SendOrders(Integer count, Integer intervalSeconds, Long customerId, String sku, String customerNote) {
    }

    /** Names of the actions that are set; a valid step has exactly one. */
    public List<String> actions() {
        List<String> out = new ArrayList<>();
        if (deploy != null) out.add("deploy");
        if (config != null) out.add("config");
        if (flag != null) out.add("flag");
        if (toxic != null) out.add("toxic");
        if (toxicRemove != null) out.add("toxic_remove");
        if (proxy != null) out.add("proxy");
        if (switchOn != null) out.add("switch");
        if (switchClear != null) out.add("switch_clear");
        if (restart != null) out.add("restart");
        if (waitSeconds != null) out.add("wait");
        if (fireAlert != null) out.add("fire_alert");
        if (sendOrders != null) out.add("send_orders");
        return out;
    }

    public String describe() {
        List<String> a = actions();
        return a.size() == 1 ? a.get(0) : a.toString();
    }
}
