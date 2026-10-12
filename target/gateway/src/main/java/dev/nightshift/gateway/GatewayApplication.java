package dev.nightshift.gateway;

import dev.nightshift.common.settings.SettingDefinition;
import dev.nightshift.common.settings.SettingsCatalog;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = {"dev.nightshift.gateway", "dev.nightshift.common"})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    SettingsCatalog settingsCatalog() {
        return SettingsCatalog.of(
                SettingDefinition.intConfig(OrdersProxy.TIMEOUT_KEY, 8000, 10, 30000,
                        "Read timeout for calls to orders"));
    }
}
