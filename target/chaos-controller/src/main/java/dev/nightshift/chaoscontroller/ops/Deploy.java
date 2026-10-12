package dev.nightshift.chaoscontroller.ops;

import java.time.Instant;

public record Deploy(long id, String service, String version, String previousVersion, String changelog,
                     String initiatedBy, String status, Instant deployedAt, Instant completedAt) {
}
