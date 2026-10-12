package dev.nightshift.common;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A Postgres container initialised with the same scripts as docker-compose (target/db/init).
 * Shared by the module tests through common's test-jar.
 */
public final class PostgresTestSupport {

    private PostgresTestSupport() {
    }

    /** @param withHistory include the ~1M-row order history (only orders' pricing tests need it) */
    public static PostgreSQLContainer<?> postgres(boolean withHistory) {
        Path init = locateInitDir();
        PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16.15-alpine")
                .withDatabaseName("nightshift")
                .withCopyFileToContainer(MountableFile.forHostPath(init.resolve("01-schema.sql")),
                        "/docker-entrypoint-initdb.d/01-schema.sql")
                .withCopyFileToContainer(MountableFile.forHostPath(init.resolve("02-seed.sql")),
                        "/docker-entrypoint-initdb.d/02-seed.sql");
        if (withHistory) {
            pg.withCopyFileToContainer(MountableFile.forHostPath(init.resolve("03-history.sql")),
                    "/docker-entrypoint-initdb.d/03-history.sql");
        }
        return pg;
    }

    private static Path locateInitDir() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path candidate = p.resolve("db/init");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("target/db/init not found from " + Path.of("").toAbsolutePath());
    }
}
