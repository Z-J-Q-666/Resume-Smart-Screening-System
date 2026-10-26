package com.resume.screening.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 消费端幂等锁。锁拿不到就当"别人正在处理"，直接跳过本条消息。
 * 删除锁走 Lua 比对 token，避免误删其它消费者刚抢到的锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLock {

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public String tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, token, ttl);
            return Boolean.TRUE.equals(ok) ? token : null;
        } catch (Exception e) {
            // Redis 不可用时不能把整条链路锁死：退化为无锁处理，
            // 幂等仍由"状态已 EXTRACTED 则跳过"兜底
            log.warn("获取锁失败（降级为无锁） key={} err={}", key, e.getMessage());
            return token;
        }
    }

    public void unlock(String key, String token) {
        if (token == null) {
            return;
        }
        try {
            redis.execute(RELEASE, List.of(key), token);
        } catch (Exception e) {
            log.warn("释放锁失败 key={} err={}", key, e.getMessage());
        }
    }
}
