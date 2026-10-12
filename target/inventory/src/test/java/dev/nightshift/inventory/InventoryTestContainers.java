package dev.nightshift.inventory;

import dev.nightshift.common.PostgresTestSupport;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

/** Postgres + Redis for inventory integration tests. */
interface InventoryTestContainers {

    @Container
    @ServiceConnection
    PostgreSQLContainer<?> POSTGRES = PostgresTestSupport.postgres(false);

    @Container
    @ServiceConnection(name = "redis")
    GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.11-alpine").withExposedPorts(6379);
}
