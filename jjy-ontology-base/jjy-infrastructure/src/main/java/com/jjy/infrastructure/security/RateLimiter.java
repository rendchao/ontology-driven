package com.jjy.infrastructure.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 简单令牌桶限流器：按 key（通常是 API Key）做 QPS 限制
 * <p>P6 M1 入站限流：默认 100 QPS/账号，可在 application.yml 覆盖</p>
 *
 * <p>实现说明：
 * <ul>
 *   <li>每个 key 维护一个令牌桶，容量 = qpsLimit，每毫秒补充 ratePerMs = qpsLimit/1000</li>
 *   <li>每次请求 tryAcquire：当前令牌数 >= 1 则扣减 1</li>
 *   <li>不是严格意义上的"QPS"（更接近"每 10ms 内允许 1 个请求 × qpsLimit/100"），但足够防刷</li>
 *   <li>内存存储，单实例适用；集群部署需要换 Redis Lua</li>
 * </ul>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class RateLimiter {

    @Value("${jjy.inbound.rate-limit-qps:100}")
    private double qpsLimit;

    @Value("${jjy.inbound.rate-limit-enabled:true}")
    private boolean enabled;

    /** key -> (tokens, lastRefillNanos) */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 尝试获取 1 个令牌
     * @param key 限流维度（通常是 API Key）
     * @return true=放行 false=超限
     */
    public boolean tryAcquire(String key) {
        if (!enabled) return true;
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(qpsLimit));
        return b.tryConsume(1, qpsLimit);
    }

    /** 清空所有 bucket（运维用） */
    public void clear() {
        buckets.clear();
    }

    /** 当前 key 的剩余令牌数（监控用） */
    public double available(String key) {
        Bucket b = buckets.get(key);
        if (b == null) return qpsLimit;
        return Math.min(qpsLimit, b.tokens.get());
    }

    private static final class Bucket {
        final AtomicLong tokens;
        final AtomicLong lastRefillNanos;

        Bucket(double initial) {
            this.tokens = new AtomicLong((long) initial);
            this.lastRefillNanos = new AtomicLong(System.nanoTime());
        }

        synchronized boolean tryConsume(double permits, double capacity) {
            long now = System.nanoTime();
            long last = lastRefillNanos.get();
            double elapsedMs = (now - last) / 1_000_000.0;
            double refill = elapsedMs * capacity / 1000.0;
            if (refill > 0) {
                long cur = tokens.get();
                long next = (long) Math.min(capacity, cur + refill);
                tokens.set(next);
                lastRefillNanos.set(now);
            }
            while (true) {
                long cur = tokens.get();
                if (cur < permits) return false;
                if (tokens.compareAndSet(cur, (long) (cur - permits))) return true;
            }
        }
    }
}
