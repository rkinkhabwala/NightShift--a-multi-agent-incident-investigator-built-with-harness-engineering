package dev.nightshift.chaoscontroller.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nightshift.chaoscontroller.chaos.AlertSenderTestAccess;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AlertWebhookControllerTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void storesAlertmanagerAlertsNewestFirstAndCapsTheBuffer() {
        AlertWebhookController stub = new AlertWebhookController();
        for (int i = 0; i < AlertWebhookController.KEEP + 5; i++) {
            stub.receive(json.valueToTree(AlertSenderTestAccess.payload("HighErrorRate", "svc" + i, "page", "s", Instant.now())));
        }
        assertThat(stub.list()).hasSize(AlertWebhookController.KEEP);
        assertThat(stub.list().get(0).service()).isEqualTo("svc" + (AlertWebhookController.KEEP + 4));
        assertThat(stub.list().get(0).status()).isEqualTo("firing");
        assertThat(stub.clear()).containsEntry("cleared", AlertWebhookController.KEEP);
        assertThat(stub.list()).isEmpty();
    }
}
