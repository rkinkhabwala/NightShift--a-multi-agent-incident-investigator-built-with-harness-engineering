package testsupport.common;

import dev.nightshift.common.settings.SettingDefinition;
import dev.nightshift.common.settings.SettingsCatalog;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Minimal app for common's own tests (outside dev.nightshift so services never scan it). */
@SpringBootApplication(scanBasePackages = "dev.nightshift.common")
public class CommonTestApp {

    @Bean
    SettingsCatalog settingsCatalog() {
        return SettingsCatalog.of(
                SettingDefinition.intConfig("demo.pool.max", 10, 1, 50, "pool size"),
                SettingDefinition.flag("demo_flag", false, "a flag"));
    }
}
