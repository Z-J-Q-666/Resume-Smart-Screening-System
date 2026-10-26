package com.resume.screening.cache;

import com.resume.screening.common.BizException;
import com.resume.screening.common.ErrorCode;
import com.resume.screening.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 上传限流：INCR + 首次设置 60s 过期。Redis 不可用时放行（限流不是业务正确性的一部分）。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadRateLimiter {

    private final StringRedisTemplate redis;
    private final AppProperties props;

    public void check(String userId) {
        int limit = props.getRateLimit().getUploadPerMinute();
        String key = RedisKeys.uploadRateLimit(userId);
        try {
            Long n = redis.opsForValue().increment(key);
            if (n != null && n == 1L) {
                redis.expire(key, Duration.ofMinutes(1));
            }
            if (n != null && n > limit) {
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                        "上传过于频繁，每分钟最多 " + limit + " 次请求");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("限流检查失败（放行） userId={} err={}", userId, e.getMessage());
        }
    }
}
