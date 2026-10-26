package com.resume.screening.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 简历状态缓存。Redis 只是 MySQL 的加速副本：
 * 任何 Redis 异常都吞掉并回落到 MySQL（需求 §3 D3、§8 原则）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeStatusCache {

    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public record CachedStatus(String status, String errorMsg, String candidateName) {
    }

    public void put(long resumeId, String status, String errorMsg, String candidateName) {
        try {
            String key = RedisKeys.resumeStatus(resumeId);
            Map<String, String> m = new HashMap<>();
            m.put("status", status == null ? "" : status);
            m.put("errorMsg", errorMsg == null ? "" : errorMsg);
            m.put("candidateName", candidateName == null ? "" : candidateName);
            redis.opsForHash().putAll(key, m);
            redis.expire(key, TTL);
        } catch (DataAccessException e) {
            log.warn("写状态缓存失败 resumeId={} err={}", resumeId, e.getMessage());
        }
    }

    public Optional<CachedStatus> get(long resumeId) {
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(RedisKeys.resumeStatus(resumeId));
            if (raw == null || raw.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new CachedStatus(
                    str(raw.get("status")),
                    str(raw.get("errorMsg")),
                    str(raw.get("candidateName"))
            ));
        } catch (DataAccessException e) {
            log.warn("读状态缓存失败 resumeId={} err={}", resumeId, e.getMessage());
            return Optional.empty();
        }
    }

    public void evict(long resumeId) {
        try {
            redis.delete(RedisKeys.resumeStatus(resumeId));
            redis.delete(RedisKeys.resumeProfile(resumeId));
        } catch (DataAccessException e) {
            log.warn("清缓存失败 resumeId={} err={}", resumeId, e.getMessage());
        }
    }

    public void putProfile(long resumeId, String profileJson) {
        try {
            redis.opsForValue().set(RedisKeys.resumeProfile(resumeId), profileJson, Duration.ofHours(1));
        } catch (DataAccessException e) {
            log.warn("写 Profile 缓存失败 resumeId={} err={}", resumeId, e.getMessage());
        }
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString();
        return s.isEmpty() ? null : s;
    }
}
