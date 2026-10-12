package dev.nightshift.orders;

import dev.nightshift.common.http.DownstreamResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    static final int MAX_NOTE_LENGTH = 120;

    public static class OrderFailedException extends RuntimeException {
        private final HttpStatus status;
        private final String code;
        private final Long orderId;

        public OrderFailedException(HttpStatus status, String code, String message, Long orderId) {
            super(message);
            this.status = status;
            this.code = code;
            this.orderId = orderId;
        }

        public HttpStatus status() {
            return status;
        }

        public String code() {
            return code;
        }

        public Long orderId() {
            return orderId;
        }
    }

    private final OrderRepository orders;
    private final PricingService pricing;
    private final PaymentsClient payments;
    private final TransactionTemplate tx;

    public OrderService(OrderRepository orders, PricingService pricing, PaymentsClient payments, TransactionTemplate tx) {
        this.orders = orders;
        this.pricing = pricing;
        this.payments = payments;
        this.tx = tx;
    }

    public OrderRepository.Order create(CreateOrderRequest request) {
        OrderRepository.Customer customer = orders.customer(request.customerId())
                .orElseThrow(() -> new OrderFailedException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown_customer",
                        "unknown customer " + request.customerId(), null));
        List<String> skus = request.items().stream().map(CreateOrderRequest.Item::sku).distinct().toList();
        Map<String, Integer> listPrices = orders.prices(skus);
        for (String sku : skus) {
            if (!listPrices.containsKey(sku)) {
                throw new OrderFailedException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown_sku", "unknown sku " + sku, null);
            }
        }
        String note = acceptedNote(request);
        Map<String, Integer> unitPrices = pricing.unitPrices(skus, listPrices);
        List<OrderRepository.Line> lines = request.items().stream()
                .map(i -> new OrderRepository.Line(i.sku(), i.qty(), unitPrices.get(i.sku())))
                .toList();
        long total = lines.stream().mapToLong(l -> (long) l.qty() * l.unitPriceCents()).sum();

        long orderId = tx.execute(status -> orders.insert(customer.id(), total, note, lines));

        DownstreamResult charge = payments.charge(orderId, customer, total, request.items());
        if (charge.isSuccess()) {
            orders.finish(orderId, "PAID", null);
            return orders.find(orderId).orElseThrow();
        }
        orders.finish(orderId, "FAILED", charge.describe());
        if (charge.isClientError() && (charge.status() == 402 || charge.status() == 409)) {
            String code = charge.status() == 402 ? "payment_declined" : "out_of_stock";
            throw new OrderFailedException(HttpStatus.valueOf(charge.status()), code, "order " + orderId + " " + code, orderId);
        }
        log.error("payment failed for order {}: payments returned {}", orderId, charge.describe());
        HttpStatus status = charge.failure() == DownstreamResult.Failure.TIMEOUT ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
        throw new OrderFailedException(status, "payment_error", "payment failed: " + charge.describe(), orderId);
    }

    /** Notes over the limit are dropped (the order still goes through) and logged for support. */
    private String acceptedNote(CreateOrderRequest request) {
        String note = request.customerNote();
        if (note == null || note.isBlank()) {
            return null;
        }
        if (note.length() > MAX_NOTE_LENGTH) {
            log.warn("customer note dropped for customer {}: {} chars exceeds limit {}; note was: {}",
                    request.customerId(), note.length(), MAX_NOTE_LENGTH, note.length() > 500 ? note.substring(0, 500) : note);
            return null;
        }
        return note;
    }
}
