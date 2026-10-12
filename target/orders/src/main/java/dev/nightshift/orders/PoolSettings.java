package dev.nightshift.orders;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import dev.nightshift.common.settings.RuntimeSettings;
import dev.nightshift.common.settings.SettingChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** Applies the pool config settings to the live Hikari pool, at startup and on every change. */
@Component
public class PoolSettings {

    static final String MAX_KEY = "orders.db.pool.max";
    static final String TIMEOUT_KEY = "orders.db.pool.timeout-ms";

    private static final Logger log = LoggerFactory.getLogger(PoolSettings.class);

    private final HikariDataSource dataSource;
    private final RuntimeSettings settings;

    public PoolSettings(DataSource dataSource, RuntimeSettings settings) {
        this.dataSource = hikari(dataSource);
        this.settings = settings;
    }

    private static HikariDataSource hikari(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource h) {
            return h;
        }
        try {
            return dataSource.unwrap(HikariDataSource.class);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("orders expects a Hikari connection pool", e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applyAtStartup() {
        apply();
    }

    @EventListener
    public void onChange(SettingChange change) {
        if (MAX_KEY.equals(change.key()) || TIMEOUT_KEY.equals(change.key())) {
            apply();
        }
    }

    synchronized void apply() {
        int max = settings.configInt(MAX_KEY);
        int timeout = settings.configInt(TIMEOUT_KEY);
        HikariConfigMXBean config = dataSource.getHikariConfigMXBean();
        config.setMinimumIdle(Math.min(2, max));
        config.setMaximumPoolSize(max);
        config.setConnectionTimeout(timeout);
        if (dataSource.getHikariPoolMXBean() != null) {
            // Retire existing connections so a smaller pool takes effect promptly.
            dataSource.getHikariPoolMXBean().softEvictConnections();
        }
        log.info("database pool now max={} connectionTimeoutMs={}", max, timeout);
    }

    int currentMax() {
        return dataSource.getHikariConfigMXBean().getMaximumPoolSize();
    }
}
