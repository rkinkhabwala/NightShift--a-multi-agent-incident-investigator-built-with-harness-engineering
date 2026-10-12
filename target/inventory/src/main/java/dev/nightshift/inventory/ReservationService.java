package dev.nightshift.inventory;

import dev.nightshift.common.release.ReleaseInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    public record Reservation(long orderId, List<Long> reservationIds) {
    }

    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final StockCache cache;
    private final ReservationAuditTrail audit;
    private final TransactionTemplate tx;
    private final boolean v23LogFormat;

    public ReservationService(StockRepository stock, ReservationRepository reservations, StockCache cache,
                              ReservationAuditTrail audit, TransactionTemplate tx, ReleaseInfo release) {
        this.stock = stock;
        this.reservations = reservations;
        this.cache = cache;
        this.audit = audit;
        this.tx = tx;
        this.v23LogFormat = release.isAtLeast("2.3");
    }

    public Reservation reserve(ReservationRequest request) {
        // Fast rejection from the cache before touching the database.
        for (ReservationRequest.Item item : request.items()) {
            OptionalInt cached = cache.get(item.sku());
            int available = cached.isPresent() ? cached.getAsInt() : loadIntoCache(item.sku());
            if (available < item.qty()) {
                throw new OutOfStockException(item.sku(), item.qty());
            }
        }
        Map<String, Integer> newLevels = new LinkedHashMap<>();
        List<Long> ids = tx.execute(status -> {
            List<Long> created = new ArrayList<>();
            for (ReservationRequest.Item item : request.items()) {
                int left = stock.take(item.sku(), item.qty())
                        .orElseThrow(() -> new OutOfStockException(item.sku(), item.qty()));
                newLevels.put(item.sku(), left);
                created.add(reservations.insert(request.orderId(), item.sku(), item.qty()));
            }
            return created;
        });
        newLevels.forEach(cache::put);
        for (ReservationRequest.Item item : request.items()) {
            audit.record(request.orderId(), item.sku(), item.qty());
            if (v23LogFormat) {
                log.info("reservation created order={} sku={} qty={}", request.orderId(), item.sku(), item.qty());
            } else {
                log.info("reserved {} x {} for order {}", item.qty(), item.sku(), request.orderId());
            }
        }
        return new Reservation(request.orderId(), ids);
    }

    private int loadIntoCache(String sku) {
        int available = stock.available(sku).orElseThrow(() -> new OutOfStockException(sku, 0));
        cache.put(sku, available);
        return available;
    }
}
