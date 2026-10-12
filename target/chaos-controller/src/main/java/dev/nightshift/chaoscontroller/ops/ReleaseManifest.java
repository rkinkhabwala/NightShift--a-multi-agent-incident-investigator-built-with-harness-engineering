package dev.nightshift.chaoscontroller.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.NoSuchElementException;

/** Which services exist, where they listen, and which versions can be deployed. */
@Component
public class ReleaseManifest {

    public record Service(int port, String baseline, Map<String, String> versions) {
    }

    record Manifest(Map<String, Service> services) {
    }

    private final Map<String, Service> services;

    public ReleaseManifest() {
        try (InputStream in = new ClassPathResource("releases.yml").getInputStream()) {
            this.services = new ObjectMapper(new YAMLFactory()).readValue(in, Manifest.class).services();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Map<String, Service> services() {
        return services;
    }

    public Service service(String name) {
        Service s = services.get(name);
        if (s == null) {
            throw new NoSuchElementException("unknown service: " + name);
        }
        return s;
    }

    public String changelog(String service, String version) {
        String log = service(service).versions().get(version);
        if (log == null) {
            throw new IllegalArgumentException(service + " has no release " + version
                    + " (known: " + service(service).versions().keySet() + ")");
        }
        return log;
    }
}
