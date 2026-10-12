package dev.nightshift.inventory;

import dev.nightshift.common.settings.RuntimeSettings;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.OptionalInt;

/** Read-through cache of available stock per SKU, in Redis (reached through Toxiproxy). */
@Component
public class StockCache {

    private final StringRedisTemplate redis;
    private final RuntimeSettings settings;

    public StockCache(StringRedisTemplate redis, RuntimeSettings settings) {
        this.redis = redis;
        this.settings = settings;
    }

    public OptionalInt get(String sku) {
        String v = redis.opsForValue().get(key(sku));
        return v == null ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(v));
    }

    public void put(String sku, int available) {
        redis.opsForValue().set(key(sku), Integer.toString(available),
                Duration.ofSeconds(settings.configInt("inventory.cache.ttl-seconds")));
    }

    public void evict(String sku) {
        redis.delete(key(sku));
    }

    private static String key(String sku) {
        return "stock:" + sku;
    }
}
