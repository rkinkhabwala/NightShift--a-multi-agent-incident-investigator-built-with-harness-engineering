package dev.nightshift.orders;

import dev.nightshift.common.settings.SettingDefinition;
import dev.nightshift.common.settings.SettingsCatalog;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = {"dev.nightshift.orders", "dev.nightshift.common"})
public class OrdersApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }

    @Bean
    SettingsCatalog settingsCatalog() {
        return SettingsCatalog.of(
                SettingDefinition.intConfig(PoolSettings.MAX_KEY, 10, 1, 50,
                        "Maximum connections in the orders database pool"),
                SettingDefinition.intConfig(PoolSettings.TIMEOUT_KEY, 3000, 100, 30000,
                        "How long a request waits for a database connection"),
                SettingDefinition.intConfig("orders.payments-client.timeout-ms", 5000, 10, 30000,
                        "Read timeout for calls to payments /charges"),
                SettingDefinition.flag(PricingService.NEW_PRICING_FLAG, false,
                        "Demand-based pricing computed from order history"));
    }
}
