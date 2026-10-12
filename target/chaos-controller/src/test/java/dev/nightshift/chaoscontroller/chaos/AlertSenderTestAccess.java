package dev.nightshift.chaoscontroller.chaos;

import java.time.Instant;
import java.util.Map;

/** Exposes the package-private payload builder to tests in other packages. */
public final class AlertSenderTestAccess {

    private AlertSenderTestAccess() {
    }

    public static Map<String, Object> payload(String alertname, String service, String severity, String summary, Instant now) {
        return AlertSender.payload(alertname, service, severity, summary, now);
    }
}
