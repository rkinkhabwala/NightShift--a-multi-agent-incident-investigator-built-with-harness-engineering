package dev.nightshift.payments;

import dev.nightshift.common.release.ReleaseInfo;
import dev.nightshift.common.settings.SettingDefinition;
import dev.nightshift.common.settings.SettingsCatalog;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = {"dev.nightshift.payments", "dev.nightshift.common"})
public class PaymentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentsApplication.class, args);
    }

    @Bean
    SettingsCatalog settingsCatalog() {
        return SettingsCatalog.of(
                SettingDefinition.intConfig("payments.inventory-client.timeout-ms", 2000, 1, 30000,
                        "Read timeout for calls to inventory /reservations"),
                SettingDefinition.intConfig("payments.decline-threshold-cents", 100000, 100, 10000000,
                        "Charges above this amount are declined by the risk rule"));
    }

    /** Release 1.4 replaced the loyalty discount calculation. */
    @Bean
    LoyaltyDiscount loyaltyDiscount(ReleaseInfo release) {
        return release.isAtLeast("1.4") ? new TieredLoyaltyDiscount() : new StandardLoyaltyDiscount();
    }
}
