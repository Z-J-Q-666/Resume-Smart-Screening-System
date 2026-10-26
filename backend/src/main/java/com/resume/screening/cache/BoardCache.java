package com.resume.screening.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 候选人排行榜。它只是 MySQL 的一个加速视图：
 * 打上分就更新，查 TOP N 才用它；分页列表仍以 MySQL 为准（需求 §8 原则 3）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BoardCache {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    public void update(long jobId, long resumeId, double score) {
        try {
            String key = RedisKeys.board(jobId);
            redis.opsForZSet().add(key, String.valueOf(resumeId), score);
            redis.expire(key, TTL);
        } catch (Exception e) {
            log.warn("更新排行榜失败 jobId={} resumeId={} err={}", jobId, resumeId, e.getMessage());
        }
    }

    /** 重算分数前先把旧分踢掉，避免排行榜短暂显示过期分数 */
    public void remove(long jobId, long resumeId) {
        try {
            redis.opsForZSet().remove(RedisKeys.board(jobId), String.valueOf(resumeId));
        } catch (Exception e) {
            log.warn("移除排行榜成员失败 jobId={} resumeId={} err={}", jobId, resumeId, e.getMessage());
        }
    }

    /** 从 Redis 取 TOP N，为空表示缓存缺失，调用方应回源重建。 */
    public List<Long> top(long jobId, int n) {
        try {
            Set<String> members = redis.opsForZSet()
                    .reverseRange(RedisKeys.board(jobId), 0, Math.max(n - 1, 0));
            if (members == null || members.isEmpty()) {
                return List.of();
            }
            List<Long> ids = new ArrayList<>(members.size());
            for (String m : members) {
                try {
                    ids.add(Long.parseLong(m));
                } catch (NumberFormatException ignore) {
                    // 脏成员跳过
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("读排行榜失败 jobId={} err={}", jobId, e.getMessage());
            return List.of();
        }
    }

    public boolean exists(long jobId) {
        try {
            Boolean has = redis.hasKey(RedisKeys.board(jobId));
            return Boolean.TRUE.equals(has);
        } catch (Exception e) {
            return false;
        }
    }

    public void replaceAll(long jobId, List<ZSetOperations.TypedTuple<String>> tuples) {
        try {
            String key = RedisKeys.board(jobId);
            redis.delete(key);
            if (!tuples.isEmpty()) {
                redis.opsForZSet().add(key, new LinkedHashSet<>(tuples));
                redis.expire(key, TTL);
            }
        } catch (Exception e) {
            log.warn("重建排行榜失败 jobId={} err={}", jobId, e.getMessage());
        }
    }
}
