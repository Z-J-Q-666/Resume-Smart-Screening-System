package com.resume.screening.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 语义分缓存。
 *
 * <p>省的是真金白银：语义打分一次要调一次 LLM，而同一份简历投多个岗位、或重试、
 * 或对账任务补投，profile 和岗位要求都没变，模型输出在 temperature=0 下是可复用的。
 *
 * <p>为什么把 prompt 版本放在 value 里而不是 key 里：版本一换，旧缓存自然变成"未命中"
 * 并被覆盖，不会在 Redis 里堆一堆再也读不到的历史版本键。
 *
 * <p>Redis 只是加速层：任何异常都吞掉并当作未命中（需求 §3 D3），绝不因为缓存挂了就打不了分。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticScoreCache {

    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    public record CachedScore(int score, String reason, String promptVersion) {
    }

    public Optional<CachedScore> get(long resumeId, long jobId, String promptVersion) {
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(RedisKeys.semanticScore(resumeId, jobId));
            if (raw == null || raw.isEmpty()) {
                return Optional.empty();
            }
            String cachedVersion = str(raw.get("promptVersion"));
            if (!Objects.equals(cachedVersion, promptVersion)) {
                // prompt 变过，旧分数不再和新逻辑可比，按未命中处理
                log.info("语义分缓存版本不匹配 resumeId={} jobId={} cached={} now={}",
                        resumeId, jobId, cachedVersion, promptVersion);
                return Optional.empty();
            }
            return Optional.of(new CachedScore(
                    parseInt(raw.get("score")),
                    str(raw.get("reason")),
                    cachedVersion
            ));
        } catch (DataAccessException | NumberFormatException e) {
            log.warn("读语义分缓存失败 resumeId={} jobId={} err={}", resumeId, jobId, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(long resumeId, long jobId, int score, String reason, String promptVersion) {
        try {
            String key = RedisKeys.semanticScore(resumeId, jobId);
            Map<String, String> m = new HashMap<>();
            m.put("score", String.valueOf(score));
            m.put("reason", reason == null ? "" : reason);
            m.put("promptVersion", promptVersion == null ? "unknown" : promptVersion);
            redis.opsForHash().putAll(key, m);
            redis.expire(key, TTL);
        } catch (DataAccessException e) {
            log.warn("写语义分缓存失败 resumeId={} jobId={} err={}", resumeId, jobId, e.getMessage());
        }
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString();
        return s.isEmpty() ? null : s;
    }

    private static int parseInt(Object o) {
        return o == null ? 0 : Integer.parseInt(o.toString());
    }
}
