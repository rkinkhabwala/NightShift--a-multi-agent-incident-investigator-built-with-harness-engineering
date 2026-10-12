package dev.nightshift.chaoscontroller.ops;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persisted hidden fault switches, so they survive restarts of both sides. */
@Repository
public class FaultSwitchStore {

    private final JdbcClient jdbc;

    public FaultSwitchStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, String> forService(String service) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.sql("SELECT name, value FROM ops.fault_switches WHERE service = ? ORDER BY name").param(service)
                .query(rs -> {
                    out.put(rs.getString(1), rs.getString(2));
                });
        return out;
    }

    public void set(String service, String name, String value, String scenario) {
        jdbc.sql("""
                INSERT INTO ops.fault_switches (service, name, value, scenario, updated_at) VALUES (?, ?, ?, ?, now())
                ON CONFLICT (service, name) DO UPDATE SET value = EXCLUDED.value, scenario = EXCLUDED.scenario, updated_at = now()
                """).params(service, name, value, scenario).update();
    }

    public void clear(String service, String name) {
        jdbc.sql("DELETE FROM ops.fault_switches WHERE service = ? AND name = ?").params(service, name).update();
    }

    public void clearAll() {
        jdbc.sql("DELETE FROM ops.fault_switches").update();
    }
}
