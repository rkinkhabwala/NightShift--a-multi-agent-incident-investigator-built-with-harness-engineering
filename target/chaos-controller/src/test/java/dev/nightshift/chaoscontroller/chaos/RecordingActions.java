package dev.nightshift.chaoscontroller.chaos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Records every action as a readable string instead of touching the system. */
class RecordingActions implements ChaosActions {

    final List<String> calls = new ArrayList<>();
    String failOn;

    private void record(String call) {
        calls.add(call);
        if (failOn != null && call.startsWith(failOn)) {
            throw new IllegalStateException("boom: " + call);
        }
    }

    @Override public void deploy(String service, String version, String by) { record("deploy " + service + " " + version + " by " + by); }
    @Override public void changeSetting(String service, String kind, String key, String value, String by, String reason) { record(kind + " " + service + " " + key + "=" + value + " by " + by); }
    @Override public void addToxic(String proxy, String name, String type, String stream, Map<String, Object> attrs) { record("toxic " + proxy + " " + name + " " + type + " " + attrs); }
    @Override public void removeToxic(String proxy, String name) { record("toxic_remove " + proxy + " " + name); }
    @Override public void setProxyEnabled(String proxy, boolean enabled) { record("proxy " + proxy + " enabled=" + enabled); }
    @Override public void setSwitch(String service, String name, String value, String scenario) { record("switch " + service + " " + name + "=" + value + " (" + scenario + ")"); }
    @Override public void clearSwitch(String service, String name) { record("switch_clear " + service + " " + name); }
    @Override public void restart(String service) { record("restart " + service); }
    @Override public void fireAlert(String alertname, String service, String severity, String summary) { record("alert " + alertname + " " + service + " " + severity); }
    @Override public void sendOrders(int count, int interval, long customerId, String sku, String note) { record("orders x" + count + " every " + interval + "s customer " + customerId + " note=" + (note == null ? null : note.substring(0, 30))); }
    @Override public void sleep(int seconds) { record("wait " + seconds); }
}
