package dev.nightshift.chaoscontroller.chaos;

import java.util.Map;

/** Everything a scenario step can do. Live implementation: {@link LiveChaosActions}. */
public interface ChaosActions {

    /** Releases a version and waits until the service runs it. */
    void deploy(String service, String version, String initiatedBy);

    /** kind is "config" or "flags". */
    void changeSetting(String service, String kind, String key, String value, String changedBy, String reason);

    void addToxic(String proxy, String name, String type, String stream, Map<String, Object> attributes);

    void removeToxic(String proxy, String name);

    void setProxyEnabled(String proxy, boolean enabled);

    void setSwitch(String service, String name, String value, String scenario);

    void clearSwitch(String service, String name);

    void restart(String service);

    void fireAlert(String alertname, String service, String severity, String summary);

    void sendOrders(int count, int intervalSeconds, long customerId, String sku, String customerNote);

    void sleep(int seconds);
}
