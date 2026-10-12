package dev.nightshift.inventory;

import dev.nightshift.common.settings.SettingDefinition;
import dev.nightshift.common.settings.SettingsCatalog;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"dev.nightshift.inventory", "dev.nightshift.common"})
@EnableScheduling
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }

    @Bean
    SettingsCatalog settingsCatalog() {
        return SettingsCatalog.of(
                SettingDefinition.intConfig("inventory.cache.ttl-seconds", 60, 1, 3600,
                        "How long a stock level stays in the Redis cache"),
                SettingDefinition.intConfig("inventory.restock.threshold", 10000, 0, 1000000,
                        "Restock a SKU when available stock falls below this"));
    }
}
