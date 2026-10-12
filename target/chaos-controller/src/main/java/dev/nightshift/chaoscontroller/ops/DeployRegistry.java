package dev.nightshift.chaoscontroller.ops;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Deploy history and service start records (ops schema). */
@Repository
public class DeployRegistry {

    private static final RowMapper<Deploy> DEPLOY = (rs, n) -> new Deploy(rs.getLong("id"), rs.getString("service"),
            rs.getString("version"), rs.getString("previous_version"), rs.getString("changelog"),
            rs.getString("initiated_by"), rs.getString("status"), rs.getTimestamp("deployed_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());

    private final JdbcClient jdbc;

    public DeployRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Deploy> latest(String service) {
        return jdbc.sql("SELECT * FROM ops.deploys WHERE service = ? ORDER BY deployed_at DESC, id DESC LIMIT 1")
                .param(service).query(DEPLOY).optional();
    }

    public Optional<Deploy> find(long id) {
        return jdbc.sql("SELECT * FROM ops.deploys WHERE id = ?").param(id).query(DEPLOY).optional();
    }

    public List<Deploy> list(String service, Instant since, int limit) {
        return jdbc.sql("""
                SELECT * FROM ops.deploys
                WHERE (CAST(? AS text) IS NULL OR service = ?) AND deployed_at >= ?
                ORDER BY deployed_at DESC, id DESC LIMIT ?
                """).params(service, service, Timestamp.from(since), limit).query(DEPLOY).list();
    }

    public Deploy insert(String service, String version, String previous, String changelog, String initiatedBy,
                         String status, Instant at) {
        long id = jdbc.sql("""
                INSERT INTO ops.deploys (service, version, previous_version, changelog, initiated_by, status, deployed_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """).params(service, version, previous, changelog, initiatedBy, status, Timestamp.from(at),
                "COMPLETED".equals(status) ? Timestamp.from(at) : null).query(Long.class).single();
        return find(id).orElseThrow();
    }

    public void complete(long id, Instant at) {
        jdbc.sql("UPDATE ops.deploys SET status = 'COMPLETED', completed_at = ? WHERE id = ?")
                .params(Timestamp.from(at), id).update();
    }

    public void fail(long id) {
        jdbc.sql("UPDATE ops.deploys SET status = 'FAILED', completed_at = now() WHERE id = ?").param(id).update();
    }

    public void recordStart(String service, String version, String instance, Instant startedAt) {
        jdbc.sql("INSERT INTO ops.service_starts (service, version, instance, started_at) VALUES (?, ?, ?, ?)")
                .params(service, version, instance, Timestamp.from(startedAt)).update();
    }

    public Map<String, Integer> startsSince(Instant since) {
        return jdbc.sql("SELECT service, count(*) FROM ops.service_starts WHERE started_at >= ? GROUP BY service")
                .param(Timestamp.from(since))
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getInt(2))).list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Optional<Instant> lastStart(String service) {
        return jdbc.sql("SELECT max(started_at) FROM ops.service_starts WHERE service = ?").param(service)
                .query((rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant()).optional();
    }
}
