package dev.nightshift.common.release;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The service name and the release version this process was started with. The version is set
 * by the container entrypoint from the releases volume, so a deploy is a real restart.
 */
@Component
public class ReleaseInfo {

    private final String service;
    private final String version;

    public ReleaseInfo(@Value("${spring.application.name}") String service,
                       @Value("${APP_VERSION:0.0.0}") String version) {
        this.service = service;
        this.version = version;
    }

    public String service() {
        return service;
    }

    public String version() {
        return version;
    }

    public boolean isAtLeast(String other) {
        return compare(version, other) >= 0;
    }

    /** Compares dotted numeric versions ("1.10" > "1.4"); missing parts count as 0. */
    public static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? parse(x[i]) : 0;
            int yi = i < y.length ? parse(y[i]) : 0;
            if (xi != yi) {
                return Integer.compare(xi, yi);
            }
        }
        return 0;
    }

    private static int parse(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
