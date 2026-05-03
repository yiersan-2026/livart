package com.artisanlab.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

@Service
public class RedisEmbeddingCacheService {
    private static final Logger log = LoggerFactory.getLogger(RedisEmbeddingCacheService.class);
    private static final String KEY_PREFIX = "livart:embedding-cache";

    private final boolean enabled;
    private final Duration ttl;
    private final StringRedisTemplate redisTemplate;

    @Autowired
    public RedisEmbeddingCacheService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${artisan.redis.embedding-cache.enabled:true}") boolean enabled,
            @Value("${artisan.redis.embedding-cache.ttl-days:30}") long ttlDays
    ) {
        this(enabled, ttlDays, redisTemplateProvider.getIfAvailable());
    }

    RedisEmbeddingCacheService(boolean enabled, long ttlDays, StringRedisTemplate redisTemplate) {
        this.enabled = enabled;
        this.ttl = Duration.ofDays(Math.max(1L, Math.min(365L, ttlDays)));
        this.redisTemplate = redisTemplate;
    }

    public String find(String namespace, String model, String questionHash) {
        if (!isUsable()) {
            return "";
        }
        String key = buildKey(namespace, model, questionHash);
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value != null && !value.isBlank()) {
                redisTemplate.expire(key, ttl);
                return value;
            }
            return "";
        } catch (Exception exception) {
            log.warn("[redis-embedding-cache] read failed key={} error={}", key, safeMessage(exception));
            return "";
        }
    }

    public void put(String namespace, String model, String questionHash, String embedding) {
        if (!isUsable() || embedding == null || embedding.isBlank()) {
            return;
        }
        String key = buildKey(namespace, model, questionHash);
        try {
            redisTemplate.opsForValue().set(key, embedding, ttl);
        } catch (Exception exception) {
            log.warn("[redis-embedding-cache] write failed key={} error={}", key, safeMessage(exception));
        }
    }

    private boolean isUsable() {
        return enabled && redisTemplate != null;
    }

    private static String buildKey(String namespace, String model, String questionHash) {
        return String.join(":",
                KEY_PREFIX,
                normalizePart(namespace),
                normalizePart(model),
                normalizePart(questionHash)
        );
    }

    private static String normalizePart(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "unknown";
        }
        return normalized.replaceAll("[^a-z0-9:_\\-]", "_");
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable == null ? "unknown" : throwable.getClass().getSimpleName();
        }
        return message.replaceAll("\\s+", " ").trim();
    }
}
