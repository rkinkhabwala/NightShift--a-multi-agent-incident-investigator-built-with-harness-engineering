package dev.nightshift.payments;

import dev.nightshift.common.http.DownstreamResult;
import dev.nightshift.common.settings.RuntimeSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ChargeService {

    private static final Logger log = LoggerFactory.getLogger(ChargeService.class);

    public record Charge(long chargeId, long orderId, long amountCents, long discountCents, String status) {
    }

    /** A charge that could not be completed; status is what payments returns to its caller. */
    public static class ChargeFailedException extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        public ChargeFailedException(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public HttpStatus status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    private final LoyaltyDiscount discount;
    private final InventoryClient inventory;
    private final ChargeRepository charges;
    private final RuntimeSettings settings;

    public ChargeService(LoyaltyDiscount discount, InventoryClient inventory, ChargeRepository charges,
                         RuntimeSettings settings) {
        this.discount = discount;
        this.inventory = inventory;
        this.charges = charges;
        this.settings = settings;
    }

    public Charge charge(ChargeRequest request) {
        long discountCents = discount.discountCents(request);
        long amount = request.amountCents() - discountCents;

        if (amount > settings.configInt("payments.decline-threshold-cents")) {
            long id = charges.insert(request, amount, discountCents, "DECLINED");
            log.info("charge {} declined for order {}: amount {} over risk threshold", id, request.orderId(), amount);
            throw new ChargeFailedException(HttpStatus.PAYMENT_REQUIRED, "payment_declined", "amount over risk threshold");
        }

        DownstreamResult reserved = inventory.reserve(request);
        if (!reserved.isSuccess()) {
            if (reserved.status() == 409) {
                throw new ChargeFailedException(HttpStatus.CONFLICT, "out_of_stock", reserved.body());
            }
            log.error("inventory reservation failed for order {}: {}", request.orderId(), reserved.describe());
            HttpStatus status = reserved.failure() == DownstreamResult.Failure.TIMEOUT
                    ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
            throw new ChargeFailedException(status, "inventory_unavailable",
                    "inventory reservation failed: " + reserved.describe());
        }

        long id = charges.insert(request, amount, discountCents, "CAPTURED");
        return new Charge(id, request.orderId(), amount, discountCents, "CAPTURED");
    }
}
