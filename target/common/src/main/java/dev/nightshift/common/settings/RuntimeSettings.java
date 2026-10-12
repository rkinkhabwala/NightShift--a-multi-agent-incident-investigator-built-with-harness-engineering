package dev.nightshift.common.settings;

import dev.nightshift.common.release.ReleaseInfo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime config and feature flags for this service. Values are served from memory; every
 * change is persisted (ops.settings) with an audit row (ops.settings_history), published as a
 * {@link SettingChange} event and logged as a structured event for annotations.
 */
@Service
public class RuntimeSettings {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSettings.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final String service;
    private final Map<String, SettingDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, String> values = new ConcurrentHashMap<>();

    public RuntimeSettings(JdbcClient jdbc, TransactionTemplate tx, ApplicationEventPublisher events,
                           ReleaseInfo release, SettingsCatalog catalog) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.service = release.service();
        for (SettingDefinition d : catalog.definitions()) {
            definitions.put(id(d.kind(), d.key()), d);
            values.put(id(d.kind(), d.key()), d.defaultValue());
        }
    }

    /** Applies persisted overrides. A database outage at startup leaves the defaults in place. */
    @PostConstruct
    void load() {
        try {
            jdbc.sql("SELECT kind, key, value FROM ops.settings WHERE service = ?")
                    .param(service)
                    .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)})
                    .list()
                    .forEach(row -> {
                        SettingDefinition d = definitions.get(row[0] + ":" + row[1]);
                        if (d != null) {
                            values.put(id(d.kind(), d.key()), d.validate(row[2]));
                        }
                    });
        } catch (RuntimeException e) {
            log.warn("could not load persisted settings, using defaults: {}", e.toString());
        }
    }

    public String get(SettingKind kind, String key) {
        String v = values.get(id(kind, key));
        if (v == null) {
            throw new NoSuchElementException("unknown " + kind.path() + " key: " + key);
        }
        return v;
    }

    public int configInt(String key) {
        return Integer.parseInt(get(SettingKind.CONFIG, key));
    }

    public boolean flag(String key) {
        return Boolean.parseBoolean(get(SettingKind.FLAG, key));
    }

    public Map<String, String> all(SettingKind kind) {
        Map<String, String> out = new LinkedHashMap<>();
        definitions.values().stream()
                .filter(d -> d.kind() == kind)
                .forEach(d -> out.put(d.key(), values.get(id(kind, d.key()))));
        return out;
    }

    public List<SettingDefinition> definitions(SettingKind kind) {
        return definitions.values().stream().filter(d -> d.kind() == kind).toList();
    }

    public synchronized SettingChange change(SettingKind kind, String key, String value, String changedBy, String reason) {
        SettingDefinition d = definitions.get(id(kind, key));
        if (d == null) {
            throw new NoSuchElementException("unknown " + kind.path() + " key: " + key);
        }
        String newValue = d.validate(value);
        String oldValue = values.get(id(kind, key));
        String who = changedBy == null || changedBy.isBlank() ? "unknown" : changedBy;
        Instant now = Instant.now();
        Long historyId = tx.execute(status -> {
            jdbc.sql("""
                    INSERT INTO ops.settings (service, kind, key, value, updated_at) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (service, kind, key) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at
                    """)
                    .params(service, kind.name(), key, newValue, Timestamp.from(now))
                    .update();
            return jdbc.sql("""
                    INSERT INTO ops.settings_history (service, kind, key, old_value, new_value, changed_by, reason, changed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """)
                    .params(service, kind.name(), key, oldValue, newValue, who, reason, Timestamp.from(now))
                    .query(Long.class)
                    .single();
        });
        values.put(id(kind, key), newValue);
        SettingChange change = new SettingChange(historyId, service, kind, key, oldValue, newValue, who, reason, now);
        log.atInfo()
                .addKeyValue("event", kind == SettingKind.CONFIG ? "config_change" : "flag_change")
                .addKeyValue("change_service", service)
                .addKeyValue("change_key", key)
                .addKeyValue("change_old", oldValue)
                .addKeyValue("change_new", newValue)
                .addKeyValue("changed_by", who)
                .log("{} {} changed from {} to {} by {}", kind.path(), key, oldValue, newValue, who);
        events.publishEvent(change);
        return change;
    }

    public List<SettingChange> history(SettingKind kind, Instant since, int limit) {
        return jdbc.sql("""
                SELECT id, service, kind, key, old_value, new_value, changed_by, reason, changed_at
                FROM ops.settings_history
                WHERE service = ? AND kind = ? AND changed_at >= ?
                ORDER BY changed_at DESC, id DESC LIMIT ?
                """)
                .params(service, kind.name(), Timestamp.from(since), limit)
                .query((rs, n) -> new SettingChange(rs.getLong("id"), rs.getString("service"),
                        SettingKind.valueOf(rs.getString("kind")), rs.getString("key"), rs.getString("old_value"),
                        rs.getString("new_value"), rs.getString("changed_by"), rs.getString("reason"),
                        rs.getTimestamp("changed_at").toInstant()))
                .list();
    }

    private static String id(SettingKind kind, String key) {
        return kind.name() + ":" + key;
    }
}
