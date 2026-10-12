package dev.nightshift.chaoscontroller.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;

/**
 * Releases a service version: writes it to the shared releases volume, records the deploy, and
 * asks the service to exit so the container restart policy starts it on the new version. The
 * deploy completes when the service registers with that version.
 */
@Service
public class DeployService {

    private static final Logger log = LoggerFactory.getLogger(DeployService.class);

    private final ReleaseManifest manifest;
    private final DeployRegistry registry;
    private final ServiceClient services;
    private final FaultSwitchStore switches;
    private final Path releasesDir;

    public DeployService(ReleaseManifest manifest, DeployRegistry registry, ServiceClient services,
                         FaultSwitchStore switches, @Value("${nightshift.releases-dir}") String releasesDir) {
        this.manifest = manifest;
        this.registry = registry;
        this.services = services;
        this.switches = switches;
        this.releasesDir = Path.of(releasesDir);
    }

    public Deploy deploy(String service, String version, String initiatedBy) {
        String changelog = manifest.changelog(service, version);
        String previous = registry.latest(service).map(Deploy::version).orElse(manifest.service(service).baseline());
        writeRelease(service, version);
        Deploy deploy = registry.insert(service, version, previous, changelog, initiatedBy, "IN_PROGRESS", Instant.now());
        logDeployEvent(deploy);
        try {
            services.restart(service);
        } catch (RuntimeException e) {
            // The service may already be down; its restart policy will pick up the new version.
            log.warn("restart call to {} failed ({}); waiting for it to come back on {}", service, e.toString(), version);
        }
        return deploy;
    }

    /** Called by each service on startup. Returns the fault switches that apply to it. */
    public Map<String, String> register(String service, String version, String instance, Instant startedAt) {
        manifest.service(service);
        registry.recordStart(service, version, instance, startedAt);
        Deploy latest = registry.latest(service).orElse(null);
        if (latest == null) {
            registry.insert(service, version, null, "Initial registration.", "bootstrap", "COMPLETED", startedAt);
        } else if (latest.version().equals(version) && "IN_PROGRESS".equals(latest.status())) {
            registry.complete(latest.id(), Instant.now());
            log.info("deploy {} of {} {} completed", latest.id(), service, version);
        } else if (!latest.version().equals(version)) {
            Deploy d = registry.insert(service, version, latest.version(), "Version observed at startup.", "unknown",
                    "COMPLETED", startedAt);
            logDeployEvent(d);
        }
        return switches.forService(service);
    }

    public String desiredVersion(String service) {
        Path file = releasesDir.resolve(service + ".version");
        try {
            return Files.exists(file) ? Files.readString(file).trim() : manifest.service(service).baseline();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeRelease(String service, String version) {
        try {
            Files.createDirectories(releasesDir);
            Path tmp = releasesDir.resolve(service + ".version.tmp");
            Files.writeString(tmp, version + "\n");
            Files.move(tmp, releasesDir.resolve(service + ".version"), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write release file for " + service, e);
        }
    }

    private static void logDeployEvent(Deploy d) {
        log.atInfo()
                .addKeyValue("event", "deploy")
                .addKeyValue("change_service", d.service())
                .addKeyValue("change_old", d.previousVersion())
                .addKeyValue("change_new", d.version())
                .addKeyValue("changed_by", d.initiatedBy())
                .log("deploy {} of {}: {} -> {} by {} ({})", d.id(), d.service(), d.previousVersion(), d.version(),
                        d.initiatedBy(), d.changelog());
    }
}
